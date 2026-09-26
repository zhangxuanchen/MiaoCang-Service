package com.miaocang.chat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮 Agent 事件的 SSE 转发器（移植自 Memory-Observatory）。
 *
 * <p>事件映射：TextBlockDelta → {@code token}；ToolCallEnd → {@code tool}（带入参摘要）；
 * ToolResultEnd → {@code toolresult}（带结构保持的结果摘要）。每个实例绑一条 SSE 连接，
 * 自带 callArgs/callResults 累积表，天然并发隔离。
 */
final class SseEventSink implements SingleTurnExecutor.EventSink {

    private final SseEmitter emitter;
    /** 工具呼叫实例集合：流式累积入参/结果，用 toolCallId 隔离。 */
    private final Map<String, String> callArgs = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> callResults = new ConcurrentHashMap<>();

    SseEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onEvent(AgentEvent ev) {
        try {
            if (ev instanceof TextBlockDeltaEvent t) {
                emitter.send(SseEmitter.event().name("token").data(t.getDelta()));
            } else if (ev instanceof ToolCallStartEvent t) {
                callArgs.put(t.getToolCallId(), "");
            } else if (ev instanceof ToolCallDeltaEvent t) {
                callArgs.merge(t.getToolCallId(), t.getDelta(), String::concat);
            } else if (ev instanceof ToolCallEndEvent t) {
                String args = summarize(callArgs.remove(t.getToolCallId()));
                /* 结构化下发：前端解析 name/args 做「指挥动作」（open_file/read_file 入参含 path） */
                emitter.send(SseEmitter.event().name("tool").data(Map.of(
                        "name", t.getToolCallName() == null ? "" : t.getToolCallName(),
                        "args", args == null ? "" : args)));
            } else if (ev instanceof ToolResultStartEvent t) {
                callResults.put(t.getToolCallId(), new StringBuilder());
            } else if (ev instanceof ToolResultTextDeltaEvent t) {
                StringBuilder sb = callResults.get(t.getToolCallId());
                if (sb != null) {
                    sb.append(t.getDelta());
                }
            } else if (ev instanceof ToolResultEndEvent t) {
                StringBuilder sb = callResults.remove(t.getToolCallId());
                String result = summarizePreserve(sb == null ? null : sb.toString());
                if (result != null) {
                    /* 结构化工具结果（write_file/open_doc 等返回的 {"type":…} JSON）原样透传为 JSON，
                       前端据此执行 UI 反馈（刷新文件树/打开详情）；普通文本结果照旧 */
                    emitter.send(SseEmitter.event().name("toolresult").data(structured(result)));
                }
            }
        } catch (Exception e) {
            // SSE 已断开（客户端关页面等）：只标记，不重复 complete
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 已完成的 emitter 再次 complete 会抛异常，忽略
            }
        }
    }

    /** 判断结果是否为协议 JSON（以 { 开头且含 "type" 键）→ 转 Map 走 Jackson 序列化；否则返回原字符串。 */
    private static Object structured(String result) {
        String s = result.strip();
        if (s.startsWith("{") && s.endsWith("}") && s.contains("\"type\"")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
            } catch (Exception ignored) {
                // 非 JSON（模型把 JSON 写进正文等）：按纯文本下发
            }
        }
        return result;
    }

    /** 工具入参摘要：压缩空白、截断到 200 字符，便于气泡里单行展示。 */
    private static String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 工具结果摘要：保留换行与缩进的 Markdown 结构，按行边界截断到 MAX_CHARS。 */
    private static String summarizePreserve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll(" {2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        final int MAX = 2000;
        if (s.length() > MAX) {
            int cut = s.indexOf('\n', MAX);
            if (cut < 0) {
                cut = MAX;
            }
            cut = Math.min(cut, s.length());
            // 截断点若落在未闭合代码围栏块内，推进到闭合处，避免代码块残缺
            if ((countFencesTo(s, cut) & 1) == 1) {
                int close = s.indexOf("```", cut);
                if (close >= 0) {
                    int eol = s.indexOf('\n', close + 3);
                    cut = Math.min(s.length(), eol < 0 ? close + 3 : eol + 1);
                }
            }
            s = s.substring(0, cut) + "\n…（结果较长已截断）";
        }
        return s;
    }

    /** 统计 s[0,end) 内代码围栏 ``` 的出现次数（偶数=不在块内，奇数=块内）。 */
    private static int countFencesTo(String s, int end) {
        int n = 0, i = 0;
        while (i < end) {
            int j = s.indexOf("```", i);
            if (j < 0 || j >= end) {
                break;
            }
            n++;
            i = j + 3;
        }
        return n;
    }
}
