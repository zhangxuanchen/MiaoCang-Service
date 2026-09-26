package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 喵喵技能：驱动内容提取 / 记录主人喜好的可积累指令。
 * kind：
 *  - EXTRACT    提取规则，拼进每次内容提取的指令中
 *  - PREFERENCE 主人喜好，由喵喵从反馈中总结生成（也可手写）
 * source：preset 内置 / miaomiao 喵喵自生成 / user 用户手写
 */
@Entity
@Table(name = "agent_skill")
public class AgentSkill {

    public static final String KIND_EXTRACT = "EXTRACT";
    public static final String KIND_PREFERENCE = "PREFERENCE";

    public static final String SOURCE_PRESET = "preset";
    public static final String SOURCE_MIAOMIAO = "miaomiao";
    public static final String SOURCE_USER = "user";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 指令正文：提取约定 / 喜好描述（会拼进 Agent 的提示词） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 20)
    private String source = SOURCE_MIAOMIAO;

    private boolean enabled = true;

    /** 被用于提取的次数（观察技能价值） */
    private int usageCount = 0;

    /** 适用工作流阶段：ALL / EXTRACT / READ_DEEP / READ_THIN / LEARN（老数据视为 ALL） */
    @Column(length = 20)
    private String stage = "ALL";

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 归属的猫（每只猫有独立的技能集，即它的预设配置文件；老库由种子任务兜底归属） */
    @Column(name = "cat_id")
    private Long catId;

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
