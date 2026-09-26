package com.miaocang.chat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 统一「单轮 Agent 执行」入口（移植自 Memory-Observatory，按喵藏场景简化）。
 *
 * <p>流程：消费待压缩标记 → 组会话记忆注入块 → 建该猫 Agent 实例 → 投喂消息 →
 * 跑事件流（事件转 {@link EventSink} + 抽取计量）→ 落盘会话记忆 → 生命周期回调。
 * 单轮最长执行时间由 TurnSpec.timeout 控制（watchdog 超时取消订阅并回调 onTimeout）；
 * heartbeat 供长阻塞期间周期回调保持 SSE 连接活跃。
 */
public class SingleTurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(SingleTurnExecutor.class);

    private final MiaoMiaoAgentFactory agentFactory;
    private final ConversationMemory conversationMemory;
    private final ContextSummarizer contextSummarizer;
    private final ChatProperties props;
    /** 喵事件埋点落库（喵的日记数据源），旁路容错。 */
    private final com.miaocang.service.CatEventService eventService;

    public SingleTurnExecutor(MiaoMiaoAgentFactory agentFactory,
                              ConversationMemory conversationMemory,
                              ContextSummarizer contextSummarizer,
                              ChatProperties props,
                              com.miaocang.service.CatEventService eventService) {
        this.agentFactory = agentFactory;
        this.conversationMemory = conversationMemory;
        this.contextSummarizer = contextSummarizer;
        this.props = props;
        this.eventService = eventService;
    }

    /** 单轮执行所需上下文（由调用方组装）。 */
    public record TurnSpec(
            Long catId,
            String workspacePath,
            String sessionId,
            String message,
            /** 主人当前所在页面的上下文描述（可为 null/空）：让喵结合用户正在看的页面答题 */
            String pageContext,
            /** RAG 领地检索命中块（可为 null）：请求线程预先检索好的知识片段，随本轮消息注入 */
            String ragContext,
            /** laya「System 1」意图预判块（可为 null）：只做软提示，不改 ReAct 逻辑 */
            String intentHint,
            /** 单轮最长执行时间；null 表示不设 timeout 看门狗。 */
            Duration timeout,
            /** 长阻塞期间的周期心跳回调（可为 null）。 */
            Runnable heartbeat) {
    }

    /** 单轮执行结果：助手最终文本。 */
    public record TurnResult(String assistantText) {
    }

    /** 单轮事件转发口：由调用方决定每个 AgentEvent 如何转出（SSE 等）。 */
    public interface EventSink {
        void onEvent(AgentEvent ev);
    }

    /** 单轮生命周期回调：三类收尾互斥触发一次。 */
    public abstract static class TurnListener {
        /** 单轮正常结束（已落盘会话记忆后触发）。 */
        public void onDone(TurnResult result) {
        }

        /** 单轮流式执行异常（含 create/stream 阶段）。 */
        public void onError(Throwable t) {
        }

        /** 单轮超时（由看门狗触发，订阅已被取消）。 */
        public void onTimeout() {
        }
    }

    /**
     * 执行单轮 Agent。create 阶段的异常直接抛给调用方兜底；流式阶段异常走 onError。
     */
    public void runTurn(TurnSpec spec, EventSink sink, TurnListener listener) {
        String memKey = ConversationMemory.key(spec.workspacePath(), "miaomiao", spec.sessionId());

        // 1) 若挂有「待压缩」标记，先消费并执行一次自收敛，让本轮读到收敛后的记忆
        if (conversationMemory.consumeCompress(memKey)) {
            conversationMemory.compressMem(memKey, props.getCompressTargetChars(), contextSummarizer);
        }

        // 2) 会话历史上下文注入块
        String memoryContext = conversationMemory.context(memKey);

        // 3) 建该猫本轮 Agent 实例（trace 中间件随行：链路内补记 MSG_RECV / 工具参数）
        AgentEventRecorder recorder = new AgentEventRecorder(eventService, spec.workspacePath(),
                spec.sessionId(), spec.message());
        HarnessAgent agent;
        try {
            agent = agentFactory.create(spec.catId(), spec.workspacePath(), spec.sessionId(), memoryContext,
                    new MiaoTraceMiddleware(recorder));
        } catch (Exception e) {
            listener.onError(e);
            return;
        }
        if (agent == null) {
            listener.onError(new IllegalStateException("MOCK_MODE"));
            return;
        }

        // 4) 跑事件流：事件转 sink，同时抽取计量（助手文本 / 工具输出字符）+ 旁路埋点（喵的日记）
        StringBuilder asst = new StringBuilder();
        AtomicLong toolChars = new AtomicLong();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        Flux<AgentEvent> stream;
        try {
            /* 页面上下文 + RAG 领地检索命中都拼在用户消息前面（一次性的 situational 注入）：
               会话记忆落盘仍是干净的 spec.message() */
            StringBuilder ut = new StringBuilder();
            if (spec.ragContext() != null && !spec.ragContext().isBlank()) {
                ut.append(spec.ragContext()).append("\n\n");
            }
            if (spec.pageContext() != null && !spec.pageContext().isBlank()) {
                ut.append("【主人当前所在页面】").append(spec.pageContext()).append("\n\n");
            }
            if (spec.intentHint() != null && !spec.intentHint().isBlank()) {
                ut.append(spec.intentHint()).append("\n\n");
            }
            ut.append("【主人的消息】").append(spec.message());
            Msg userMsg = Msg.builder().textContent(ut.toString()).build();
            /* cancel 兜底：客户端断开 / 上层 dispose 时 complete 与 error 都不触发，
               日记 Turn 主事件会丢——doOnCancel 补一次收尾（recorder.finish 内部防重入） */
            stream = agent.streamEvents(userMsg)
                    .doOnCancel(() -> recorder.finish(new RuntimeException("回合被取消（客户端断开或订阅终止）")));
        } catch (Exception e) {
            listener.onError(e);
            return;
        }
        subRef.set(stream.subscribe(
                ev -> {
                    sink.onEvent(ev);
                    accumulate(ev, asst, toolChars);
                    recorder.observe(ev);
                },
                err -> {
                    recorder.finish(err);
                    if (finished.compareAndSet(false, true)) {
                        listener.onError(err);
                    }
                },
                () -> {
                    recorder.finish(null);
                    if (finished.compareAndSet(false, true)) {
                        persist(memKey, spec.message(), asst.toString(), toolChars.get());
                        listener.onDone(new TurnResult(asst.toString()));
                    }
                }));

        // 5) 单轮看门狗：按 timeout 中断、按 heartbeat 周期回调
        if (spec.timeout() != null || spec.heartbeat() != null) {
            Thread watchdog = new Thread(() -> {
                long waited = 0;
                while (!finished.get()) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (finished.get()) {
                        break;
                    }
                    waited += 5000;
                    if (spec.heartbeat() != null) {
                        try {
                            spec.heartbeat().run();
                        } catch (Exception ignored) {
                            // 心跳失败不影响单轮主流程
                        }
                    }
                    if (spec.timeout() != null && waited >= spec.timeout().toMillis()
                            && finished.compareAndSet(false, true)) {
                        Disposable d = subRef.get();
                        if (d != null && !d.isDisposed()) {
                            d.dispose();
                        }
                        recorder.finish(new RuntimeException("单轮执行超时"));
                        listener.onTimeout();
                        break;
                    }
                }
            }, "cat-chat-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    /** 抽取文本增量 token 与工具输出字符，供统一的会话记忆计量。 */
    private void accumulate(AgentEvent ev, StringBuilder asst, AtomicLong toolChars) {
        if (ev instanceof TextBlockDeltaEvent t) {
            String d = t.getDelta();
            if (d != null) {
                asst.append(d);
            }
        } else if (ev instanceof ToolResultTextDeltaEvent t) {
            String d = t.getDelta();
            if (d != null) {
                toolChars.addAndGet(d.length());
            }
        }
    }

    /** 落盘单轮会话记忆：先累加工具输出字符计量，再追加对话轮（按需触发四层压缩）。 */
    private void persist(String memKey, String message, String asst, long toolChars) {
        try {
            conversationMemory.recordTool(memKey, toolChars);
            conversationMemory.append(memKey, message, asst, contextSummarizer);
        } catch (Exception e) {
            log.warn("[会话区] 记录会话记忆失败（不阻断）: {}", e.toString());
        }
    }
}
