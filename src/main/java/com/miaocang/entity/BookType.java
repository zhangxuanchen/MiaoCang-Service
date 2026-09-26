package com.miaocang.entity;

import jakarta.persistence.*;

/**
 * 书籍类型：内容的"引力场"。
 * 预设 22 种，可自定义。每个类型带有关键词引力场（gravityKeywords）
 * 与书内两级目录规则（catalogRules JSON）。
 */
@Entity
@Table(name = "book_type")
public class BookType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 类型名，如"技术研发" */
    @Column(nullable = false)
    private String name;

    /** 图标（emoji） */
    private String icon;

    /** 主题色 */
    private String color;

    /** 类型描述 */
    @Column(length = 500)
    private String description;

    /**
     * 引力关键词，逗号分隔。内容命中越多，被此类型吸引的引力越强。
     */
    @Column(length = 2000)
    private String gravityKeywords;

    /**
     * 书内两级目录规则（JSON 数组）：
     * [{"name":"一级目录","keywords":[...],"children":[{"name":"二级目录","keywords":[...]}]}]
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String catalogRules;

    /** 是否为预设类型（全局共享、不归属单只喵；可在书房总览的「预设置分类」里直接删除） */
    private boolean preset;

    /** 所属猫 id：每只猫照看一批知识分类（新库必填，老库由种子任务兜底归属） */
    @Column(name = "cat_id")
    private Long catId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGravityKeywords() { return gravityKeywords; }
    public void setGravityKeywords(String gravityKeywords) { this.gravityKeywords = gravityKeywords; }
    public String getCatalogRules() { return catalogRules; }
    public void setCatalogRules(String catalogRules) { this.catalogRules = catalogRules; }
    public boolean isPreset() { return preset; }
    public void setPreset(boolean preset) { this.preset = preset; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
}
