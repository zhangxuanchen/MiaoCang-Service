package com.miaocang.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 喵事件（移植自 Memory-Observatory 的 memory_events，字段与上报协议完全对齐）。
 *
 * <p>数据来源：专属工作区 Agent 会话管线的旁路埋点（{@code AgentEventRecorder}）与
 * 协议兼容的上报接口（{@code POST /api/v1/events}）。一轮对话 = 一个 Turn：
 * <ul>
 *   <li>主事件：layer=session，metadata 带 turn_message_id / turn_user / turn_outcome</li>
 *   <li>action 子事件：metadata.turn_message_id 关联主事件，action_idx 排序</li>
 * </ul>
 * turn_message_id / action_idx / status 从 metadata 抽出冗余成列，便于 H2 聚合查询。
 */
@Entity
@Table(name = "cat_events", indexes = {
        @Index(name = "idx_ce_agent_ts", columnList = "agentId, ts"),
        @Index(name = "idx_ce_session", columnList = "sessionId, ts"),
        @Index(name = "idx_ce_turn", columnList = "turnMessageId"),
        @Index(name = "idx_ce_layer", columnList = "layer, ts")
})
public class CatEvent {

    /** 事件主键：16 位 hex（缺省自动生成）。 */
    @Id
    @Column(name = "event_id", length = 40, nullable = false)
    private String eventId;

    /** Agent 标识 = 工作区路径（cat-{id}，cat-0 = Wiki管理员）。 */
    @Column(name = "agent_id", nullable = false, length = 100)
    private String agentId;

    /** 会话标识。 */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    /** 操作：READ / WRITE / UPDATE / EXPIRE。 */
    @Column(name = "operation", nullable = false, length = 20)
    private String operation;

    /** 层：model / text / skill / session / provider / control… */
    @Column(name = "layer", nullable = false, length = 20)
    private String layer;

    /** 记忆 key，如 model:{agent}、tool:read_file、turn:{session}。 */
    @Column(name = "memory_key", length = 300)
    private String memoryKey;

    /** 摘要预览（不含原始大段数据）。 */
    @Column(name = "memory_summary", columnDefinition = "TEXT")
    private String memorySummary;

    /** 涉及 token 数。 */
    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    /** 操作耗时 ms。 */
    @Column(name = "latency_ms")
    private double latencyMs;

    /** 事件时间。 */
    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    /** 附加属性（JSON 字符串，String→String）。 */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /** Trace：一个 session = 一个 trace。 */
    @Column(name = "trace_id", length = 40)
    private String traceId;

    /** 父 Span（Turn 主事件为 null）。 */
    @Column(name = "parent_span_id", length = 20)
    private String parentSpanId;

    /** 冗余列：metadata.turn_message_id，Turn 聚合查询用。 */
    @Column(name = "turn_message_id", length = 120)
    private String turnMessageId;

    /** 冗余列：metadata.action_idx，子事件排序用。 */
    @Column(name = "action_idx")
    private Integer actionIdx;

    /** 冗余列：metadata.status（failed 等），问题检测用。 */
    @Column(name = "status", length = 20)
    private String status;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getMemoryKey() { return memoryKey; }
    public void setMemoryKey(String memoryKey) { this.memoryKey = memoryKey; }
    public String getMemorySummary() { return memorySummary; }
    public void setMemorySummary(String memorySummary) { this.memorySummary = memorySummary; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public double getLatencyMs() { return latencyMs; }
    public void setLatencyMs(double latencyMs) { this.latencyMs = latencyMs; }
    public LocalDateTime getTs() { return ts; }
    public void setTs(LocalDateTime ts) { this.ts = ts; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    public String getTurnMessageId() { return turnMessageId; }
    public void setTurnMessageId(String turnMessageId) { this.turnMessageId = turnMessageId; }
    public Integer getActionIdx() { return actionIdx; }
    public void setActionIdx(Integer actionIdx) { this.actionIdx = actionIdx; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
