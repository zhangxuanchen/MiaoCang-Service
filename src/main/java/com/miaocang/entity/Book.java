package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 书：内容最终整理成"一本本拥有二级目录的书"。
 * 每个类型默认有一本同名书，用户也可以在同一类型下自建多本书。
 */
@Entity
@Table(name = "book")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** 所属书籍类型 */
    @Column(nullable = false)
    private Long typeId;

    @Column(length = 500)
    private String description;

    /** 该类型下的默认书（类型创建时自动生成） */
    private boolean defaultBook;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isDefaultBook() { return defaultBook; }
    public void setDefaultBook(boolean defaultBook) { this.defaultBook = defaultBook; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
