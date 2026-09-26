package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 学习卡片：「学透」的产物——正面提问/背面答案，供碎片时间主动回忆（简化 SM-2 调度）。 */
@Entity
@Table(name = "learn_card")
public class LearnCard {

    public static final String KIND_RECALL = "recall";
    public static final String KIND_FEYNMAN = "feynman";
    public static final String KIND_SOCRATIC = "socratic";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属的猫 */
    @Column(name = "cat_id", nullable = false)
    private Long catId;

    /** 来源理解卡片（可空） */
    @Column(name = "understanding_card_id")
    private Long understandingCardId;

    /** 来源书文件名（可空） */
    @Column(name = "book_file", length = 200)
    private String bookFile;

    /** 来源书标题（可空） */
    @Column(name = "book_title", length = 300)
    private String bookTitle;

    /** 卡片类型：recall / feynman / socratic */
    @Column(nullable = false, length = 20)
    private String kind = KIND_RECALL;

    /** 正面提问 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String front;

    /** 背面答案 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String back;

    /** SM-2 简化：难度系数 / 当前间隔天数 / 到期日 / 复习次数 */
    private double ease = 2.5;
    /** 复习间隔天数；interval 是 H2 关键字，列名转义 */
    @Column(name = "`interval`")
    private int interval = 0;
    private LocalDate due = LocalDate.now();
    private int reviews = 0;

    /** 连续忘记 / 连续记住 计数（驱动自动降频与休眠） */
    private int lapseStreak = 0;
    private int passStreak = 0;

    /** 休眠（连续 3 次记住后不再到期） */
    private boolean dormant = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public Long getUnderstandingCardId() { return understandingCardId; }
    public void setUnderstandingCardId(Long understandingCardId) { this.understandingCardId = understandingCardId; }
    public String getBookFile() { return bookFile; }
    public void setBookFile(String bookFile) { this.bookFile = bookFile; }
    public String getBookTitle() { return bookTitle; }
    public void setBookTitle(String bookTitle) { this.bookTitle = bookTitle; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFront() { return front; }
    public void setFront(String front) { this.front = front; }
    public String getBack() { return back; }
    public void setBack(String back) { this.back = back; }
    public double getEase() { return ease; }
    public void setEase(double ease) { this.ease = ease; }
    public int getInterval() { return interval; }
    public void setInterval(int interval) { this.interval = interval; }
    public LocalDate getDue() { return due; }
    public void setDue(LocalDate due) { this.due = due; }
    public int getReviews() { return reviews; }
    public void setReviews(int reviews) { this.reviews = reviews; }
    public int getLapseStreak() { return lapseStreak; }
    public void setLapseStreak(int lapseStreak) { this.lapseStreak = lapseStreak; }
    public int getPassStreak() { return passStreak; }
    public void setPassStreak(int passStreak) { this.passStreak = passStreak; }
    public boolean isDormant() { return dormant; }
    public void setDormant(boolean dormant) { this.dormant = dormant; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
