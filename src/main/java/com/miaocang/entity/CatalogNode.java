package com.miaocang.entity;

import jakarta.persistence.*;

/**
 * 书内目录节点，严格两级：
 * - parentId == null  -> 一级目录（章）
 * - parentId != null  -> 二级目录（节）
 */
@Entity
@Table(name = "catalog_node")
public class CatalogNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属书 */
    @Column(nullable = false)
    private Long bookId;

    /** 父目录 id，null 表示一级目录 */
    private Long parentId;

    @Column(nullable = false)
    private String name;

    /** 排序 */
    private Integer orderIndex = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
}
