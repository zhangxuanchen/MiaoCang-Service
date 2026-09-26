package com.miaocang.entity;

import jakarta.persistence.*;

/** 知识点↔文章关联：一个知识点可能融合书里多篇文章产出，这里记录它的每一条出处（文件给人读，关联给系统查） */
@Entity
@Table(name = "understanding_card_source",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cat_id", "no", "content_id"}))
public class UnderstandingCardSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属的猫（随猫重跑整批重建） */
    @Column(name = "cat_id", nullable = false)
    private Long catId;

    /** 知识点全局编号（P0001，与 understanding_card.no 对应） */
    @Column(nullable = false, length = 12)
    private String no;

    /** 出处文章（ContentItem） */
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    /** 读取时文章指纹（SHA-256 标题|摘要|正文）：指纹变化 = 文章有修改，增量归纳需重读 */
    @Column(length = 64)
    private String fingerprint;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public String getNo() { return no; }
    public void setNo(String no) { this.no = no; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
}
