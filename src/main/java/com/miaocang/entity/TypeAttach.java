package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 共享预设分类的图谱挂接记录：某只喵把一个全局共享分类挂上自己的知识图谱。
 * 数据本身仍是共享的（书、内容谁归档算谁的），挂接只表达「该喵的图谱结构里常驻这个分类节点」。
 */
@Entity
@Table(name = "type_attach", uniqueConstraints = @UniqueConstraint(columnNames = {"type_id", "cat_id"}))
public class TypeAttach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 共享预设分类 id */
    @Column(name = "type_id", nullable = false)
    private Long typeId;

    /** 挂接的喵 id */
    @Column(name = "cat_id", nullable = false)
    private Long catId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
