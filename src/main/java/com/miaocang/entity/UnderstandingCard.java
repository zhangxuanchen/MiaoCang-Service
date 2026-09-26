package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 知识点卡片：「把书读厚」的产物——喵按书聚合精读后提取的关键/重点/难点/知识点，skill 同款卡片（名称+简介+使用场景）。md 给人读，卡片给系统用。 */
@Entity
@Table(name = "understanding_card")
public class UnderstandingCard {

    /** 知识点四类：关键 / 重点 / 难点 / 知识点（旧数据默认 FACT） */
    public static final String KIND_KEY = "KEY";
    public static final String KIND_FOCUS = "FOCUS";
    public static final String KIND_DIFFICULT = "DIFFICULT";
    public static final String KIND_FACT = "FACT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 全局编号（P0001 递增）：points/ 文件名与关联引用的唯一标识 */
    @Column(length = 12)
    private String no;

    /** 知识点类别：KEY / FOCUS / DIFFICULT / FACT */
    @Column(length = 12)
    private String kind;

    /** 关联的知识书（Book 表，按书聚合精读的归属） */
    @Column(name = "book_id")
    private Long bookId;

    /** 出处内容条目（可回溯原文） */
    @Column(name = "content_id")
    private Long contentId;

    /** 出处标题（原文《…》徽标显示用；会话类卡片无 contentId 时仍有标题） */
    @Column(nullable = false, length = 300)
    private String src;

    /** 知识点名称（skill 同款 name；md 的 H1） */
    @Column(nullable = false, length = 200)
    private String topic;

    /** 一句话简介（skill 同款 description） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String point;

    /** 什么时候使用这个知识点（场景清单，JSON 数组字符串） */
    @Column(columnDefinition = "TEXT")
    private String usage;

    /** 补充展开正文（ENRICH 渐进补全：为什么关键/难在哪/例子/误解；可空=未补全） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 同书关联知识点编号（JSON 数组字符串，如 ["P0002","P0007"]） */
    @Column(length = 500)
    private String links;

    /** 所属书标题（读薄阶段回填：二级主题书） */
    @Column(length = 300)
    private String bookTitle;

    /** 所属书文件名（md 回链） */
    @Column(name = "book_file", length = 200)
    private String bookFile;

    /** 书内归位：一级主题（章，顺序见 reading/_outline.json 骨架） */
    @Column(length = 100)
    private String chapter;

    /** 书内归位：二级主题（节） */
    @Column(length = 100)
    private String section;

    /** 生成批次（同一次整理任务同一批） */
    @Column(length = 40)
    private String batchId;

    /** 归属的猫 */
    @Column(name = "cat_id", nullable = false)
    private Long catId;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNo() { return no; }
    public void setNo(String no) { this.no = no; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
    public String getSrc() { return src; }
    public void setSrc(String src) { this.src = src; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getPoint() { return point; }
    public void setPoint(String point) { this.point = point; }
    public String getUsage() { return usage; }
    public void setUsage(String usage) { this.usage = usage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getLinks() { return links; }
    public void setLinks(String links) { this.links = links; }
    public String getBookTitle() { return bookTitle; }
    public void setBookTitle(String bookTitle) { this.bookTitle = bookTitle; }
    public String getBookFile() { return bookFile; }
    public void setBookFile(String bookFile) { this.bookFile = bookFile; }
    public String getChapter() { return chapter; }
    public void setChapter(String chapter) { this.chapter = chapter; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
