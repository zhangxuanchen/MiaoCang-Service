package com.miaocang.laya;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成书归位：把一张理解卡片判进「哪本书 · 哪一章 · 哪一个二级主题（节）」。
 *
 * <p><b>为什么是独立一层分类</b>：内容归档（{@link LayaClassifyService}）回答的是「这篇内容属于
 * 哪个书籍分类」，粒度粗、候选是十几个分类；成书归位回答的是「这个知识点落在书架上的哪个位置」，
 * 候选是骨架里全部节（可能几十个），且节名之间往往只差一两个字 —— 这正是语义判定比字符串匹配
 * 更值得花一次前向的地方。
 *
 * <p><b>与 LLM 的分工</b>：laya 只能从**给定候选**里挑（choice 原语），无法发明新书/新节，
 * 所以「结构生成」永远归 LLM；laya 负责的是「结构已定，把卡分进去」这一步细分。
 * 门控（p1 与 p1−p2）没过的卡、以及主动选「都不贴切」出口的卡，一律交回调用方由 LLM 兜底 ——
 * 侧车不可用时整套逻辑退化为「全部交给 LLM」，行为与接入前完全一致（fail-open）。
 */
@Service
public class LayaCardRouter {

    private static final Logger log = LoggerFactory.getLogger(LayaCardRouter.class);

    /** 问题 id：与调用方约定的单问题。 */
    private static final String Q_SECTION = "section";

    /**
     * 「都不贴切」出口。choice 是归一化的，永远存在一个 top1 —— 没有出口时模型会把任何卡片
     * 硬塞进最接近的节。给一个显式出口，选中即视为「该卡需要 LLM 另立新书/新节」。
     */
    static final String NEW_OPTION = "__new__";

    private final LayaProperties props;
    private final LayaDecisionClient client;

    public LayaCardRouter(LayaProperties props, LayaDecisionClient client) {
        this.props = props;
        this.client = client;
    }

    /** 本层是否启用（总开关 + 本层开关都开）。 */
    public boolean active() {
        return props.isEnabled() && props.getPlacement().isEnabled();
    }

    /** 一张待归位的卡片（只取判定需要的三个字段，不耦合调用方的内部结构）。 */
    public record Card(String no, String topic, String point) {
    }

    /**
     * 一个候选节：书 + 章 + 节。
     *
     * <p>{@code key} 对判定本身是不透明的，只用来把结果映射回调用方的骨架 ——
     * 增量归位传书文件名，全量成书传「书序/章序/节序」，两处共用同一套判定逻辑。
     */
    public record Section(String key, String bookTitle, String chapter, String name) {

        /** 展示给模型的标签。 */
        public String label() {
            return "《" + bookTitle + "》" + chapter + " ／ " + name;
        }
    }

    /** 过门控的判定结果：命中的节 + 当时的前两概率（日志与调参用）。 */
    private record Pick(Section section, double p1, double p2) {
    }

    /**
     * 一轮判定的结论。
     *
     * @param routed    过门控、由 laya 定下的归位（卡号 → 节）
     * @param delegated 未定（未过门控 / 选了「都不贴切」/ 超时 / 侧车不可用），交 LLM 兜底
     * @param judged    真正跑完判定的卡数
     * @param note      一行日志摘要
     */
    public record Ruling(Map<String, Section> routed, List<String> delegated,
                         int judged, int total, String note) {
    }

    /**
     * 逐卡判定归位。
     *
     * <p>单卡一次 {@code /v1/predict}（state = 该卡，questions = 一个 choice）。实测单卡
     * 80~130ms，所以卡多时靠 {@code budget-ms} 兜住总量：预算耗尽后剩余卡直接交 LLM，
     * 不让一张卡拖住整轮整理。
     */
    public Ruling route(List<Section> sections, List<Card> cards) {
        LayaProperties.Placement cfg = props.getPlacement();
        List<String> all = new ArrayList<>();
        for (Card c : cards) all.add(c.no());
        if (!active()) {
            return new Ruling(Map.of(), all, 0, cards.size(), "归位判定已禁用，全部交 LLM");
        }
        if (sections == null || sections.isEmpty()) {
            return new Ruling(Map.of(), all, 0, cards.size(), "骨架里没有可用的节，全部交 LLM");
        }
        if (cards.isEmpty()) {
            return new Ruling(Map.of(), List.of(), 0, 0, "没有待归位的卡片");
        }

        long deadline = System.currentTimeMillis() + cfg.getBudgetMs();
        Map<String, Section> routed = new LinkedHashMap<>();
        List<String> delegated = new ArrayList<>();
        int judged = 0;
        boolean aborted = false;
        long t0 = System.currentTimeMillis();

        for (Card card : cards) {
            if (System.currentTimeMillis() > deadline) {
                aborted = true;
                break;
            }
            List<Section> pool = preselect(sections, card, cfg.getMaxOptions());
            try {
                Pick hit = judgeOne(pool, card, cfg);
                judged++;
                if (hit == null) {
                    delegated.add(card.no());
                } else {
                    routed.put(card.no(), hit.section());
                    log.debug("[laya] 归位 {} 过门控（p1={} p2={}）→ 《{}》{}／{}", card.no(),
                            String.format("%.3f", hit.p1()), String.format("%.3f", hit.p2()),
                            hit.section().bookTitle(), hit.section().chapter(), hit.section().name());
                }
            } catch (LayaUnavailableException e) {
                /* 侧车不可达：再逐卡重试只是把 2 秒超时乘以卡数，直接整体放弃 */
                log.warn("[laya] 侧车不可用，成书归位全部交 LLM 兜底: {}", e.getMessage());
                aborted = true;
                break;
            } catch (Exception e) {
                log.debug("[laya] 归位 {} 判定失败，交 LLM 兜底: {}", card.no(), e.toString());
                delegated.add(card.no());
            }
        }
        if (aborted) {
            for (Card c : cards) {
                if (!routed.containsKey(c.no()) && !delegated.contains(c.no())) delegated.add(c.no());
            }
        }

        long ms = System.currentTimeMillis() - t0;
        String note = String.format("成书归位：判定 %d/%d 张，laya 采纳 %d 张，交 LLM 兜底 %d 张（耗时 %.1fs%s）",
                judged, cards.size(), routed.size(), delegated.size(), ms / 1000.0,
                aborted ? "，预算耗尽或侧车不可用提前收手" : "");
        return new Ruling(routed, delegated, judged, cards.size(), note);
    }

    /**
     * 判一张卡。返回 null = 交 LLM（未过门控，或模型选了「都不贴切」出口）。
     *
     * <p>候选的 key 用序号而非节名：节名可能重复/含特殊字符，序号作 key 最稳；
     * 节名放 value 当选项文案（与 {@link LayaClassifyService} 同一约定）。
     */
    private Pick judgeOne(List<Section> pool, Card card, LayaProperties.Placement cfg) {
        /* 选项文案必须短。实测把「《书》章 ／ 节」整串当 criteria 的 value 时，长且几乎相同的
           公共前缀会把判定稀释掉（同一张卡 p1 从 0.99 掉到 0.29，正确项直接掉出 top1）；
           节名本身才是有效信号。只有节名在同批候选里撞名（跨书同名节）时才补书名/章名消歧。 */
        Map<String, Integer> nameCount = new LinkedHashMap<>();
        for (Section s : pool) {
            String n = nz(s.name());
            nameCount.put(n, nameCount.getOrDefault(n, 0) + 1);
        }

        Map<String, Section> byKey = new LinkedHashMap<>();
        Map<String, String> criteria = new LinkedHashMap<>();
        int i = 1;
        for (Section s : pool) {
            String key = String.valueOf(i++);
            byKey.put(key, s);
            String value = nz(s.name());
            if (nameCount.getOrDefault(nz(s.name()), 0) > 1) {
                value = value + "（《" + s.bookTitle() + "》" + s.chapter() + "）";
            }
            criteria.put(key, value);
        }
        criteria.put(NEW_OPTION, "以上任何一节都不贴切");

        Map<String, Object> questions = LayaQuestions.questions(LayaQuestions.entry(Q_SECTION,
                LayaQuestions.choice("给这张知识点卡片选择它最应该归入的二级主题（节）。"
                        + "只选一个；如果以上任何一节都不贴切，请选「" + NEW_OPTION + "」，不要勉强塞进最接近的一节。",
                        criteria)));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("title", nz(card.topic()));
        state.put("text", truncate(nz(card.point()), cfg.getStateMaxChars()));

        LayaDecisionClient.LayaAnswer a = client
                .predict(state, questions, cfg.getTimeoutMs())
                .answer(Q_SECTION);
        String chosen = a.choice();
        if (chosen == null || NEW_OPTION.equals(chosen)) return null;
        Section hit = byKey.get(chosen);
        if (hit == null) return null;

        double[] p = a.topTwo();
        boolean certain = p[0] >= cfg.getMinProb() && (p[0] - p[1]) >= cfg.getMargin();
        if (!certain) {
            log.debug("[laya] 归位 {} 未过门控（p1={} p2={} → 最接近「{}」），交 LLM",
                    card.no(), String.format("%.3f", p[0]), String.format("%.3f", p[1]), hit.label());
            return null;
        }
        return new Pick(hit, p[0], p[1]);
    }

    /**
     * 候选节预筛：节数没超上限就全量喂；超了按「卡片文字与节标签的二元字组重叠」取前 K。
     *
     * <p>宁可预筛掉一些又让剩余候选保持精简：multilingual 的选项头只有 256，选项一多精度就衰减。
     * 预筛只影响「laya 看到哪些候选」，被筛掉的节并非判不了 —— 未过门控的卡仍会由 LLM 兜底。
     */
    private List<Section> preselect(List<Section> sections, Card card, int maxOptions) {
        if (sections.size() <= maxOptions) return sections;
        String text = nz(card.topic()) + " " + nz(card.point());
        Set<String> query = bigrams(text);

        /* 留一个位给「都不贴切」出口，故只取 maxOptions-1 个节 */
        int keep = Math.max(2, maxOptions - 1);
        List<Section> ranked = new ArrayList<>(sections);
        Map<Section, Double> score = new LinkedHashMap<>();
        for (Section s : sections) score.put(s, overlap(query, bigrams(s.label())));
        ranked.sort((x, y) -> Double.compare(score.get(y), score.get(x)));
        if (ranked.size() > keep) ranked = new ArrayList<>(ranked.subList(0, keep));
        return ranked;
    }

    /** 二元字组（中文按字切，天然适配）。 */
    private static Set<String> bigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        String t = s == null ? "" : s.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        if (out.isEmpty() && !t.isEmpty()) out.add(t);
        return out;
    }

    /** query 有多少比例的字组在目标里出现过。 */
    private static double overlap(Set<String> query, Set<String> target) {
        if (query.isEmpty() || target.isEmpty()) return 0;
        Set<String> hit = new HashSet<>(query);
        hit.retainAll(target);
        return (double) hit.size() / query.size();
    }

    private static String truncate(String s, int max) {
        String t = s == null ? "" : s.strip();
        return max > 0 && t.length() > max ? t.substring(0, max) : t;
    }

    private static String nz(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }
}