package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 喵喵 Agent 配置（单行，id 恒为 1）。
 * provider=mock 时为模拟输出；配置了 apiKey 的 OpenAI 兼容服务即启用真实模型。
 * 未来接入 agentscope-harness 时，baseUrl 指向其兼容端点即可复用本配置。
 */
@Entity
@Table(name = "agent_config")
public class AgentConfig {

    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_OPENAI_COMPAT = "openai-compat";

    @Id
    private Long id = 1L;

    /** mock / openai-compat */
    @Column(nullable = false)
    private String provider = PROVIDER_MOCK;

    /** OpenAI 兼容端点，如 https://api.deepseek.com/v1 */
    @Column(length = 500)
    private String baseUrl;

    /** 模型名，如 deepseek-chat */
    @Column(length = 200)
    private String model;

    /** API Key（AK） */
    @Column(length = 500)
    private String apiKey;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
