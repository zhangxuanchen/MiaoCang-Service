package com.miaocang.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 学习主题（我的学习）：一只喵的命名话题，绑定关键词做全库检索聚合 + 手动收录 */
@Entity
@Table(name = "study_topic")
public class StudyTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属猫 */
    @Column(name = "cat_id", nullable = false)
    private Long catId;

    @Column(nullable = false, length = 60)
    private String name;

    /** 按钮/页面图标（emoji） */
    @Column(length = 8)
    private String icon;

    /** 一句话简介（类似 skill md 的 description） */
    @Column(length = 300)
    private String description;

    /** 检索聚合关键词，逗号分隔 */
    @Column(length = 300)
    private String keywords;

    @Column(nullable = false)
    private Integer sort = 0;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
