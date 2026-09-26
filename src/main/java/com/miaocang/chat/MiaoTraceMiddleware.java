package com.miaocang.chat;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.util.JsonUtils;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

/**
 * 喵日记 Trace 中间件（AgentScope 2.0.0 版的「AgentTraceHook」）。
 *
 * <p>文章里的 {@code io.agentscope.harness.hook.AgentTraceHook} 在 harness 2.0.0 中不存在，
 * 本版本的等价机制是 {@link MiddlewareBase} 钩子链（框架自带的
 * {@code AgentTraceMiddleware} 只打 slf4j 日志，进不了日记库）。本类按文章的 trace
 * 口径补齐事件流旁路观察（{@link AgentEventRecorder}）拿不到的两类内容：
 * <ul>
 *   <li>MSG_RECV：onAgent 阶段取本轮主人消息原文，独立上报「主人消息」事件；</li>
 *   <li>TOOL_CALL 参数：onActing 阶段从 {@link ToolUseBlock#getInput()} 拿调用参数
 *       （ToolCallStartEvent 不携带 args），回填到对应工具调用事件。</li>
 * </ul>
 *
 * <p>事件落库仍由构造时注入的 {@link AgentEventRecorder} 统一负责（分类、token 分摊、
 * Turn 收尾都在它那里），本中间件只做「链路视角」的增量喂入，全程 try-catch 不阻断主流程。
 */
public final class MiaoTraceMiddleware implements MiddlewareBase {

    private final AgentEventRecorder recorder;

    public MiaoTraceMiddleware(AgentEventRecorder recorder) {
        this.recorder = recorder;
    }

    /** PRE_CALL：本轮主人消息 → MSG_RECV（日记独立条目，事件表可见每轮提问）。 */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        try {
            if (recorder != null && input != null && input.msgs() != null) {
                Msg last = null;
                for (Msg m : input.msgs()) {
                    if (m.getRole() == MsgRole.USER) last = m;
                }
                if (last != null && last.getTextContent() != null && !last.getTextContent().isBlank()) {
                    recorder.reportUserMessage(last.getTextContent());
                }
            }
        } catch (Exception ignored) {
            // 埋点失败绝不阻断 Agent 主流程
        }
        return next.apply(input);
    }

    /** PRE_ACTING：工具调用参数 input={...} → 回填对应 TOOL_CALL 事件。 */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        try {
            if (recorder != null && input != null && input.toolCalls() != null) {
                for (ToolUseBlock tu : input.toolCalls()) {
                    recorder.attachToolArgs(tu.getId(), toJson(tu.getInput()));
                }
            }
        } catch (Exception ignored) {
            // 同上，不阻断
        }
        return next.apply(input);
    }

    /** 参数对象序列化为 JSON（与框架 AgentTraceMiddleware 同款工具）。 */
    private static String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try {
            return JsonUtils.getJsonCodec().toJson(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }
}
