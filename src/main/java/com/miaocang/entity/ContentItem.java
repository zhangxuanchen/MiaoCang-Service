package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 内容条目：一次投入的最小单元。
 * 类型：URL（网页链接）/ WORD / EXCEL / TEXT
 * 归属：分类成功 -> bookId + catalogNodeId（二级目录）；未达标 -> 收集箱（status=PENDING）
 */
@Entity
@Table(name = "content_item")
public class ContentItem {

    public static final String TYPE_URL = "URL";
    public static final String TYPE_WORD = "WORD";
    public static final String TYPE_EXCEL = "EXCEL";
    public static final String TYPE_TEXT = "TEXT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CLASSIFIED = "CLASSIFIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** 内容类型：URL / WORD / EXCEL / TEXT */
    @Column(nullable = false)
    private String contentType;

    /** 来源：网页 URL 或上传文件的原始文件名 */
    @Column(length = 1000)
    private String source;

    /** 上传文件在服务器上的存储路径（URL/TEXT 为空） */
    @Column(length = 500)
    private String storedPath;

    /** 抽取出的正文，用于引力分类与全文搜索（采集时已截断至 max-text-length） */
    @Column(length = 20000)
    private String rawText;

    /** 摘要（列表与详情页展示） */
    @Column(length = 1000)
    private String summary;

    /** 归属的书（未分类时为 null） */
    private Long bookId;

    /** 归属喵（谁归档算谁的）：归档/移动动作发生时的猫；收集箱待归档时为 null */
    private Long catId;

    /** 归属的目录节点（一般为二级目录 id） */
    private Long catalogNodeId;

    /** 引力波打分明细 JSON：[{"typeId":1,"name":"技术研发","score":12,"matched":["算法"]}] */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String typeScores;

    /** 命中的引力关键词，逗号分隔 */
    @Column(length = 1000)
    private String matchedTags;

    /** 是否由引力引擎自动归档 */
    private boolean autoClassified;

    /** PENDING=收集箱 / CLASSIFIED=已归档 */
    @Column(nullable = false)
    private String status = STATUS_PENDING;

    /** 书库文件树中的相对路径（与喵藏客户端书库格式一致），未同步时为 null */
    @Column(length = 1000)
    private String libraryPath;

    /** 喵喵提取结果 JSON：{summary, keyPoints:[], quotes:[], tags:[], suggest:{bookId,bookTitle,catalogName,reason}, related:[{contentId,title,reason}]} */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String extract;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Long getBookId() { return bookId; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Long getCatalogNodeId() { return catalogNodeId; }
    public void setCatalogNodeId(Long catalogNodeId) { this.catalogNodeId = catalogNodeId; }
    public String getTypeScores() { return typeScores; }
    public void setTypeScores(String typeScores) { this.typeScores = typeScores; }
    public String getMatchedTags() { return matchedTags; }
    public void setMatchedTags(String matchedTags) { this.matchedTags = matchedTags; }
    public boolean isAutoClassified() { return autoClassified; }
    public void setAutoClassified(boolean autoClassified) { this.autoClassified = autoClassified; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLibraryPath() { return libraryPath; }
    public void setLibraryPath(String libraryPath) { this.libraryPath = libraryPath; }
    public String getExtract() { return extract; }
    public void setExtract(String extract) { this.extract = extract; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
