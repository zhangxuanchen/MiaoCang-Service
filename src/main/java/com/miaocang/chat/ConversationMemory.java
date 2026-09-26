package com.miaocang.chat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话上下文管理：存时压四层压缩（完整移植自 Memory-Observatory）。
 *
 * <p>每只猫的会话状态由官方 {@link JsonFileAgentStateStore} 持久化，根目录 =
 * {@code <library.dir>/<cat.workspacePath>/.context/}，按 cat 天然隔离。
 *
 * <p>四层监控（存时压，落库即减）：
 * <ul>
 *   <li>L1 单条超长 → 头 + 尾 截断</li>
 *   <li>L2 超过 {@code keepRecent} 轮 → 最旧一轮折叠进 summary，原文归档</li>
 *   <li>L3 summary 累计超 {@code summaryThreshold} → 调模型重摘要（否则规则累加兜底）</li>
 *   <li>L4 主动清空 → {@link #clear}</li>
 * </ul>
 *
 * <p>伴生文件（与 agent_state.json 同目录）：
 * <ul>
 *   <li>{@code turns.json} —— 累计轮次</li>
 *   <li>{@code tools.json} —— 累计工具输出字符</li>
 *   <li>{@code fold-trail.txt} —— 折叠足迹（最近 N 条）</li>
 *   <li>{@code archive/<id>.json} —— 被折叠轮次的完整原文（可经工具召回）</li>
 * </ul>
 *
 * <p>注出历史按三段组织：早期要旨(summary) + 中段折叠标记 + 最近原文(KEEP_RECENT 轮)。
 */
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** 状态持久化 key，与官方 ReActAgent 保存 agent_state 用同一键，便于互操作。 */
    private static final String STATE_KEY = "agent_state";

    private final CatWorkspaceService catWorkspace;
    private final ChatProperties props;

    /** 待压缩标记集：用户点击「标记压缩」后置位，下一轮 Agent 运行时消费并执行压缩。 */
    private final Set<String> pendingCompress = ConcurrentHashMap.newKeySet();

    /** 压缩日志每 key 保留最近 N 条（in-memory，重启不丢持久化部分）。 */
    private final Map<String, ArrayDeque<CompressionRecord>> compressionLogs = new ConcurrentHashMap<>();

    public ConversationMemory(CatWorkspaceService catWorkspace, ChatProperties props) {
        this.catWorkspace = catWorkspace;
        this.props = props;
    }

    /** 会话隔离键（由 Controller 生成）：{workspacePath}/{agentId}/{sessionId}。 */
    public static String key(String workspacePath, String agentId, String sessionId) {
        return workspacePath + "/" + agentId + "/" + sessionId;
    }

    /* ---- 伴生文件路径 ---- */

    /** JsonFileAgentStateStore 的根目录（仅在注入的确实是该实现时可用）。 */
    private Path storeRoot(Resolved r) {
        AgentStateStore s = store(r);
        return (s instanceof JsonFileAgentStateStore js) ? js.getRootDirectory() : null;
    }

    /** 该会话的目录：{@code storeRoot/<b64(userId)>/<sessionId>/}（与 agent_state.json 同目录）。 */
    private Path sessionDir(Resolved r) {
        Path root = storeRoot(r);
        if (root == null) return null;
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(r.userId().getBytes(StandardCharsets.UTF_8));
        return root.resolve(b64).resolve(r.sessionId());
    }

    private Path turnFile(Resolved r) {
        Path d = sessionDir(r);
        return d == null ? null : d.resolve("turns.json");
    }

    private Path toolFile(Resolved r) {
        Path d = sessionDir(r);
        return d == null ? null : d.resolve("tools.json");
    }

    private Path foldTrailFile(Resolved r) {
        Path d = sessionDir(r);
        return d == null ? null : d.resolve("fold-trail.txt");
    }

    private Path archiveFile(Resolved r, String archiveId) {
        Path d = sessionDir(r);
        return d == null ? null : d.resolve("archive").resolve(archiveId + ".json");
    }

    /** 归档相对路径（相对会话目录），作为「可找回标记」里的回调锚点。 */
    private static String archiveRelative(String archiveId) {
        return "archive/" + archiveId + ".json";
    }

    /* ---- 轮次 / 工具字符 ---- */

    private int loadTurns(Resolved r) {
        Path f = turnFile(r);
        if (f == null || !Files.isRegularFile(f)) return 0;
        try {
            return Integer.parseInt(Files.readString(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveTurns(Resolved r, int n) {
        Path f = turnFile(r);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, Integer.toString(n), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 轮次落盘失败不阻断对话
        }
    }

    private long loadToolChars(Resolved r) {
        Path f = toolFile(r);
        if (f == null || !Files.isRegularFile(f)) return 0;
        try {
            return Long.parseLong(Files.readString(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveToolChars(Resolved r, long n) {
        Path f = toolFile(r);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, Long.toString(n), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 工具计量落盘失败不阻断
        }
    }

    /** 追加本轮累计工具输出字符到会话持久化计量。 */
    public void recordTool(String key, long chars) {
        Resolved r = resolve(key);
        if (chars <= 0) return;
        synchronized (this) {
            saveToolChars(r, loadToolChars(r) + chars);
        }
    }

    /** 读该会话累计工具输出字符。 */
    public long toolChars(String key) {
        return loadToolChars(resolve(key));
    }

    /* ---- 折叠足迹 ---- */

    /** 记录一次折叠：时间 + 该轮用户问题摘句，追加并保留最近 N 条。 */
    private void recordFold(Resolved r, String userText) {
        Path f = foldTrailFile(r);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            int cap = props.getFoldTrailCap();
            int snipLen = props.getFoldSnippet();
            String snip = (userText == null ? "" : userText).replace('\n', ' ').replace('\t', ' ');
            if (snip.length() > snipLen) snip = snip.substring(0, snipLen) + "…";
            String line = System.currentTimeMillis() + "\t" + snip;
            ArrayDeque<String> lines = new ArrayDeque<>();
            if (Files.isRegularFile(f)) {
                for (String l : Files.readAllLines(f)) {
                    if (!l.isBlank()) lines.addLast(l);
                }
            }
            lines.addLast(line);
            while (lines.size() > cap) lines.pollFirst();
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 折叠足迹落盘失败不阻断
        }
    }

    /** 读取某会话最近折叠足迹（新→旧）。无则空表。 */
    public List<Map<String, Object>> foldTrail(String key) {
        Path f = foldTrailFile(resolve(key));
        if (f == null || !Files.isRegularFile(f)) return List.of();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String l : Files.readAllLines(f)) {
                int t = l.indexOf('\t');
                out.add(0, Map.of(
                        "ts", t > 0 ? Long.parseLong(l.substring(0, t)) : 0L,
                        "snippet", t > 0 ? l.substring(t + 1) : l));
            }
            return out.stream().limit(props.getFoldTrailCap()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ---- 记忆归档（可找回全文） ---- */

    /** 把一轮（user + assistant）完整原文归档落盘并返回 archiveId；失败或无可落盘根返回 null。
     *  用 nano 时间戳作 id：折叠常在同一毫秒内连续折叠多轮，须保证每轮 id 唯一防覆盖。 */
    private String archiveRound(Resolved r, String userText, String asstText) {
        String id = Long.toString(System.nanoTime());
        Path f = archiveFile(r, id);
        if (f == null) return null;
        try {
            Files.createDirectories(f.getParent());
            String body = "【归档 id=" + id + "】该轮完整原文（用户/助手两段）：\n"
                    + "———— 用户 ————\n" + (userText == null ? "" : userText)
                    + "\n———— 助手 ————\n" + (asstText == null ? "" : asstText);
            Files.writeString(f, body, StandardCharsets.UTF_8);
            return id;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按 archiveId 读回某次折叠/压缩的完整原文；id 不存在返回明确提示。 */
    public String findArchive(String key, String archiveId) {
        if (archiveId == null || archiveId.isEmpty()) {
            return "ERROR: 缺少归档 id，请提供折叠标记里的 archiveId 参数。";
        }
        Resolved r = resolve(key);
        Path f = archiveFile(r, archiveId);
        if (f == null || !Files.isRegularFile(f)) {
            return "ERROR: 未找到归档 id=" + archiveId + "，请确认该 id 来自某条【记忆归档】标记。";
        }
        try {
            return "已找回归档 " + archiveId + " 的完整原文：\n" + Files.readString(f);
        } catch (Exception e) {
            return "ERROR: 读取归档失败: " + e.getMessage();
        }
    }

    /* ---- 上下文读 ---- */

    /** 当前会话的历史上下文块，按三层结构组织；无历史返回 null。 */
    public String context(String key) {
        AgentState st = load(key);
        if (st == null) return null;
        StringBuilder sb = new StringBuilder();
        List<Msg> ctx = st.getContext();
        String summary = st.getSummary() == null ? "" : st.getSummary();
        if (!summary.isBlank()) {
            sb.append("【早期要旨】\n").append(summary).append("\n");
            sb.append("【中段折叠】\n更早的对话已折叠为上摘要，如需较早细节请明确追问。\n");
        }
        sb.append("【最近原文】\n");
        for (int i = 0; i < ctx.size(); i += 2) {
            sb.append("- 用户：").append(ctx.get(i).getTextContent()).append("\n");
            if (i + 1 < ctx.size()) {
                sb.append("  助手：").append(ctx.get(i + 1).getTextContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 只读快照：供前端记忆面板轮询展示。 */
    public MemorySnapshot snapshot(String key) {
        Resolved r = resolve(key);
        AgentState st = load(r);
        if (st == null) {
            return new MemorySnapshot(false, "", 0, 0, false, 0, 0,
                    loadTurns(r), loadToolChars(r), List.of());
        }
        String summary = st.getSummary() == null ? "" : st.getSummary();
        long summaryChars = summary.length();
        long recentChars = 0;
        List<Msg> ctx = st.getContext();
        for (Msg m : ctx) {
            String c = m.getTextContent();
            if (c != null) recentChars += c.length();
        }
        List<RecentTurn> recent = new ArrayList<>();
        for (int i = 0; i < ctx.size(); i += 2) {
            Msg u = ctx.get(i);
            Msg a = i + 1 < ctx.size() ? ctx.get(i + 1) : null;
            recent.add(new RecentTurn(
                    u == null || u.getTextContent() == null ? "" : u.getTextContent(),
                    a == null || a.getTextContent() == null ? "" : a.getTextContent()));
        }
        return new MemorySnapshot(true, summary, countUserTurns(ctx),
                summaryChars + recentChars, !summary.isBlank(), summaryChars, recentChars,
                loadTurns(r) > 0 ? loadTurns(r) : countUserTurns(ctx),
                loadToolChars(r), recent);
    }

    /** 最近原文中的一轮（user + assistant）。 */
    public record RecentTurn(String user, String assistant) {
    }

    /** 记忆面板只读快照。 */
    public record MemorySnapshot(boolean exists, String summary, int recentTurns, long estChars,
                                 boolean folded, long summaryChars, long recentChars, int totalTurns,
                                 long toolChars, List<RecentTurn> recent) {
    }

    /* ---- 写（核心：四层监控） ---- */

    /** 记录一轮对话。 */
    public void append(String key, String user, String assistant, ContextSummarizer summarizer) {
        Resolved r = resolve(key);
        synchronized (this) {
            AgentState st = load(r);
            if (st == null) {
                st = AgentState.builder().userId(r.userId()).sessionId(r.sessionId()).build();
            }
            List<Msg> ctx = st.contextMutable();
            // L1：写入前对单条超长做保头尾截断
            ctx.add(userMsg(truncateL1(user)));
            ctx.add(assistantMsg(truncateL1(assistant)));
            int userTurns = countUserTurns(ctx);
            // L2：超过保留轮数，每次把最旧一轮折叠进摘要，逐轮收敛
            while (userTurns > props.getKeepRecent()) {
                Msg oldestUser = ctx.get(0);
                Msg oldestAsst = ctx.size() > 1 ? ctx.get(1) : null;
                recordFold(r, oldestUser == null ? "" : oldestUser.getTextContent());
                fold(st, r, oldestUser, oldestAsst, summarizer);
                ctx.remove(0);
                if (oldestAsst != null && ctx.size() > 0) {
                    ctx.remove(0);
                }
                userTurns = countUserTurns(ctx);
            }
            save(r, st);
        }
        synchronized (this) {
            saveTurns(r, loadTurns(r) + 1);
        }
    }

    /** 清空某会话上下文（L4：主动开新上下文，等价于删除该槽）。 */
    public void clear(String key) {
        Resolved r = resolve(key);
        try {
            store(r).delete(r.userId(), r.sessionId());
        } catch (Exception e) {
            log.warn("[ConversationMemory] 清空会话上下文失败（不阻断）: {}", e.toString());
        }
        try {
            Path f = turnFile(r);
            if (f != null) Files.deleteIfExists(f);
        } catch (Exception ignored) {
            // 清空轮次伴生文件失败不阻断
        }
    }

    /* ---- 压缩自收敛（用户主动标记后，下一轮消费） ---- */

    /** 标记某会话待压缩（仅置位，不立即执行）。 */
    public void markCompress(String key) {
        pendingCompress.add(key);
    }

    /** 消费待压缩标记：置位则返回 true 并清除。 */
    public boolean consumeCompress(String key) {
        return pendingCompress.remove(key);
    }

    /** 该会话当前是否有未消费的压缩标记。 */
    public boolean isCompressMarked(String key) {
        return pendingCompress.contains(key);
    }

    /** 预算超限后触发「系统自收敛」：按 L1→L2→L3→L4 逐层折叠。 */
    public boolean compressMem(String key, long targetMemChars, ContextSummarizer summarizer) {
        Resolved r = resolve(key);
        synchronized (this) {
            AgentState st = load(r);
            if (st == null) return true;
            long before = estMemChars(st);
            boolean compressed = false;
            List<Msg> ctx = st.contextMutable();

            // L1：单条超长截断
            for (int i = 0; i < ctx.size(); i++) {
                Msg m = ctx.get(i);
                String c = m.getTextContent();
                if (c != null && c.length() > props.getTruncateThreshold()) {
                    ctx.set(i, wrapMsg(m.getRole(), truncateL1(c)));
                    compressed = true;
                }
            }
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) return true;

            // L2：保留最近 keepRecent 轮，折叠更早轮次
            while (countUserTurns(ctx) > props.getKeepRecent()) {
                Msg oldestUser = ctx.get(0);
                Msg oldestAsst = ctx.size() > 1 ? ctx.get(1) : null;
                recordFold(r, oldestUser == null ? "" : oldestUser.getTextContent());
                fold(st, r, oldestUser, oldestAsst, summarizer, true);
                ctx.remove(0);
                if (oldestAsst != null && ctx.size() > 0) ctx.remove(0);
                compressed = true;
            }
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) return true;

            // L3：仍超限，对最近窗口逐轮折叠
            while (estMemChars(st) > targetMemChars) {
                if (ctx.size() < 2) break;
                Msg oldestUser = ctx.get(0);
                Msg oldestAsst = ctx.get(1);
                recordFold(r, oldestUser.getTextContent());
                fold(st, r, oldestUser, oldestAsst, summarizer, true);
                ctx.remove(0);
                ctx.remove(0);
                compressed = true;
            }
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) return true;

            // L4：规则已无空间，唯一一次模型重摘要
            String sum0 = st.getSummary();
            if (summarizer != null && sum0 != null && !sum0.isBlank()) {
                try {
                    String sum = summarizer.summarize(sum0);
                    if (sum != null && !sum.isBlank()) {
                        st.setSummary(sum);
                        compressed = true;
                    }
                } catch (Exception ignored) {
                    // 摘要异常回退：保留原摘要
                }
            }
            if (compressed) {
                save(r, st);
                long after = estMemChars(st);
                recordCompression(key, before, after, targetMemChars, after <= targetMemChars);
                return after <= targetMemChars;
            }
            return estMemChars(st) <= targetMemChars;
        }
    }

    public record CompressionRecord(long ts, long memCharsBefore, long memCharsAfter,
                                    long targetChars, boolean achieved) {
    }

    private void recordCompression(String key, long before, long after, long target, boolean achieved) {
        ArrayDeque<CompressionRecord> q = compressionLogs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.offerFirst(new CompressionRecord(System.currentTimeMillis(), before, after, target, achieved));
            while (q.size() > props.getCompressLogCap()) q.pollLast();
        }
    }

    public List<CompressionRecord> compressionLog(String key) {
        ArrayDeque<CompressionRecord> q = compressionLogs.get(key);
        if (q == null) return List.of();
        synchronized (q) {
            return List.copyOf(q);
        }
    }

    /* ---- 内部辅助 ---- */

    private boolean finishPhase(AgentState st, Resolved r, long before, long target,
                                boolean compressed, String key) {
        save(r, st);
        long cur = estMemChars(st);
        if (cur <= target) {
            if (compressed) recordCompression(key, before, cur, target, true);
            return true;
        }
        return false;
    }

    /** 估算当前会话 MEMORY 体积（字符）= 摘要长度 + context 各条文本长度之和。 */
    private static long estMemChars(AgentState st) {
        long n = st.getSummary() == null ? 0 : st.getSummary().length();
        for (Msg m : st.getContext()) {
            String c = m.getTextContent();
            if (c != null) n += c.length();
        }
        return n;
    }

    private void fold(AgentState st, Resolved r, Msg user, Msg asst, ContextSummarizer summarizer) {
        fold(st, r, user, asst, summarizer, false);
    }

    /** 把最旧一轮折叠进摘要。
     *  forceFallback=true 时强制纯规则累加截断，绝不动用模型（用于压缩自收敛的低成本分层）。 */
    private void fold(AgentState st, Resolved r, Msg user, Msg asst,
                      ContextSummarizer summarizer, boolean forceFallback) {
        String userT = user.getTextContent();
        String asstT = asst != null ? asst.getTextContent() : "";
        String archiveId = archiveRound(r, userT, asstT);
        String cur = st.getSummary() == null ? "" : st.getSummary();
        String joined = cur.isBlank() ? userT + "\n" + asstT : cur + "\n" + userT + "\n" + asstT;
        String sum = null;
        if (!forceFallback && summarizer != null && joined.length() >= props.getSummaryThreshold()) {
            try {
                sum = summarizer.summarize(joined);
            } catch (Exception ignored) {
                // 摘要调用异常回退截断
            }
        }
        String base = (sum != null && !sum.isBlank()) ? sum : fallback(cur, userT, asstT);
        st.setSummary(base + marker(archiveId, userT));
    }

    private String marker(String archiveId, String userText) {
        if (archiveId == null) return "";
        int snipLen = props.getFoldSnippet();
        String snip = (userText == null ? "" : userText).replace('\n', ' ').replace('\t', ' ').trim();
        if (snip.length() > snipLen) snip = snip.substring(0, snipLen) + "…";
        return "\n【记忆归档 #" + archiveId + "】如需找回被压缩的该轮完整内容：\n"
                + "  从哪找回：会话目录下 " + archiveRelative(archiveId) + "\n"
                + "  归档 id：#" + archiveId + "\n"
                + "  大概内容：「" + (snip.isEmpty() ? "(无文本摘句)" : snip) + "」\n"
                + "  什么情况召回：当用户需要该轮完整原文/细节、而上下文仅存摘要时\n"
                + "  使用方法：调用工具 memory_recall(archiveId=\"" + archiveId + "\") 读取全文。";
    }

    /** L1：单条文本超长时保头尾截断。 */
    private String truncateL1(String text) {
        if (text == null || text.length() <= props.getTruncateThreshold()) return text;
        return text.substring(0, props.getTruncateHead())
                + "\n…[内容超长已截断]…\n"
                + text.substring(text.length() - props.getTruncateTail());
    }

    /** 摘要回退：原样拼接并截断到 maxSummaryChars。 */
    private String fallback(String summary, String user, String asst) {
        String suffix = user + "\n" + asst;
        String joined = summary.isBlank() ? suffix : summary + "\n" + suffix;
        int cap = props.getMaxSummaryChars();
        return joined.length() > cap ? joined.substring(0, cap) : joined;
    }

    private static int countUserTurns(List<Msg> ctx) {
        int n = 0;
        for (Msg m : ctx) {
            if (m.getRole() == MsgRole.USER) n++;
        }
        return n;
    }

    private static Msg userMsg(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistantMsg(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static Msg wrapMsg(MsgRole role, String text) {
        return Msg.builderForRole(role).textContent(text).build();
    }

    private AgentStateStore store(Resolved r) {
        return catWorkspace.stateStore(r.workspacePath());
    }

    private AgentState load(String key) {
        return load(resolve(key));
    }

    private AgentState load(Resolved r) {
        try {
            return store(r).get(r.userId(), r.sessionId(), STATE_KEY, AgentState.class).orElse(null);
        } catch (Exception e) {
            log.warn("[ConversationMemory] 读取会话上下文失败（回退空）: {}", e.toString());
            return null;
        }
    }

    private void save(Resolved r, AgentState st) {
        try {
            store(r).save(r.userId(), r.sessionId(), STATE_KEY, st);
        } catch (Exception e) {
            log.warn("[ConversationMemory] 写入会话上下文失败（不阻断）: {}", e.toString());
        }
    }

    /** 把会话 key（{workspace}/{agent}/{session}）拆成官方槽键 (userId={workspace}/{agent}, sessionId)。 */
    private static Resolved resolve(String key) {
        int i = key.lastIndexOf('/');
        if (i < 0) return new Resolved("anon", "anon", key);
        String userId = key.substring(0, i);
        String sessionId = key.substring(i + 1);
        // workspacePath = userId 的第一段（"cat-1/miaomiao" → "cat-1"）
        int j = userId.indexOf('/');
        String workspacePath = j > 0 ? userId.substring(0, j) : userId;
        return new Resolved(workspacePath, userId, sessionId);
    }

    /** 官方状态槽的 workspace / 用户 / 会话标识。 */
    private record Resolved(String workspacePath, String userId, String sessionId) {
    }
}
