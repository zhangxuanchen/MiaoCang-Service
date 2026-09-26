package com.miaocang.laya;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-2 会话意图快路由（「System 1」）：在 LLM（System 2）之前用一次 800ms 以内的前向
 * 判出主人这步想干什么，从而：
 * <ul>
 *   <li><b>短路</b>——只对<b>只读</b>的 {@code open_file} 生效：确定要打开某个真实存在的文件时，
 *       直接发 tool/toolresult 协议串让前端开文件，不构建 Agent（省下数秒 ReAct 启动）；</li>
 *   <li><b>软提示</b>——其余情况把预判拼成【System 1 预判】块注入本轮提示词，让 LLM 少绕弯路。</li>
 * </ul>
 *
 * <p>{@code edit_file} / 删除 / 执行命令一律<b>只给软提示</b>：写动作误判的代价远大于慢几秒，
 * 宁可漏判不误判。{@code unsafe} 仅标注不拦截（拦截是后续阶段的事）。
 *
 * <p>state 只放 {@code message} 与 {@code page}，<b>不放工作区文件清单</b>——清单动辄上千字符，
 * 会挤爆 multilingual 的状态预算（1024 − head 256 = 768 token）。
 * 文件定位交给 {@link #matchFile} 在 Java 侧用字符串比对完成。
 */
@Service
public class LayaIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(LayaIntentRouter.class);

    private static final String Q_INTENT = "intent";

    /**
     * 短路要求的 p1−p2 最小间隔。p1 高但 p2 紧随其后说明模型在两个意图间摇摆
     * （无温度校准的 multilingual checkpoint 会给出这种虚高 p1），此时宁可交给 LLM。
     */
    private static final double SURE_MARGIN = 0.20;

    /** 意图标签 → 中文说明（提示词与日志共用同一份文案）。 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("open_file", "打开/查看工作区里已存在的某个文件");
        LABELS.put("edit_file", "修改、重写或补充工作区里某个文件的内容");
        LABELS.put("search", "检索、查找或汇总库里的信息");
        LABELS.put("chat", "闲聊、提问或讨论，不需要动工作区文件");
        LABELS.put("unsafe", "删除文件、执行系统命令、泄露密钥等危险操作");
    }

    private final LayaProperties props;
    private final LayaDecisionClient client;
    /** 判定要跑在独立线程上（与领地检索并发），不能占用请求线程。 */
    private final java.util.concurrent.ExecutorService executor;

    public LayaIntentRouter(LayaProperties props, LayaDecisionClient client,
                            @org.springframework.beans.factory.annotation.Qualifier("layaExecutor")
                            java.util.concurrent.ExecutorService executor) {
        this.props = props;
        this.client = client;
        this.executor = executor;
    }

    /**
     * 并发发起一次判定（调用方随后照常干活，再 {@link #join} 有界等待）。
     *
     * @return 立即返回的 future；{@code miaocang.laya.enabled=false} 时返回 null
     */
    public java.util.concurrent.CompletableFuture<IntentResult> classifyAsync(String message, String page) {
        if (!props.isEnabled()) return null;
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> classify(message, page), executor);
    }

    /**
     * 有界等待判定结果（≤ {@code intent.timeout-ms}）：超时立即放弃并取消，让这一轮照原路径走。
     * 绝不抛异常——快路由是加速器，不是必经之路。
     */
    public IntentResult join(java.util.concurrent.CompletableFuture<IntentResult> future) {
        if (future == null) return null;
        try {
            return future.get(props.getIntent().getTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.debug("[laya] System 1 预判放弃: {}", e.toString());
            return null;
        }
    }

    /** 短路资格：只对**只读**的 open_file、p1 达标且与 p2 拉开间隔才放行（写动作一律交给 LLM）。 */
    public boolean shortCircuitEligible(IntentResult r) {
        return r != null && r.is("open_file")
                && r.certain() >= props.getIntent().getSureThreshold()
                && r.margin() >= SURE_MARGIN;
    }

    /**
     * 一次意图判定结果。
     *
     * @param intent  命中的意图标签（{@code open_file}/{@code edit_file}/{@code search}/{@code chat}/{@code unsafe}）
     * @param certain 最高概率 p1，0..1
     * @param margin  p1 − p2，用于判断模型是否在两个意图间摇摆
     */
    public record IntentResult(String intent, double certain, double margin) {

        public boolean is(String name) {
            return name.equals(intent);
        }

        public String label() {
            return LABELS.getOrDefault(intent, intent);
        }
    }

    /**
     * 跑一次意图判定（阻塞，调用方应在 {@code layaExecutor} 里并发发起）。
     *
     * @return 判定结果；{@code miaocang.laya.enabled=false} 时返回 null
     */
    public IntentResult classify(String message, String page) {
        if (!props.isEnabled()) return null;
        Map<String, String> criteria = new LinkedHashMap<>();
        LABELS.forEach((k, v) -> criteria.put(k, v));
        Map<String, Object> questions = LayaQuestions.questions(
                LayaQuestions.entry(Q_INTENT, LayaQuestions.choice(
                        "阅读 `message`（主人刚说的话，`page` 是主人当前停留的页面），"
                                + "判断主人这一步最想要什么。拿不准就选 chat。", criteria)));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", message == null ? "" : message);
        state.put("page", page == null ? "" : page);

        LayaDecisionClient.LayaPrediction pred = client.predict(state, questions, props.getIntent().getTimeoutMs());
        LayaDecisionClient.LayaAnswer ans = pred.answer(Q_INTENT);
        String intent = ans.choice();
        if (intent == null || !LABELS.containsKey(intent)) {
            log.debug("[laya] 意图判定未给出有效标签: {}", intent);
            return null;
        }
        /* 确定度取 choice 的 p1、间隔取 p1−p2，而不是 confidence 或另加一个 noul 问题：
           multilingual 出厂没做温度校准，confidence 与被喂错命题的 noul 都不可信
           （实测最明确的「帮我打开 x.md」，noul 也只得 0.13）；p1 与 p1−p2 在同一分布内自洽，
           与归档门控同一套判据。 */
        double[] top = ans.topTwo();
        return new IntentResult(intent, top[0], top[0] - top[1]);
    }

    // ---------------------------------------------------------------- 文件定位（Java 侧，零模型成本）

    /**
     * 在工作区文件清单里唯一定位主人提到的那份文件。
     *
     * <p>先按「相对路径整串命中」找，再退到「文件名（去扩展名）命中」找；
     * 任何一种出现多个候选（歧义）就返回 null，交给 LLM 去澄清——短路宁可漏判不误判。
     *
     * @param paths 工作区相对路径清单（调用方给，本类不碰文件系统）
     * @return 唯一命中的相对路径；无法唯一确定时 null
     */
    public static String matchFile(String message, List<String> paths) {
        if (message == null || message.isBlank() || paths == null || paths.isEmpty()) return null;
        String msg = message.toLowerCase(java.util.Locale.ROOT);

        String byFullPath = unique(paths.stream()
                .filter(p -> msg.contains(p.toLowerCase(java.util.Locale.ROOT)))
                .toList());
        if (byFullPath != null) return byFullPath;

        List<String> byName = paths.stream().filter(p -> {
            String stem = stem(p);
            return stem.length() >= 2 && msg.contains(stem);
        }).toList();
        return unique(byName);
    }

    private static String unique(List<String> hits) {
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * 短路件：把只读 open_file 的确定指令转成前端已认识的工作区动作协议。
     * 文件内容仍由前端另行 GET，这里只负责「打开哪一份」。
     */
    public static Map<String, Object> openFileToolResult(String relPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "file");
        m.put("action", "read");
        m.put("ok", true);
        m.put("path", relPath);
        m.put("message", "System 1 快路由：直接打开 " + relPath + "（未唤醒 Agent）");
        return m;
    }

    /** 未短路时的软提示块，拼装位置与「领地检索命中」块一致。 */
    public String hintBlock(IntentResult r) {
        if (r == null || !props.getIntent().isHintEnabled()) return null;
        StringBuilder sb = new StringBuilder("【System 1 预判】这一步我快速判断主人的意图是：")
                .append(r.label()).append("（确定度 ").append(Math.round(r.certain() * 100)).append("%）。");
        if (r.is("open_file")) {
            sb.append("若主人指的是工作区里已存在的文件，直接 read_file 给他看，别绕圈子。");
        } else if (r.is("edit_file")) {
            sb.append("先 read_file 看清原文，再做最小改动，改完告诉主人改了哪几处。");
        } else if (r.is("search")) {
            sb.append("优先用领地检索与工作区搜索给出处，别凭记忆编。");
        } else if (r.is("unsafe")) {
            sb.append("这涉及删除/执行命令/泄露密钥类危险操作，先向主人确认，不要擅自执行。");
        } else {
            sb.append("照常回答即可。");
        }
        sb.append("预判仅供参考，与主人的原话冲突时以原话为准。");
        return sb.toString();
    }

    /** 启动自检：跑一次真实判定，把「侧车可用」变成日志里的事实。 */
    public String selfTest() {
        if (!props.isEnabled()) return "已禁用（miaocang.laya.enabled=false）";
        try {
            IntentResult r = classify("帮我打开 会话笔记.md", "书房/文档");
            if (r == null) return "未通过（侧车不可达或未给出有效标签）";
            return String.format("OK intent=%s(%s) p1=%.2f p2=%.2f 短路=%s",
                    r.intent(), r.label(), r.certain(), r.certain() - r.margin(),
                    shortCircuitEligible(r) ? "放行" : "不放行");
        } catch (Exception e) {
            log.warn("[laya] 意图自检失败: {}", e.toString());
            return "失败: " + e;
        }
    }
}