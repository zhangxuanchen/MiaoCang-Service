package com.miaocang.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 学习主题的手动收录条目（钉选）：知识点 / 书 / 文章 */
@Entity
@Table(name = "study_topic_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"topic_id", "item_type", "item_key"}))
public class StudyTopicItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    /** point | book | content */
    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    /** P0001 | 01-书文件.md | 文章id */
    @Column(name = "item_key", nullable = false, length = 120)
    private String itemKey;

    /** 收录时的标题快照（列表展示用） */
    @Column(length = 200)
    private String title;

    /** 主人的收录备注 */
    @Column(length = 300)
    private String pinnedNote;

    @Column(name = "added_at")
    private java.time.LocalDateTime addedAt = java.time.LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPinnedNote() { return pinnedNote; }
    public void setPinnedNote(String pinnedNote) { this.pinnedNote = pinnedNote; }
    public java.time.LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(java.time.LocalDateTime addedAt) { this.addedAt = addedAt; }
}
