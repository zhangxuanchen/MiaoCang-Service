package com.miaocang.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 每猫专属会话区配置（miaocang.chat.*）。
 * 与 application.yml 的 miaocang.chat 段一一对应。
 * 纯配置 POJO，不承载任何行为；装配见 {@link ChatConfig}。
 */
@ConfigurationProperties(prefix = "miaocang.chat")
public class ChatProperties {

    /** 工作区根目录：与 library.dir 同源，每只猫工作区 = workspaceBase/cat-{id}/。 */
    private String workspaceBase = "./data/library";

    /** 工作区内记忆状态子目录（{@link io.agentscope.core.state.JsonFileAgentStateStore} 的根）。 */
    private String contextDir = ".context";

    /** 工作区内归档子目录（被折叠轮次的完整原文，可经工具召回）。 */
    private String archiveDir = ".archive";

    /** 工作区内 sandbox 子目录（{@link io.agentscope.harness.agent.HarnessAgent} 的工作目录）。 */
    private String sandboxDir = ".workbench";

    /** 单轮对话超时（毫秒）：超时 SseEmitter 自动断开。 */
    private long timeoutMs = 600_000L;

    /** 心跳间隔（毫秒）：长会话期间定期发 ping 防止代理掐断。 */
    private long heartbeatMs = 15_000L;

    /** L1：单条消息超过该字符数 → 头 truncateHead + 尾 truncateTail 截断。 */
    private int truncateThreshold = 12_000;
    private int truncateHead = 6_000;
    private int truncateTail = 2_000;

    /** L2：超过该轮数 → 最旧一轮折叠进 summary，原文归档。 */
    private int keepRecent = 5;

    /** L3：summary 累计超过该字符数 → 调模型重摘要（否则规则累加截断）。 */
    private int summaryThreshold = 2_000;

    /** 规则累加后的摘要上限（兜底，保证摘要不无限增长）。 */
    private int maxSummaryChars = 2_000;

    /** 消费「标记压缩」时记忆区的目标体积（字符），对应 ContextBudget.MEMORY 的作用。 */
    private int compressTargetChars = 30_000;

    /** 折叠足迹文件最大记录条数。 */
    private int foldTrailCap = 20;

    /** 压缩日志每 key 保留条数（in-memory）。 */
    private int compressLogCap = 20;

    /** 折叠足迹中保留的用户问题摘句字符数。 */
    private int foldSnippet = 40;

    // ---- getters / setters ----

    public String getWorkspaceBase() { return workspaceBase; }
    public void setWorkspaceBase(String workspaceBase) { this.workspaceBase = workspaceBase; }

    public String getContextDir() { return contextDir; }
    public void setContextDir(String contextDir) { this.contextDir = contextDir; }

    public String getArchiveDir() { return archiveDir; }
    public void setArchiveDir(String archiveDir) { this.archiveDir = archiveDir; }

    public String getSandboxDir() { return sandboxDir; }
    public void setSandboxDir(String sandboxDir) { this.sandboxDir = sandboxDir; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getHeartbeatMs() { return heartbeatMs; }
    public void setHeartbeatMs(long heartbeatMs) { this.heartbeatMs = heartbeatMs; }

    public int getTruncateThreshold() { return truncateThreshold; }
    public void setTruncateThreshold(int truncateThreshold) { this.truncateThreshold = truncateThreshold; }

    public int getTruncateHead() { return truncateHead; }
    public void setTruncateHead(int truncateHead) { this.truncateHead = truncateHead; }

    public int getTruncateTail() { return truncateTail; }
    public void setTruncateTail(int truncateTail) { this.truncateTail = truncateTail; }

    public int getKeepRecent() { return keepRecent; }
    public void setKeepRecent(int keepRecent) { this.keepRecent = keepRecent; }

    public int getSummaryThreshold() { return summaryThreshold; }
    public void setSummaryThreshold(int summaryThreshold) { this.summaryThreshold = summaryThreshold; }

    public int getMaxSummaryChars() { return maxSummaryChars; }
    public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }

    public int getCompressTargetChars() { return compressTargetChars; }
    public void setCompressTargetChars(int compressTargetChars) { this.compressTargetChars = compressTargetChars; }

    public int getFoldTrailCap() { return foldTrailCap; }
    public void setFoldTrailCap(int foldTrailCap) { this.foldTrailCap = foldTrailCap; }

    public int getCompressLogCap() { return compressLogCap; }
    public void setCompressLogCap(int compressLogCap) { this.compressLogCap = compressLogCap; }

    public int getFoldSnippet() { return foldSnippet; }
    public void setFoldSnippet(int foldSnippet) { this.foldSnippet = foldSnippet; }
}
