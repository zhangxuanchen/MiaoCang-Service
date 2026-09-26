package com.miaocang.service.agent;

import java.util.List;

/**
 * 喵喵 Agent 接口：两种能力——
 * 1. extract：读一条内容 → 提取摘要/要点/金句/标签/归类建议/关联（喵喵内容提取）
 * 2. chat：自由对话（喵喵总结技能等元任务）
 *
 * 当前实现：
 * - {@link MockMiaoMiaoAgent} 模拟输出（默认，零依赖）
 * - {@link OpenAiCompatAgent} OpenAI 兼容端点（DeepSeek / 智谱 / Kimi / OpenAI…）
 *
 * 预留：agentscope-harness（Python Agent）可作为一个独立实现接入——
 * 其服务若暴露 OpenAI 兼容端点，直接配 baseUrl 即可复用 {@link OpenAiCompatAgent}。
 */
public interface MiaoMiaoAgent {

    /** 本次提取任务的素材：一条内容 + 书库上下文 */
    record Material(String title, String path, String summary, String excerpt) {
    }

    /** 最近一次模型调用消耗的 token（OpenAI 兼容端点的 usage 字段）；模拟实现返回 null */
    record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        public long total() { return totalTokens; }
    }

    // ==================== 内容提取 ====================

    /** 可选的归类去向（现书库里已有的书），供模型给出落点建议 */
    record BookOption(Long bookId, String bookTitle, String bookDescription) {
    }

    /** 关联候选：书库里其它内容条目 */
    record RelatedCandidate(Long contentId, String title, String summary) {
    }

    record ExtractTask(String title, String contentType, String rawText, String summary,
                       /** 当前所属书（null=还在收集箱） */
                       String currentBook,
                       /** 当前命中的引力关键词 */
                       List<String> existingTags,
                       /** 可选的归类去向 */
                       List<BookOption> bookOptions,
                       /** 关联候选（书库里其它内容的 id+标题+摘要） */
                       List<RelatedCandidate> candidates,
                       /** 启用的技能指令正文（EXTRACT 提取规则 + PREFERENCE 主人喜好），按行拼好 */
                       List<String> skillDirectives) {
    }

    record RelatedItem(Long contentId, String title, String reason) {
    }

    /** 归类建议：bookId=null 表示建议保持现状（含收集箱暂不归类），bookTitle/ Reason 说明理由 */
    record Suggestion(Long bookId, String bookTitle, String reason) {
    }

    record ExtractResult(String summary, List<String> keyPoints, List<String> quotes,
                         List<String> tags, Suggestion suggest, List<RelatedItem> related) {
    }

    String name();

    ExtractResult extract(ExtractTask task) throws Exception;

    /** 最近一次模型调用消耗的 token（含 extract / chat）；模拟实现返回 null */
    default TokenUsage lastUsage() { return null; }

    /** 自由对话（喵喵总结技能等元任务用），实现不支持时抛异常 */
    default String chat(String system, String user) throws Exception {
        throw new UnsupportedOperationException("该实现不支持自由对话");
    }
}
