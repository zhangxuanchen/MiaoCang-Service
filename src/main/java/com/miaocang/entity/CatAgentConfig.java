package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 每只猫独立的喵喵配置（预设配置文件之一）。
 * provider=mock 时为模拟输出；配置了 apiKey 的 OpenAI 兼容服务即启用真实模型。
 * 某只猫没有自己的配置时，沿用全局 AgentConfig(id=1) 作为兜底。
 */
@Entity
@Table(name = "cat_agent_config")
public class CatAgentConfig {

    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_OPENAI_COMPAT = "openai-compat";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属的猫，每只猫一行 */
    @Column(nullable = false, unique = true)
    private Long catId;

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
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
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
