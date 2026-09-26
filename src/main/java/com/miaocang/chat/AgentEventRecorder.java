package com.miaocang.chat;

import com.miaocang.service.CatEventService;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 事件埋点器（移植自 Memory-Observatory 的 MemoryReportMiddleware，按喵藏场景简化）。
 *
 * <p>旁路观测：消费 agentscope 事件流中的模型推理（token）与工具调用，组装成与
 * {@code POST /api/v1/events} 完全一致的事件字段直接落库（同进程不再走 HTTP）。
 * 全程 try-catch，任何异常只 WARN，绝不阻塞 / 改变 Agent 主流程。
 *
 * <p>事件归类（与 observatory 归一化口径一致）：
 * 模型调用 → layer=model（含 token 与分摊）；思考/正文块 END → layer=model / text；
 * 工具调用与结果 → layer=skill（结果带 latency 与 status）；Turn 收尾 → layer=session 主事件，
 * metadata 带 turn_message_id / turn_user / turn_outcome / turn_actions。
 */
public final class AgentEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentEventRecorder.class);

    private final CatEventService svc;
    private final String agentId;
    private final String sessionId;
    private final String turnMessageId;
    private final String turnUser;
    private final long startNanos = System.nanoTime();

    private long totalTokens;
    /** Turn 收尾防重入（正常完成 / 异常 / 取消 / 看门狗超时多路只会落库一次）。 */
    private final java.util.concurrent.atomic.AtomicBoolean turnClosed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 已分摊到工具事件的 token 累计（增量 = totalTokens - attributedTokens）。 */
    private long attributedTokens;
    private int actionIdx;
    private String outcome;
    private final List<String> actions = new ArrayList<>();
    /** 流式块累积：blockId → 思考 / 正文文本（DELTA 拼接，END 合成一条上报）。 */
    private final Map<String, StringBuilder> think = new HashMap<>();
    private final Map<String, StringBuilder> text = new HashMap<>();
    private final Map<String, StringBuilder> toolResult = new HashMap<>();
    private final Map<String, Long> toolStartNanos = new HashMap<>();
    /** 待分摊 token 的工具调用事件（TOOL_CALL_START 产生，下一次 MODEL_CALL_END 均摊上报）。 */
    private final List<Map<String, Object>> pendingToolEvents = new ArrayList<>();
    /** 工具调用参数（trace 链路 PRE_ACTING 阶段拿到）：toolCallId → 参数 JSON。 */
    private final Map<String, String> toolArgs = new HashMap<>();

    public AgentEventRecorder(CatEventService svc, String agentId, String sessionId, String turnUser) {
        this.svc = svc;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnMessageId = "turn-" + sessionId + "-" + System.nanoTime();
        this.turnUser = truncate(turnUser, 500);
    }

    /**
     * 主人消息上报（MSG_RECV，trace 中间件 onAgent 阶段调用）：日记里独立成条，
     * 与 Turn metadata 的 turn_user 互补——事件表能直接看到主人每轮问了什么。
     */
    public void reportUserMessage(String text) {
        if (svc == null || text == null || text.isBlank()) return;
        try {
            Map<String, Object> f = base("WRITE", "text");
            f.put("memoryKey", "msg:user");
            f.put("memorySummary", truncate("主人消息 · " + text.strip(), 2000));
            f.put("tokenCount", 0);
            attachAction(f, "主人消息 · " + truncate(text.strip(), 120));
            report(f);
        } catch (Exception e) {
            log.warn("[喵事件] 主人消息上报失败（不阻断）: {}", e.toString());
        }
    }

    /**
     * 挂工具调用参数（trace 中间件 PRE_ACTING 阶段调用）：补齐 TOOL_CALL 事件的 input={...}，
     * 时序上 ToolCallStartEvent（进 pending）先于此调用，按 metadata.tool_call_id 回填。
     */
    public void attachToolArgs(String toolCallId, String argsJson) {
        if (toolCallId == null || argsJson == null || argsJson.isBlank()) return;
        toolArgs.put(toolCallId, truncate(argsJson, 500));
        for (Map<String, Object> f : pendingToolEvents) {
            if (f.get("metadata") instanceof Map<?, ?> mm && toolCallId.equals(((Map<?, ?>) mm).get("tool_call_id"))) {
                @SuppressWarnings("unchecked")
                Map<String, String> meta = (Map<String, String>) f.get("metadata");
                meta.put("tool_args", truncate(argsJson, 500));
                f.put("memorySummary", truncate(f.get("memorySummary") + " · 参数 " + truncate(argsJson, 200), 1000));
            }
        }
    }

    /** 事件流旁路观察：每个 AgentEvent 调一次。 */
    public void observe(AgentEvent ev) {
        if (svc == null) return;
        try {
            if (ev instanceof ModelCallEndEvent m) {
                reportModelCall(m);
            } else if (ev instanceof ThinkingBlockDeltaEvent t) {
                accumulate(think, t.getBlockId(), t.getDelta());
            } else if (ev instanceof ThinkingBlockEndEvent t) {
                reportBlockEnd("model", "thinking:" + agentId, "AI 思考：", t.getBlockId(), think);
            } else if (ev instanceof TextBlockDeltaEvent t) {
                accumulate(text, t.getBlockId(), t.getDelta());
            } else if (ev instanceof TextBlockEndEvent t) {
                reportBlockEnd("text", "text:" + agentId, "内容：", t.getBlockId(), text);
            } else if (ev instanceof ToolCallStartEvent s) {
                reportToolCall(s);
            } else if (ev instanceof ToolResultTextDeltaEvent s) {
                accumulate(toolResult, s.getToolCallId(), s.getDelta());
            } else if (ev instanceof ToolResultEndEvent s) {
                reportToolResultEnd(s);
            } else if (ev instanceof AgentResultEvent r) {
                if (r.getResult() != null) {
                    String txt = r.getResult().getTextContent();
                    if (txt != null && !txt.isBlank()) outcome = txt;
                }
            } else if (ev instanceof AgentStartEvent s) {
                reportLifecycle("agent", "agent:start", "Agent 启动 · "
                        + (s.getName() == null ? agentId : s.getName()));
            } else if (ev instanceof AgentEndEvent) {
                reportLifecycle("agent", "agent:end", "Agent 结束");
            }
        } catch (Exception e) {
            log.warn("[喵事件] 埋点观察失败（不阻断）: {}", e.toString());
        }
    }

    /** Turn 收尾（正常完成 / 异常 / 取消都调，防重入只落库一次）：flush 余量并上报 layer=session 主事件。 */
    public void finish(Throwable err) {
        if (svc == null || !turnClosed.compareAndSet(false, true)) return;
        try {
            flushPendingToolEvents(0);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Map<String, Object> f = base("WRITE", "session");
            f.put("memoryKey", "turn:" + sessionId);
            f.put("latencyMs", elapsedMs);
            f.put("tokenCount", totalTokens);

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("turn_message_id", turnMessageId);
            if (turnUser != null && !turnUser.isBlank()) meta.put("turn_user", turnUser);
            if (outcome != null && !outcome.isBlank()) meta.put("turn_outcome", truncate(outcome, 2000));
            meta.put("action_count", String.valueOf(actionIdx));
            if (!actions.isEmpty()) meta.put("turn_actions", toJsonArray(actions));
            if (err != null) meta.put("error", truncate(String.valueOf(err.getMessage() == null ? err : err.getMessage()), 500));
            f.put("metadata", meta);

            f.put("memorySummary", err != null
                    ? "Turn 异常中断 · " + truncate(String.valueOf(err.getMessage() == null ? err : err.getMessage()), 300)
                    : (outcome != null && !outcome.isBlank() ? truncate(outcome, 2000) : "Turn 结束 · 耗时 " + elapsedMs + "ms"));
            svc.report(f);
        } catch (Exception e) {
            log.warn("[喵事件] Turn 主事件上报失败（不阻断）: {}", e.toString());
        }
    }

    // ==================== 各类事件上报 ====================

    /** 模型推理结束：只累计 token（分摊延迟到工具结果结束时统一 flush，保证参数先 attach）。 */
    private void reportModelCall(ModelCallEndEvent ev) {
        var u = ev.getUsage();
        int total = u != null ? u.getTotalTokens() : 0;
        Map<String, Object> f = base("WRITE", "model");
        f.put("memoryKey", "model:" + agentId);
        String in = u != null ? String.valueOf(u.getInputTokens()) : "0";
        String out = u != null ? String.valueOf(u.getOutputTokens()) : "0";
        String cached = (u != null && u.getCachedTokens() > 0) ? " 缓存in=" + u.getCachedTokens() : "";
        String detail = "LLM 推理 · token=" + total + "（in=" + in + " out=" + out + cached + "）";
        f.put("memorySummary", detail);
        f.put("tokenCount", total);
        f.put("latencyMs", u != null ? (long) (u.getTime() * 1000L) : 0L);
        totalTokens += total;
        attachAction(f, detail);
        report(f);
    }

    /** 工具调用：先进 pending 队列，下一次 MODEL_CALL_END 分摊 token 后上报（layer=skill）。 */
    private void reportToolCall(ToolCallStartEvent ev) {
        String name = ev.getToolCallName() == null ? "tool" : ev.getToolCallName();
        Map<String, Object> f = base("WRITE", "skill");
        f.put("memoryKey", "tool:" + name);
        String detail = "工具调用 · " + name + (ev.getToolCallId() != null ? " · " + ev.getToolCallId() : "");
        f.put("memorySummary", detail);
        attachAction(f, detail);
        if (ev.getToolCallId() != null) {
            toolStartNanos.put(ev.getToolCallId(), System.nanoTime());
            if (f.get("metadata") instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, String> meta = (Map<String, String>) mm;
                meta.put("tool_call_id", ev.getToolCallId());
                /* trace 中间件可能先于事件流拿到参数（PRE_ACTING 提前），有则直接带上 */
                String early = toolArgs.get(ev.getToolCallId());
                if (early != null) meta.put("tool_args", early);
            }
        }
        f.put("timestamp", Instant.now().toString());
        pendingToolEvents.add(f);
    }

    /** 分摊并上报 pending 工具调用事件：tokensDelta 均摊到每条（处理工具结果的推理成本）。 */
    private void flushPendingToolEvents(long tokensDelta) {
        if (pendingToolEvents.isEmpty()) return;
        try {
            long share = tokensDelta > 0 ? tokensDelta / pendingToolEvents.size() : 0;
            for (Map<String, Object> f : pendingToolEvents) {
                f.put("tokenCount", share);
                /* 兜底：PRE_ACTING 链路出问题时，pending 可能没带 tool_args，用 toolArgs map 补一次 */
                if (f.get("metadata") instanceof Map<?, ?> mm && ((Map<?, ?>) mm).get("tool_args") == null
                        && ((Map<?, ?>) mm).get("tool_call_id") != null) {
                    String id = String.valueOf(((Map<?, ?>) mm).get("tool_call_id"));
                    String args = toolArgs.get(id);
                    if (args != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> meta = (Map<String, String>) mm;
                        meta.put("tool_args", args);
                    }
                }
                report(f);
            }
            attributedTokens = totalTokens;
        } catch (Exception e) {
            log.warn("[喵事件] 工具事件分摊上报失败: {}", e.toString());
        } finally {
            pendingToolEvents.clear();
        }
    }

    /** 工具结果结束：先把 pending 的工具调用事件分摊 token 上报（此时参数已由 PRE_ACTING attach），再上报结果。 */
    private void reportToolResultEnd(ToolResultEndEvent ev) {
        String name = ev.getToolCallName() == null ? "tool" : ev.getToolCallName();
        String state = (ev.getState() == null) ? "" : " · " + ev.getState();
        StringBuilder sb = (ev.getToolCallId() == null) ? null : toolResult.remove(ev.getToolCallId());
        String content = truncate(sb == null ? "" : sb.toString(), 2000);
        /* flush 在 attach 之后：PRE_ACTING（onActing 中间件链）必先于本事件，pending 里已带 tool_args */
        flushPendingToolEvents(totalTokens - attributedTokens);
        Map<String, Object> f = base("WRITE", "skill");
        f.put("memoryKey", "tool:" + name + ":result");
        f.put("memorySummary", truncate("工具结果 · " + name + state + (content.isBlank() ? "" : "\n" + content), 3000));
        f.put("tokenCount", 0);
        Long start = (ev.getToolCallId() != null) ? toolStartNanos.remove(ev.getToolCallId()) : null;
        if (start != null) {
            f.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
        }
        attachAction(f, "工具结果 · " + name + state);
        if (ev.getState() != null && f.get("metadata") instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, String> meta = (Map<String, String>) mm;
            meta.put("status", ev.getState() == io.agentscope.core.message.ToolResultState.ERROR ? "failed" : ev.getState().getValue());
            /* 结果条目也带上调用参数（toolArgs 里必已有：PRE_ACTING 早于工具执行） */
            String args = ev.getToolCallId() != null ? toolArgs.get(ev.getToolCallId()) : null;
            if (args != null) meta.put("tool_args", args);
        }
        report(f);
    }

    /** 流式块结束：把累积的 DELTA 合成一条上报（AI 思考 / 正文），避免逐条刷库。 */
    private void reportBlockEnd(String layer, String memoryKey, String prefix, String blockId,
                                Map<String, StringBuilder> buf) {
        StringBuilder sb = (blockId == null) ? null : buf.remove(blockId);
        String content = truncate(sb == null ? "" : sb.toString(), 3000);
        if (content.isBlank()) return;
        Map<String, Object> f = base("WRITE", layer);
        f.put("memoryKey", memoryKey);
        f.put("memorySummary", prefix + content);
        f.put("tokenCount", 0);
        attachAction(f, prefix + truncate(content, 120));
        report(f);
    }

    /** 一次性生命周期事件（Agent 启动/结束）。 */
    private void reportLifecycle(String layer, String memoryKey, String summary) {
        Map<String, Object> f = base("WRITE", layer);
        f.put("memoryKey", memoryKey);
        f.put("memorySummary", summary);
        f.put("tokenCount", 0);
        attachAction(f, summary);
        report(f);
    }

    // ==================== 组装与工具 ====================

    /** action 子事件打 Turn 分组元数据（turn_message_id + action_idx + action_full）。 */
    private void attachAction(Map<String, Object> fields, String actionFull) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("turn_message_id", turnMessageId);
        meta.put("action_idx", String.valueOf(actionIdx));
        if (actionFull != null && !actionFull.isBlank()) meta.put("action_full", truncate(actionFull, 500));
        actions.add(actionFull == null ? "" : actionFull);
        actionIdx++;
        fields.put("metadata", meta);
    }

    /** 事件公共字段（对齐 POST /api/v1/events 协议）。 */
    private Map<String, Object> base(String op, String layer) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("agentId", agentId);
        f.put("sessionId", sessionId);
        f.put("operation", op);
        f.put("layer", layer);
        f.put("timestamp", Instant.now().toString());
        return f;
    }

    private void report(Map<String, Object> f) {
        try {
            svc.report(f);
        } catch (Exception e) {
            log.warn("[喵事件] 事件上报失败（不阻断）: {}", e.toString());
        }
    }

    private static void accumulate(Map<String, StringBuilder> buf, String key, String delta) {
        if (delta == null || delta.isBlank()) return;
        StringBuilder sb = buf.computeIfAbsent(key == null ? "_" : key, k -> new StringBuilder());
        if (sb.length() < 20000) sb.append(delta);
    }

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}
