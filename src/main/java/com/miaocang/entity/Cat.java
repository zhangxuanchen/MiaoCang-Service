package com.miaocang.entity;

import jakarta.persistence.*;

/**
 * 猫：用户养的宠物实体（如"小肥""喵喵"）。
 * 每只猫照看一批知识分类（BookType.catId），
 * 侧栏展示猫名单，点击猫进入它的知识图谱。
 */
@Entity
@Table(name = "cat")
public class Cat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 猫名，如"小肥""喵喵"。多用户后各用户独立命名（同名校验在应用层按用户做） */
    @Column(nullable = false)
    private String name;

    /** 所属主人（登录用户）：每用户的猫工作区在 <username>/cat-<id>/，彼此完全隔离 */
    private Long userId;

    /** 工作区路径：每只猫专属会话区 + 文档存储的子目录名（位于书库根下）。
     *  规则 = "<username>/cat-" + id（创建后由 Controller 回填，永不改变 —— 即使猫改名也保持稳定）。
     *  agentscope-harness 的 workspace、ConversationMemory 的 context 目录均落在此处，
     *  实现每只猫的会话状态与文档沙箱隔离。 */
    @Column(unique = true)
    private String workspacePath;

    /** 图标（emoji） */
    private String icon;

    /** 主题色（毛色） */
    private String color;

    /** 猫的简介 */
    @Column(length = 500)
    private String description;

    /** 展示顺序 */
    private Integer orderIndex;

    /** 是否允许喵自动整理（定时巡逻发现变化后自动触发）；false = 只能手动整理 */
    private Boolean autoTidy = Boolean.TRUE;

    public Boolean getAutoTidy() {
        return autoTidy == null || autoTidy; /* 老数据列为 NULL 时视为开启 */
    }

    public void setAutoTidy(Boolean autoTidy) { this.autoTidy = autoTidy; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
}
