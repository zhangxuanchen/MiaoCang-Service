package com.miaocang.chat;

import com.miaocang.entity.AgentConfig;
import com.miaocang.service.MiaomiaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摘要器：把已折叠轮次的原文（user + assistant + 既有 summary）交给 LLM 重新压缩。
 *
 * <p>L3 触发：当 {@link ConversationMemory#append} 折叠一轮进 summary 时，若累计摘要字符
 * 超过 {@code summaryThreshold}（默认 2000），调用本类把 joined 文本压成一段新的 summary；
 * 否则走规则累加兜底（不调模型）。
 *
 * <p>实现复用 {@link MiaomiaoService#agent(Long)} 的猫级 LLM 配置 —— 用全局默认配置（id=1）
 * 做摘要，不绑特定猫，因为摘要请求是无状态的文本压缩任务。
 */
public class ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);

    private static final String SUMMARIZE_SYSTEM = """
            你是会话记忆压缩器。下面给你一段已折叠的对话原文（含已有的早期摘要），
            请输出一段 200~400 字的中文摘要：
            1. 保留用户提问的核心意图与关键实体（书名/分类/猫名等）；
            2. 保留助手回复中的结论、决策、已做过的操作；
            3. 丢弃寒暄、重复信息、过程性描述；
            4. 摘要里如有「【记忆归档 #id】」标记，原样保留该标记（含 id）。
            只输出摘要正文，不要任何解释、引导或前后缀。""";

    private final MiaomiaoService miaomiao;

    public ContextSummarizer(MiaomiaoService miaomiao) {
        this.miaomiao = miaomiao;
    }

    /** 把一段文本压成摘要；失败时返回 null（调用方走兜底）。 */
    public String summarize(String joinedText) {
        try {
            // 用全局默认配置做摘要（id=1 即 PROVIDER_MOCK 或全局 OpenAI 兼容配置）
            AgentConfig cfg = miaomiao.config(null);
            if (cfg == null || AgentConfig.PROVIDER_MOCK.equals(cfg.getProvider())) {
                // mock 模式：直接截断到 maxSummaryChars 兜底（不调模型）
                return null;
            }
            // 复用 MiaomiaoService 的 OpenAiCompatAgent.chat(system, user)
            // 注：MiaoMiaoAgent.chat 是同步阻塞接口，正好符合此处"压缩要拿到结果再继续"的语义
            String user = "请压缩以下对话原文与早期摘要：\n\n" + joinedText;
            return miaomiao.agent(null).chat(SUMMARIZE_SYSTEM, user);
        } catch (Exception e) {
            log.warn("[摘要] LLM 调用失败，回退规则累加: {}", e.toString());
            return null;
        }
    }
}
