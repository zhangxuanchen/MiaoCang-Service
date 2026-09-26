package com.miaocang.laya;

import com.miaocang.entity.BookType;
import com.miaocang.service.GravityEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-1 内容自动分类打分：把「关键词计数」升级为「laya 语义判定」，输出仍是
 * {@link GravityEngine.TypeScore}——前端与既有「判定阈值」展示一行都不用改，只是量纲从
 * 关键词命中次数（score）变成统一概率分（prob × 10）。
 *
 * <p>为什么不能只用关键词：关键词一个都没命中时（最需要语义判断的场景），旧打分直接进收集箱。
 * 这里先用关键词做零成本预筛（top-K）缩小候选，再让 multilingual checkpoint 做一次前向；
 * 候选不足 2 个时放开为「全部可用类型取前 12」，否则上面那种场景仍然进不了模型。
 *
 * <p>门控用 <b>p1 与 p1−p2</b>，不用 confidence：multilingual 出厂不带温度校准，
 * 绝对置信度在拟合前不可信（日志里两边分数都会打印，便于对照）。
 *
 * <p>侧车不可用 / 超时 / 已禁用 → 返回 {@code modelGate == null}，调用方回退原关键词阈值，
 * 行为与接入前完全一致（fail-open，不报错不阻塞）。
 */
@Service
public class LayaClassifyService {

    private static final Logger log = LoggerFactory.getLogger(LayaClassifyService.class);

    /** 每个候选类型带进 criteria 的引力关键词个数（再多会挤占 state 的 token 预算）。 */
    private static final int CRITERIA_KEYWORDS = 8;

    /** 概率低于此值的候选不进 typeScores，避免落一堆噪声场。 */
    private static final double MIN_KEEP_PROB = 0.02;

    /** {@code classify.top-k} 未配置时的兜底候选数（即官方建议的 ≤20 选项区间）。 */
    private static final int DEFAULT_TOP_K = 12;

    /** 问题 id（与 criteria 的候选标签一一对应）。 */
    private static final String Q_CATEGORY = "category";

    private final LayaProperties props;
    private final LayaDecisionClient client;

    public LayaClassifyService(LayaProperties props, LayaDecisionClient client) {
        this.props = props;
        this.client = client;
    }

    /**
     * 归档打分结果。
     *
     * @param scores    候选分类分数（已按概率降序；laya 未参与时即关键词分数）
     * @param modelGate 模型门控结论：true 归档 / false 进收集箱 / <b>null = laya 未参与，调用方按关键词阈值判定</b>
     * @param note      一行日志摘要（判定依据）
     */
    public record Decision(List<GravityEngine.TypeScore> scores, Boolean modelGate, String note) {
    }

    /**
     * 判定内容该归入哪个分类。
     *
     * @param keywordScores 关键词引力打分原始结果（预筛依据，同时作为失败回退值）
     */
    public Decision decide(List<BookType> types, String title, String body,
                           List<GravityEngine.TypeScore> keywordScores) {
        if (!props.isEnabled() || types == null || types.isEmpty()) {
            return new Decision(keywordScores, null, null);
        }
        List<BookType> candidates = preselect(types, keywordScores, props.getClassify().getTopK());
        if (candidates.size() < 2) {
            // 候选只有一个，choice 无意义；交给关键词阈值
            return new Decision(keywordScores, null, null);
        }
        try {
            return infer(candidates, title, body, keywordScores);
        } catch (Exception e) {
            log.warn("[laya] 语义分类降级为关键词打分: {}", e.toString());
            return new Decision(keywordScores, null, null);
        }
    }

    /**
     * 预筛候选分类。
     *
     * <p>关键词命中 ≥2 个：信关键词，取分数最高的前 K（K = {@code classify.top-k}）。
     *
     * <p>命中 0~1 个（**这正是 laya 要救的场景**）：关键词不足以定候选，但不许发生两件事 ——
     * <ol>
     *   <li>丢掉那个唯一的命中。否则「恰好 1 个命中、且其 id 不在前 K」的内容等于关键词白算；</li>
     *   <li>退化成「按类型顺序取前 K」。那会让排在后面的分类永远无法被选中 —— 实测这里就是
     *       id 13..25 共 13 个类型在关键词零命中时被彻底排除在外。</li>
     * </ol>
     * 故放开为「命中项打头 + 全部非桶类型」，数量不作 K 限制。实测（一篇中文技术随笔，同一个
     * checkpoint）：12 候选 p1=0.303 / 24 候选 p1=0.281 —— 选项变多不会显著摊薄 p1，
     * 而官方说明也确认选项数 &gt;20 只是精度衰减、不会失败。
     */
    private List<BookType> preselect(List<BookType> types, List<GravityEngine.TypeScore> keywordScores, int topK) {
        int k = topK <= 0 ? DEFAULT_TOP_K : topK;
        Map<Long, BookType> byId = new LinkedHashMap<>();
        for (BookType t : types) byId.put(t.getId(), t);

        List<BookType> hits = new ArrayList<>();
        if (keywordScores != null) {
            for (GravityEngine.TypeScore s : keywordScores) {
                BookType t = byId.get(s.typeId());
                if (t != null && !hits.contains(t)) hits.add(t);
            }
        }
        if (hits.size() >= 2) return hits.subList(0, Math.min(k, hits.size()));

        List<BookType> out = new ArrayList<>(hits);
        for (BookType t : types) {
            if (isBucket(t) || out.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    /** 「收件箱」是客户端兜底目录、只是待归档桶，不该作为语义分类的候选（与 LibrarySyncService 同一约定）。 */
    private static boolean isBucket(BookType t) {
        return t.getName() != null && t.getName().contains("收件箱");
    }

    /** 一次前向：questions = { category: choice(候选类型) }，state = { title, text }。 */
    private Decision infer(List<BookType> candidates, String title, String body,
                           List<GravityEngine.TypeScore> keywordScores) {
        Map<String, String> criteria = new LinkedHashMap<>();
        Map<String, BookType> byId = new LinkedHashMap<>();
        for (BookType t : candidates) {
            String id = String.valueOf(t.getId());
            criteria.put(id, label(t));
            byId.put(id, t);
        }
        Map<String, Object> questions = LayaQuestions.questions(LayaQuestions.entry(Q_CATEGORY,
                LayaQuestions.choice("判断这篇内容最应该归入下面哪一个书籍分类。只选一个；"
                        + "如果都不贴切，选最接近的那个，不要勉强。", criteria)));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("title", title == null ? "" : title);
        state.put("text", truncate(body, props.getClassify().getStateMaxChars()));

        LayaDecisionClient.LayaAnswer a = client
                .predict(state, questions, props.getClassify().getTimeoutMs())
                .answer(Q_CATEGORY);
        Map<String, Double> probs = a.probabilities();
        String chosen = a.choice();

        List<GravityEngine.TypeScore> mapped = new ArrayList<>();
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            BookType t = byId.get(e.getKey());
            if (t == null || e.getValue() == null || e.getValue() < MIN_KEEP_PROB) continue;
            mapped.add(toScore(t, e.getValue(), keywordScores));
        }
        /* 服务端只回 choice 不带 probabilities 时，退化成「必选」——门控仍按 p1/p2 走 */
        if (mapped.isEmpty() && chosen != null && byId.containsKey(chosen)) {
            mapped.add(toScore(byId.get(chosen), 1.0, keywordScores));
        }
        if (mapped.isEmpty()) {
            return new Decision(List.of(), null, "模型未给出可用分类");
        }
        mapped.sort((x, y) -> Double.compare(y.score(), x.score()));

        double[] p = a.topTwo();
        boolean gate = p[0] >= props.getClassify().getMinProb()
                && (p[0] - p[1]) >= props.getClassify().getMargin();
        String note = String.format("语义判定 category=%s p1=%.3f p2=%.3f 门控=%s（阈值 p1≥%.2f 且间隔≥%.2f）",
                chosen, p[0], p[1], gate ? "通过" : "未达", props.getClassify().getMinProb(), props.getClassify().getMargin());
        return new Decision(mapped, gate, note);
    }

    /** criteria 里单个候选的文案：类型名 + 描述 + 该类型的引力关键词。 */
    private String label(BookType t) {
        StringBuilder sb = new StringBuilder(t.getName() == null ? "" : t.getName());
        if (t.getDescription() != null && !t.getDescription().isBlank()) {
            sb.append("：").append(t.getDescription().strip());
        }
        String[] kws = GravityEngine.parseKeywords(t.getGravityKeywords());
        if (kws.length > 0) {
            sb.append("（关键词：");
            for (int i = 0; i < Math.min(kws.length, CRITERIA_KEYWORDS); i++) {
                if (i > 0) sb.append("、");
                sb.append(kws[i]);
            }
            sb.append("）");
        }
        return sb.toString();
    }

    /** 映射回既有结构：概率 × 10 当分数，命中的关键词沿用关键词打分的结论。 */
    private GravityEngine.TypeScore toScore(BookType t, double prob, List<GravityEngine.TypeScore> keywordScores) {
        List<String> matched = List.of();
        if (keywordScores != null) {
            for (GravityEngine.TypeScore s : keywordScores) {
                if (s.typeId().equals(t.getId())) {
                    matched = s.matchedKeywords();
                    break;
                }
            }
        }
        return new GravityEngine.TypeScore(t.getId(), t.getName(), t.getIcon(), t.getColor(),
                Math.round(prob * 1000) / 100.0, matched);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return max > 0 && t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 启动自检：用两个临时类型跑一次真实判定，把「侧车可用」变成日志里的事实。
     * 任何异常都吞掉（自检失败不该影响启动），返回一行摘要。
     */
    public String selfTest() {
        if (!props.isEnabled()) return "已禁用（miaocang.laya.enabled=false）";
        try {
            List<BookType> demo = List.of(
                    fakeType(1L, "技术研发", "编程、工程、工具链相关", "代码,架构,部署"),
                    fakeType(2L, "生活随笔", "日常记录、心情、杂谈", "随笔,日记,心情"));
            Decision d = decide(demo, "自检：Spring Boot 启动流程",
                    "本文记录一次服务启动时的依赖装配与探活实现。", List.of());
            if (d.modelGate() == null) return "未通过（侧车不可达或候选不足）";
            return "OK " + d.note() + " → " + (d.scores().isEmpty() ? "-" : d.scores().get(0).name());
        } catch (Exception e) {
            log.warn("[laya] 分类自检失败: {}", e.toString());
            return "失败: " + e;
        }
    }

    private static BookType fakeType(Long id, String name, String desc, String keywords) {
        BookType t = new BookType();
        t.setId(id);
        t.setName(name);
        t.setDescription(desc);
        t.setGravityKeywords(keywords);
        return t;
    }
}