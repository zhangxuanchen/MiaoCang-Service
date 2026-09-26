package com.miaocang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.CatEvent;
import com.miaocang.repository.CatEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 喵事件服务（移植自 Memory-Observatory 的 WriteController + EventRepository 聚合逻辑）。
 *
 * <p>两个职责：
 * <ol>
 *   <li>接收：{@link #report(Map)} 按 {@code POST /api/v1/events} 原协议字段（camelCase）收一条事件，
 *       补齐缺省值后落库；Agent 埋点（AgentEventRecorder）与外部上报共用。</li>
 *   <li>查询：事件列表 / 事件详情 / Turn 日记流 / Token 统计与多维分析 / 问题分析聚合。</li>
 * </ol>
 * 查询用 H2 原生 SQL（turn_message_id 等冗余列已抽成普通列，聚合无需 JSON 函数）。
 */
@Service
public class CatEventService {

    private static final Logger log = LoggerFactory.getLogger(CatEventService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 显式列清单：SELECT * 依赖物理列序（ddl-auto 追加列会乱序），原生查询统一按列名取。 */
    private static final String EVENT_COLS = "event_id, agent_id, session_id, operation, layer, memory_key, memory_summary, "
            + "token_count, latency_ms, ts, metadata, trace_id, parent_span_id, turn_message_id, action_idx, status";

    @PersistenceContext
    private EntityManager em;

    private final CatEventRepository repo;

    // ===== 问题分析 · 阈值（application.yml: mo.analytics.problems.*，与 Memory-Observatory 一致）=====
    @Value("${mo.analytics.problems.tool-token:5000}")       private long probToolToken;
    @Value("${mo.analytics.problems.turn-token:5000}")       private long probTurnToken;
    @Value("${mo.analytics.problems.turn-count:20}")         private long probTurnCount;
    @Value("${mo.analytics.problems.turn-tool-count:8}")     private long probTurnToolCount;
    @Value("${mo.analytics.problems.turn-latency-ms:60000}") private long probTurnLatencyMs;
    @Value("${mo.analytics.problems.model-slow-ms:5000}")    private long probModelSlowMs;
    @Value("${mo.analytics.problems.session-token:100000}")  private long probSessionToken;
    @Value("${mo.analytics.problems.memory-key-writes:10}")  private long probKeyWrites;
    @Value("${mo.analytics.problems.skill-slow-ms:30000}")   private long probSkillSlowMs;
    @Value("${mo.analytics.problems.skill-repeat-count:5}")  private long probSkillRepeat;
    @Value("${mo.analytics.problems.skill-error-count:0}")   private long probSkillErrorMin;

    public CatEventService(CatEventRepository repo) {
        this.repo = repo;
    }

    // ==================== 接收（原协议：POST /api/v1/events 的 CreateEventDTO 字段） ====================

    /**
     * 上报一条事件。字段与 Memory-Observatory CreateEventDTO 完全对齐：
     * eventId/agentId/sessionId/operation/layer/memoryKey/memorySummary/tokenCount/latencyMs/
     * timestamp(ISO-8601)/traceId/parentSpanId/metadata(Map)。缺省值补齐规则一致。
     */
    public CatEvent report(Map<String, Object> f) {
        String agentId = str(f.get("agentId"));
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        String sessionId = orDefault(str(f.get("sessionId")), "default-session");
        Map<String, String> meta = new LinkedHashMap<>();
        Object rawMeta = f.get("metadata");
        if (rawMeta instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                meta.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        CatEvent e = new CatEvent();
        e.setEventId(orDefault(str(f.get("eventId")), uuid(16)));
        e.setAgentId(agentId);
        e.setSessionId(sessionId);
        e.setOperation(orDefault(normalizeOp(str(f.get("operation"))), "WRITE"));
        e.setLayer(orDefault(str(f.get("layer")), "provider"));
        e.setMemoryKey(str(f.get("memoryKey")));
        e.setMemorySummary(truncate(str(f.get("memorySummary")), 6000));
        e.setTokenCount((int) doubleOf(f.get("tokenCount"), 0));
        e.setLatencyMs(doubleOf(f.get("latencyMs"), 0));
        e.setTs(parseTs(str(f.get("timestamp"))));
        e.setTraceId(orDefault(str(f.get("traceId")), uuid(32)));
        e.setParentSpanId(str(f.get("parentSpanId")));
        // 冗余列：从 metadata 抽出，Turn 聚合 / 问题检测直接用普通列查
        e.setTurnMessageId(meta.get("turn_message_id"));
        e.setActionIdx(meta.get("action_idx") == null ? null : (int) doubleOf(meta.get("action_idx"), 0));
        e.setStatus(meta.get("status"));
        try {
            e.setMetadata(MAPPER.writeValueAsString(meta));
        } catch (Exception ex) {
            e.setMetadata("{}");
        }
        return repo.save(e);
    }

    /** 操作归一化（对齐 MemoryOp.of）：read/add→READ/WRITE，delete/expire→EXPIRE，update→UPDATE。 */
    private static String normalizeOp(String op) {
        if (op == null || op.isBlank()) return null;
        return switch (op.trim().toLowerCase()) {
            case "read", "retrieve", "get", "search" -> "READ";
            case "update", "modify", "patch" -> "UPDATE";
            case "delete", "expire", "remove", "drop" -> "EXPIRE";
            default -> "WRITE";
        };
    }

    // ==================== Agent 列表（带猫信息映射） ====================

    /** agentId = 工作区路径（cat-{id}），附带猫名/图标/毛色（cat-0 = Wiki管理员）。 */
    public List<Map<String, Object>> agents() {
        List<?> rows = em.createNativeQuery(
                "SELECT agent_id, COUNT(*) AS events, MAX(ts) AS last_active FROM cat_events GROUP BY agent_id ORDER BY last_active DESC")
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            String agentId = String.valueOf(row[0]);
            m.put("agentId", agentId);
            m.put("events", ((Number) row[1]).longValue());
            m.put("lastActive", tsToStr(row[2]));
            appendCatInfo(m, agentId);
            out.add(m);
        }
        return out;
    }

    /**
     * 从 agentId（工作区相对路径）解析猫信息挂到 map（不存在的猫跳过）。
     * 路径格式：{@code {username}/cat-{id}} = 某只猫；{@code {username}}（无 /cat-N 后缀）= 该用户的 Wiki 管理员（书库根）。
     */
    private void appendCatInfo(Map<String, Object> m, String agentId) {
        try {
            String last = agentId.contains("/")
                    ? agentId.substring(agentId.lastIndexOf('/') + 1)
                    : agentId;
            if (last.matches("cat-\\d+") && !last.equals("cat-0")) {
                long catId = Long.parseLong(last.substring(4));
                // Cat 实体无 @Table 注解，默认表名为 cat（不是 cats）
                List<?> cats = em.createNativeQuery("SELECT name, icon, color FROM cat WHERE id = ?")
                        .setParameter(1, catId).getResultList();
                if (!cats.isEmpty()) {
                    Object[] c = (Object[]) cats.get(0);
                    m.put("catName", String.valueOf(c[0]));
                    m.put("catIcon", c[1] == null ? "🐱" : String.valueOf(c[1]));
                    m.put("catColor", c[2] == null ? "#888" : String.valueOf(c[2]));
                }
            } else {
                // 工作区根（{username} 或显式 cat-0）= Wiki 管理员
                m.put("catName", "Wiki管理员");
                m.put("catIcon", "🛡");
                m.put("catColor", "#0F6E56");
            }
        } catch (Exception ignore) {
            // 猫信息只是展示增强，失败不影响 agentId 列表
        }
    }

    // ==================== 事件列表 / 统计 / 详情 ====================

    public Map<String, Object> events(String agentId, String sessionId, String eventId, String op,
                                      String layer, String keyword, Instant from, Instant to,
                                      int limit, int offset) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        appendFilters(w, params, agentId, sessionId, eventId, op, layer, keyword, from, to);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        int safeOffset = Math.max(offset, 0);
        List<?> rows = query("SELECT " + EVENT_COLS + " FROM cat_events" + w + " ORDER BY ts DESC LIMIT :limit OFFSET :offset", params)
                .setParameter("limit", safeLimit).setParameter("offset", safeOffset)
                .getResultList();
        long total = countBy(w, params);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object r : rows) events.add(toEventMap((Object[]) r));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("total", total);
        result.put("limit", safeLimit);
        result.put("offset", safeOffset);
        result.put("events", events);
        return result;
    }

    /** 筛选范围统计：事件总数 / 活跃会话数 / Token 合计。 */
    public Map<String, Object> eventStats(String agentId, String sessionId, String eventId, String op,
                                          String layer, String keyword, Instant from, Instant to) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        appendFilters(w, params, agentId, sessionId, eventId, op, layer, keyword, from, to);
        List<?> rows = query(
                "SELECT COUNT(*) AS total, COUNT(DISTINCT session_id) AS sessions, "
                        + "COALESCE(SUM(token_count),0) AS tokens FROM cat_events" + w, params)
                .getResultList();
        Object[] r = (Object[]) rows.get(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", ((Number) r[0]).longValue());
        stats.put("sessions", ((Number) r[1]).longValue());
        stats.put("tokens", ((Number) r[2]).longValue());
        return stats;
    }

    /** 单事件详情（metadata 已解析为 map）。 */
    public Map<String, Object> eventDetail(String eventId) {
        List<?> rows = query("SELECT " + EVENT_COLS + " FROM cat_events WHERE event_id = ?", null)
                .setParameter(1, eventId).getResultList();
        if (rows.isEmpty()) return null;
        Map<String, Object> event = toEventMap((Object[]) rows.get(0));
        // 同 turn_message_id 的 action 子事件（含主事件本身，前端过滤）
        String turnId = (String) event.get("turnMessageId");
        List<Map<String, Object>> actions = new ArrayList<>();
        if (turnId != null && !turnId.isBlank()) {
            actions = turnActions(String.valueOf(event.get("agentId")), turnId);
        }
        event.put("actions", actions);
        return event;
    }

    /** 某 Turn 的全部 action 子事件（action_idx 升序，排除主事件）。 */
    public List<Map<String, Object>> turnActions(String agentId, String turnMessageId) {
        List<?> rows = query(
                        "SELECT " + EVENT_COLS + " FROM cat_events WHERE agent_id = ? AND turn_message_id = ? AND action_idx IS NOT NULL "
                                + "ORDER BY action_idx ASC", null)
                .setParameter(1, agentId).setParameter(2, turnMessageId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) out.add(toEventMap((Object[]) r));
        return out;
    }

    // ==================== Turn 日记流（按轮聚合的主事件卡片） ====================

    /** Turn 列表：主事件（layer=session, action_idx null）倒序 + 全局统计。q 模糊匹配 turn_user / 摘要。 */
    public Map<String, Object> turns(String agentId, String sessionId, String q, int limit, int offset) {
        StringBuilder w = new StringBuilder(
                " WHERE agent_id = :agent AND turn_message_id IS NOT NULL AND action_idx IS NULL AND layer = 'session'");
        if (sessionId != null && !sessionId.isBlank()) {
            w.append(" AND session_id = :session");
        }
        if (q != null && !q.isBlank()) {
            w.append(" AND (memory_summary LIKE :q OR metadata LIKE :q)");
        }
        jakarta.persistence.Query list = query(
                        "SELECT " + EVENT_COLS + " FROM cat_events" + w + " ORDER BY ts DESC LIMIT :limit OFFSET :offset", null)
                .setParameter("agent", agentId)
                .setParameter("limit", Math.min(Math.max(limit, 1), 200))
                .setParameter("offset", Math.max(offset, 0));
        jakarta.persistence.Query count = em.createNativeQuery("SELECT COUNT(*) FROM cat_events" + w)
                .setParameter("agent", agentId);
        jakarta.persistence.Query sum = em.createNativeQuery(
                        "SELECT COALESCE(SUM(token_count),0) FROM cat_events" + w)
                .setParameter("agent", agentId);
        if (sessionId != null && !sessionId.isBlank()) {
            list.setParameter("session", sessionId);
            count.setParameter("session", sessionId);
            sum.setParameter("session", sessionId);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            list.setParameter("q", like);
            count.setParameter("q", like);
            sum.setParameter("q", like);
        }
        List<Map<String, Object>> turnList = new ArrayList<>();
        for (Object r : list.getResultList()) {
            Map<String, Object> m = toEventMap((Object[]) r);
            m.put("actions", turnActions(agentId, String.valueOf(m.get("turnMessageId"))));
            turnList.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("sessionId", sessionId == null ? "" : sessionId);
        result.put("turns", turnList);
        result.put("total", ((Number) count.getSingleResult()).longValue());
        result.put("totalTokens", ((Number) sum.getSingleResult()).longValue());
        return result;
    }

    /** Turn 级指标（问题分析用）：按 turn_message_id 聚合 token/事件数/工具数/总耗时。 */
    private List<Map<String, Object>> turnMetrics(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(
                " WHERE turn_message_id IS NOT NULL AND layer <> 'control'");
        Map<String, Object> params = new HashMap<>();
        appendRange(w, params, from, to);
        if (agent != null && !agent.isBlank()) {
            w.append(" AND agent_id = :agent");
            params.put("agent", agent);
        }
        List<?> rows = query(
                        "SELECT MIN(agent_id) AS agent_id, MIN(session_id) AS session_id, turn_message_id, "
                                + "SUM(token_count) AS tokens, COUNT(*) AS events, "
                                + "SUM(CASE WHEN memory_key LIKE 'tool:%' THEN 1 ELSE 0 END) AS tool_events, "
                                + "MAX(latency_ms) AS total_ms, MAX(ts) AS last_ts "
                                + "FROM cat_events" + w + " GROUP BY turn_message_id", params)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("turnId", String.valueOf(row[2]));
            m.put("tokens", ((Number) row[3]).longValue());
            m.put("events", ((Number) row[4]).longValue());
            m.put("toolEvents", row[5] == null ? 0L : ((Number) row[5]).longValue());
            m.put("totalMs", row[6] == null ? 0L : ((Number) row[6]).longValue());
            m.put("lastTs", tsToStr(row[7]));
            out.add(m);
        }
        return out;
    }

    // ==================== Token 统计（喵喵消耗） ====================

    /** 分层 / 分操作 / 每日趋势。 */
    public Map<String, Object> tokenStats(String agentId, Instant from, Instant to) {
        StringBuilder w = new StringBuilder(" WHERE agent_id = :agent");
        Map<String, Object> params = new HashMap<>();
        params.put("agent", agentId);
        appendRange(w, params, from, to);

        Map<String, Long> byLayer = groupSum("layer", w.toString(), params);
        Map<String, Long> byOp = groupSum("operation", w.toString(), params);

        // 趋势桶：范围 <= 3 天按小时，否则按天
        boolean hourly = from != null && to != null && (to.toEpochMilli() - from.toEpochMilli()) <= 3 * 86400_000L;
        String fmt = hourly ? "MM-dd HH:00" : "MM-dd";
        List<?> rows = query(
                        "SELECT FORMATDATETIME(ts, '" + fmt + "') AS bucket, SUM(token_count) FROM cat_events" + w
                                + " GROUP BY bucket ORDER BY MIN(ts)", params)
                .getResultList();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", String.valueOf(row[0]));
            m.put("tokens", ((Number) row[1]).longValue());
            trend.add(m);
        }

        long total = byLayer.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("totalTokens", total);
        result.put("byLayer", byLayer);
        result.put("byOp", byOp);
        result.put("trend", trend);
        return result;
    }

    private Map<String, Long> groupSum(String col, String where, Map<String, Object> params) {
        List<?> rows = query(
                        "SELECT " + col + ", SUM(token_count) FROM cat_events" + where + " GROUP BY " + col, params)
                .getResultList();
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            out.put(row[0] == null ? "unknown" : String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return out;
    }

    /** Token 多维分析：Top 会话 / 小时分布 / Top key / 延迟统计 / 异常 key / 每日趋势 / 燃烧速率。 */
    public Map<String, Object> tokenAnalytics(String agentId, Instant from, Instant to) {
        StringBuilder w = new StringBuilder(" WHERE agent_id = :agent");
        Map<String, Object> params = new HashMap<>();
        params.put("agent", agentId);
        appendRange(w, params, from, to);
        String where = w.toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);

        // Top 会话（token 求和）：返回全部（limit=-1 表示不截断），前端按 session 级别画柱形图
        result.put("bySessionTop", groupTop(where, params,
                "session_id", "SUM(token_count)", -1, "tokens"));
        // 小时分布（0-23）
        List<?> hourRows = query(
                        "SELECT HOUR(ts), SUM(token_count) FROM cat_events" + where + " GROUP BY HOUR(ts) ORDER BY HOUR(ts)", params)
                .getResultList();
        List<Map<String, Object>> byHour = new ArrayList<>();
        for (Object r : hourRows) {
            Object[] row = (Object[]) r;
            byHour.add(Map.of("hour", ((Number) row[0]).intValue(), "tokens", ((Number) row[1]).longValue()));
        }
        result.put("byHour", byHour);
        // Top memory_key（排除 turn 主事件与 model/text 的整体块）
        result.put("topKeys", groupTop(where + " AND layer NOT IN ('session','text')", params,
                "memory_key", "SUM(token_count)", 10, "tokens"));
        // 延迟统计：layer=model 与 tool:result 的平均耗时 Top5
        result.put("latencyStats", groupTop(where + " AND memory_key LIKE 'tool:%' AND RIGHT(memory_key, 7) = ':result' AND latency_ms > 0",
                params, "memory_key", "AVG(latency_ms)", 5, "avgMs"));
        // 每日趋势
        List<?> dayRows = query(
                        "SELECT FORMATDATETIME(ts, 'yyyy-MM-dd'), SUM(token_count) FROM cat_events" + where
                                + " GROUP BY FORMATDATETIME(ts, 'yyyy-MM-dd') ORDER BY FORMATDATETIME(ts, 'yyyy-MM-dd')", params)
                .getResultList();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object r : dayRows) {
            Object[] row = (Object[]) r;
            daily.add(Map.of("day", String.valueOf(row[0]), "tokens", ((Number) row[1]).longValue()));
        }
        result.put("dailyTrend", daily);
        // 燃烧速率：最近 1h token
        List<?> burn = query(
                        "SELECT COALESCE(SUM(token_count),0) FROM cat_events" + where
                                + " AND ts >= :since", params)
                .setParameter("since", LocalDateTime.now(ZONE).minusHours(1))
                .getResultList();
        result.put("burnRate", Map.of("tokensLastHour", ((Number) burn.get(0)).longValue()));
        // memory_key 异常：max 超过 avg*4 且 max>2000 视为膨胀信号
        result.put("keyAbnormal", keyAbnormal(where, params));
        return result;
    }

    /** key 膨胀检测：对每个 memory_key 计算 max/avg/maxAt，超历史均值 4 倍（且 >2000）即异常。 */
    private List<Map<String, Object>> keyAbnormal(String where, Map<String, Object> params) {
        List<?> rows = query(
                        "SELECT memory_key, MAX(token_count), AVG(token_count), MAX(ts), COUNT(*) FROM cat_events"
                                + where + " AND memory_key IS NOT NULL AND layer NOT IN ('session','text')"
                                + " GROUP BY memory_key ORDER BY MAX(token_count) DESC", params)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            long max = ((Number) row[1]).longValue();
            double avg = row[2] == null ? 0 : ((Number) row[2]).doubleValue();
            if (max > 2000 && max > avg * 4) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("memoryKey", String.valueOf(row[0]));
                m.put("maxTokens", max);
                m.put("avgTokens", Math.round(avg));
                m.put("count", ((Number) row[4]).longValue());
                m.put("lastTs", tsToStr(row[3]));
                m.put("abnormal", true);
                out.add(m);
            }
        }
        return out;
    }

    // ==================== 问题分析（阈值聚合，键与 Memory-Observatory 一致） ====================

    public Map<String, Object> problems(int days, String agent) {
        Instant now = Instant.now();
        Instant from = days > 0 ? now.minusSeconds(days * 86400L) : null;
        Instant to = now;
        String agentCond = (agent == null || agent.isBlank()) ? null : agent;

        List<Map<String, Object>> turns = turnMetrics(from, to, agentCond);

        List<Map<String, Object>> problems = new ArrayList<>();
        // —— Turn 维度 ——
        List<Map<String, Object>> turnTokenHits = new ArrayList<>();
        List<Map<String, Object>> turnCountHits = new ArrayList<>();
        List<Map<String, Object>> turnToolHits = new ArrayList<>();
        List<Map<String, Object>> turnLatencyHits = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            long tokens = (long) t.get("tokens");
            long events = (long) t.get("events");
            long tools = (long) t.get("toolEvents");
            long ms = (long) t.get("totalMs");
            if (tokens > probTurnToken) turnTokenHits.add(turnHit(t, "tokens", tokens));
            if (events > probTurnCount) turnCountHits.add(turnHit(t, "events", events));
            if (tools > probTurnToolCount) turnToolHits.add(turnHit(t, "toolEvents", tools));
            if (ms > probTurnLatencyMs) turnLatencyHits.add(turnHit(t, "totalMs", ms));
        }
        problems.add(problem("turn-token", "单 turn 消耗 token 过多", "> " + probTurnToken + " tokens", turnTokenHits));
        problems.add(problem("turn-count", "单 turn 执行事件过多", "> " + probTurnCount + " 次", turnCountHits));
        problems.add(problem("turn-tool", "单 turn 执行工具过多", "> " + probTurnToolCount + " 次", turnToolHits));
        problems.add(problem("turn-latency", "单 turn 整体延迟过高", "> " + probTurnLatencyMs + " ms", turnLatencyHits));
        // —— 工具 / 技能维度 ——
        problems.add(problem("skill-slow", "工具执行耗时离群", "> " + probSkillSlowMs + " ms",
                querySkillSlow(from, to, agentCond)));
        problems.add(problem("skill-repeat", "工具被反复调用（单 turn 循环）",
                "单 turn 同工具 > " + probSkillRepeat + " 次", querySkillRepeat(from, to, agentCond)));
        problems.add(problem("skill-error", "工具执行报错", "status = failed 且次数 > " + probSkillErrorMin,
                querySkillErrors(from, to, agentCond)));
        problems.add(problem("tool-token", "工具消耗 token 过大", "> " + probToolToken + " tokens",
                queryToolToken(from, to, agentCond)));
        // —— 模型 / 会话维度 ——
        problems.add(problem("model-slow", "模型推理延迟过高", "model 层平均耗时 > " + probModelSlowMs + " ms",
                queryModelSlow(from, to, agentCond)));
        problems.add(problem("session-token", "单会话累计膨胀", "会话内 token 合计 > " + probSessionToken + " tokens",
                querySessionToken(from, to, agentCond)));
        problems.add(problem("mem-churn", "记忆写入抖动（同一 key 反复写）", "同 key 写次数 > " + probKeyWrites + " 次",
                queryMemChurn(from, to, agentCond)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("agent", agentCond);
        result.put("windowFrom", from == null ? null : from.toString());
        result.put("windowTo", to.toString());
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("skillSlowMs", probSkillSlowMs);
        thresholds.put("skillRepeatCount", probSkillRepeat);
        thresholds.put("skillErrorCount", probSkillErrorMin);
        thresholds.put("toolToken", probToolToken);
        thresholds.put("turnToken", probTurnToken);
        thresholds.put("turnCount", probTurnCount);
        thresholds.put("turnToolCount", probTurnToolCount);
        thresholds.put("turnLatencyMs", probTurnLatencyMs);
        thresholds.put("modelSlowMs", probModelSlowMs);
        thresholds.put("sessionToken", probSessionToken);
        thresholds.put("memoryKeyWrites", probKeyWrites);
        result.put("thresholds", thresholds);
        result.put("problems", problems);
        return result;
    }

    private Map<String, Object> problem(String key, String title, String threshold, List<Map<String, Object>> hits) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("title", title);
        p.put("threshold", threshold);
        p.put("hitCount", hits.size());
        p.put("hits", hits);
        return p;
    }

    /** Turn 命中行：公共字段 + 主指标值（turn_user 由主事件 metadata 回填）。 */
    private Map<String, Object> turnHit(Map<String, Object> t, String valueKey, long value) {
        Map<String, Object> h = new LinkedHashMap<>();
        for (String k : new String[]{"agentId", "sessionId", "turnId", "tokens", "events", "toolEvents", "totalMs", "lastTs"}) {
            h.put(k, t.get(k));
        }
        h.put("turnUser", turnUserOf(String.valueOf(t.get("agentId")), String.valueOf(t.get("turnId"))));
        h.put("metric", valueKey);
        h.put("metricValue", value);
        return h;
    }

    /** 取 Turn 主事件的 turn_user（问题卡片展示「主人问了什么」）。 */
    private String turnUserOf(String agentId, String turnId) {
        try {
            List<?> rows = em.createNativeQuery(
                            "SELECT metadata FROM cat_events WHERE agent_id = ? AND turn_message_id = ? AND action_idx IS NULL AND layer = 'session'")
                    .setParameter(1, agentId).setParameter(2, turnId).setMaxResults(1).getResultList();
            if (rows.isEmpty()) return "";
            Map<String, String> meta = parseMeta(String.valueOf(rows.get(0)));
            return meta.getOrDefault("turn_user", "");
        } catch (Exception e) {
            return "";
        }
    }

    private List<Map<String, Object>> querySkillSlow(Instant from, Instant to, String agent) {
        // 注意：LIKE 模式里不能写 '%:result'（Hibernate 会把 :result 当命名参数），用 RIGHT() 等价改写
        StringBuilder w = new StringBuilder(" WHERE memory_key LIKE 'tool:%' AND RIGHT(memory_key, 7) = ':result' AND latency_ms > :th");
        Map<String, Object> params = new HashMap<>();
        params.put("th", probSkillSlowMs);
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT MIN(agent_id), memory_key, COUNT(*), MAX(latency_ms), AVG(latency_ms), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, memory_key ORDER BY MAX(latency_ms) DESC", params)
                .getResultList();
        return toRows(rows, new String[]{"agentId", "tool", "count", "maxMs", "avgMs", "lastTs"}, true);
    }

    private List<Map<String, Object>> querySkillRepeat(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(" WHERE memory_key LIKE 'tool:%' AND RIGHT(memory_key, 7) <> ':result'");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT MIN(agent_id), MIN(session_id), turn_message_id, memory_key, COUNT(*), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, turn_message_id, memory_key HAVING COUNT(*) > :n", params)
                .setParameter("n", probSkillRepeat)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("turnId", String.valueOf(row[2]));
            m.put("tool", String.valueOf(row[3]));
            m.put("count", ((Number) row[4]).longValue());
            m.put("lastTs", tsToStr(row[5]));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> querySkillErrors(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(" WHERE status = 'failed'");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT MIN(agent_id), MIN(session_id), memory_key, COUNT(*), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, session_id, memory_key ORDER BY MAX(ts) DESC", params)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("memoryKey", String.valueOf(row[2]));
            m.put("count", ((Number) row[3]).longValue());
            m.put("lastTs", tsToStr(row[4]));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> queryToolToken(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(" WHERE memory_key LIKE 'tool:%' AND RIGHT(memory_key, 7) <> ':result'");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT MIN(agent_id), MIN(session_id), turn_message_id, SUM(token_count), COUNT(*), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, turn_message_id HAVING SUM(token_count) > :th ORDER BY SUM(token_count) DESC", params)
                .setParameter("th", probToolToken)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("turnId", String.valueOf(row[2]));
            m.put("tokens", ((Number) row[3]).longValue());
            m.put("toolEvents", ((Number) row[4]).longValue());
            m.put("lastTs", tsToStr(row[5]));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> queryModelSlow(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(" WHERE layer = 'model' AND latency_ms > 0");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT agent_id, session_id, COUNT(*), AVG(latency_ms), MAX(latency_ms), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, session_id HAVING AVG(latency_ms) > :th ORDER BY AVG(latency_ms) DESC", params)
                .setParameter("th", probModelSlowMs)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("calls", ((Number) row[2]).longValue());
            m.put("avgMs", Math.round(((Number) row[3]).doubleValue()));
            m.put("maxMs", Math.round(((Number) row[4]).doubleValue()));
            m.put("lastTs", tsToStr(row[5]));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> querySessionToken(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT agent_id, session_id, SUM(token_count), COUNT(*), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, session_id HAVING SUM(token_count) > :th ORDER BY SUM(token_count) DESC", params)
                .setParameter("th", probSessionToken)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("tokens", ((Number) row[2]).longValue());
            m.put("events", ((Number) row[3]).longValue());
            m.put("lastTs", tsToStr(row[4]));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> queryMemChurn(Instant from, Instant to, String agent) {
        StringBuilder w = new StringBuilder(
                " WHERE operation = 'WRITE' AND layer NOT IN ('model','text','session') AND memory_key IS NOT NULL AND memory_key NOT LIKE 'tool:%'");
        Map<String, Object> params = new HashMap<>();
        commonGroup(w, params, from, to, agent);
        List<?> rows = query(
                        "SELECT agent_id, session_id, memory_key, COUNT(*), MAX(ts) FROM cat_events" + w
                                + " GROUP BY agent_id, session_id, memory_key HAVING COUNT(*) > :n ORDER BY COUNT(*) DESC", params)
                .setParameter("n", probKeyWrites)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", String.valueOf(row[0]));
            m.put("sessionId", String.valueOf(row[1]));
            m.put("memoryKey", String.valueOf(row[2]));
            m.put("count", ((Number) row[3]).longValue());
            m.put("lastTs", tsToStr(row[4]));
            out.add(m);
        }
        return out;
    }

    /** 问题查询公共条件：时间范围 + agent。 */
    private void commonGroup(StringBuilder w, Map<String, Object> params, Instant from, Instant to, String agent) {
        appendRange(w, params, from, to);
        if (agent != null && !agent.isBlank()) {
            w.append(" AND agent_id = :agent");
            params.put("agent", agent);
        }
    }

    private List<Map<String, Object>> toRows(List<?> rows, String[] keys, boolean roundAvg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) {
                Object v = row[i];
                if ("avgMs".equals(keys[i]) && v instanceof Number n) {
                    m.put(keys[i], Math.round(n.doubleValue()));
                } else if ("lastTs".equals(keys[i])) {
                    m.put(keys[i], tsToStr(v));
                } else {
                    m.put(keys[i], v == null ? 0 : (v instanceof Number n ? n : String.valueOf(v)));
                }
            }
            if (roundAvg) { /* avg 已在上方处理 */ }
            out.add(m);
        }
        return out;
    }

    // ==================== 通用工具 ====================

    /** 创建原生 SQL 查询并绑定命名参数（动态 where 的参数统一走 params map）。 */
    private jakarta.persistence.Query query(String sql, Map<String, Object> params) {
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        if (params != null) params.forEach(q::setParameter);
        return q;
    }

    private void appendFilters(StringBuilder w, Map<String, Object> params, String agentId, String sessionId,
                               String eventId, String op, String layer, String keyword, Instant from, Instant to) {
        if (agentId != null && !agentId.isBlank()) {
            w.append(" AND agent_id = :agent");
            params.put("agent", agentId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            w.append(" AND session_id = :session");
            params.put("session", sessionId);
        }
        if (eventId != null && !eventId.isBlank()) {
            w.append(" AND event_id = :eventId");
            params.put("eventId", eventId);
        }
        if (op != null && !op.isBlank()) {
            w.append(" AND operation = :op");
            params.put("op", op);
        }
        if (layer != null && !layer.isBlank()) {
            w.append(" AND layer = :layer");
            params.put("layer", layer);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.append(" AND (memory_summary LIKE :kw OR memory_key LIKE :kw)");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        appendRange(w, params, from, to);
    }

    private void appendRange(StringBuilder w, Map<String, Object> params, Instant from, Instant to) {
        if (from != null) {
            w.append(" AND ts >= :from");
            params.put("from", LocalDateTime.ofInstant(from, ZONE));
        }
        if (to != null) {
            w.append(" AND ts <= :to");
            params.put("to", LocalDateTime.ofInstant(to, ZONE));
        }
    }

    private long countBy(StringBuilder w, Map<String, Object> params) {
        jakarta.persistence.Query q = em.createNativeQuery("SELECT COUNT(*) FROM cat_events" + w);
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    /** cat_events 行 → 前端事件 map（camelCase + metadata 解析为 map）。 */
    private Map<String, Object> toEventMap(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", row[0]);
        m.put("agentId", row[1]);
        m.put("sessionId", row[2]);
        m.put("operation", row[3]);
        m.put("layer", row[4]);
        m.put("memoryKey", row[5]);
        m.put("memorySummary", row[6]);
        m.put("tokenCount", row[7] == null ? 0 : ((Number) row[7]).intValue());
        m.put("latencyMs", row[8] == null ? 0 : ((Number) row[8]).doubleValue());
        m.put("timestamp", tsToStr(row[9]));
        Map<String, String> meta = parseMeta(row[10] == null ? null : String.valueOf(row[10]));
        m.put("metadata", meta);
        m.put("traceId", row[11]);
        m.put("parentSpanId", row[12]);
        m.put("turnMessageId", row[13]);
        m.put("actionIdx", row[14]);
        m.put("status", row[15]);
        return m;
    }

    private Map<String, String> parseMeta(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<?, ?> parsed = MAPPER.readValue(json, Map.class);
            for (Map.Entry<?, ?> e : parsed.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        } catch (Exception ignore) {
            // metadata 解析失败按空处理
        }
        return out;
    }

    private List<Map<String, Object>> groupTop(String where, Map<String, Object> params,
                                               String col, String agg, int limit, String valueKey) {
        String sql = "SELECT " + col + ", " + agg + " FROM cat_events" + where
                + (col.contains("session") ? " AND " + col + " IS NOT NULL" : "")
                + " GROUP BY " + col + " ORDER BY " + agg + " DESC";
        if (limit > 0) sql += " LIMIT " + limit;
        List<?> rows = query(sql, params).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Object[] row = (Object[]) r;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(col.equals("session_id") ? "sessionId" : col.equals("memory_key") ? "memoryKey" : col,
                    row[0] == null ? "" : String.valueOf(row[0]));
            Object v = row[1];
            m.put(valueKey, v instanceof Number n && (valueKey.equals("avgMs"))
                    ? Math.round(n.doubleValue()) : ((Number) (v == null ? 0 : v)).longValue());
            out.add(m);
        }
        return out;
    }

    private static String tsToStr(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString().replace('T', ' ').substring(0, 19);
        if (v instanceof LocalDateTime t) return t.toString().replace('T', ' ').substring(0, 19);
        return String.valueOf(v);
    }

    private static LocalDateTime parseTs(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now(ZONE);
        try {
            return LocalDateTime.ofInstant(Instant.parse(s.trim()), ZONE);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.trim().replace(' ', 'T'));
            } catch (Exception e2) {
                return LocalDateTime.now(ZONE);
            }
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static double doubleOf(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String uuid(int len) {
        String u = java.util.UUID.randomUUID().toString().replace("-", "");
        return len >= u.length() ? u : u.substring(0, len);
    }
}
