package com.miaocang.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 反馈累积：记录主人对喵喵提取结果的处理动作，供喵喵周期性总结生成新的 PREFERENCE 技能。
 * action：
 *  - accept_apply  采纳归类建议（点了"采纳"）
 *  - accept_tag    采纳标签
 *  - edit_summary  手改了摘要
 *  - ignore        忽略提取结果
 *  - delete_extract 清空了提取结果
 */
@Entity
@Table(name = "agent_feedback")
public class AgentFeedback {

    public static final String ACTION_ACCEPT_APPLY = "accept_apply";
    public static final String ACTION_ACCEPT_TAG = "accept_tag";
    public static final String ACTION_EDIT_SUMMARY = "edit_summary";
    public static final String ACTION_IGNORE = "ignore";
    public static final String ACTION_DELETE_EXTRACT = "delete_extract";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的内容条目 */
    @Column(nullable = false)
    private Long contentId;

    /** 主人的处理动作（见常量） */
    @Column(nullable = false, length = 30)
    private String action;

    /** 本次提取用到的技能 code，逗号分隔（方便归因哪个 skill 带来了好评） */
    @Column(length = 500)
    private String skillCodes;

    /** 喵喵当时给出的建议（归类目标 / 标签等，JSON 片段） */
    @Column(length = 1000)
    private String suggestion;

    /** 主人最终采用的结果（手改后的摘要 / 实际归入的书等） */
    @Column(length = 1000)
    private String finalValue;

    /** 归属的猫（反馈按猫累积，技能总结按猫进行） */
    private Long catId;

    /** 产生反馈的工作流阶段：EXTRACT / READ_DEEP / READ_THIN / LEARN（老数据视为 EXTRACT） */
    @Column(length = 20)
    private String stage = "EXTRACT";

    /** 是否已被喵喵消化（总结进技能后置 true，避免重复总结） */
    private boolean consumed = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getSkillCodes() { return skillCodes; }
    public void setSkillCodes(String skillCodes) { this.skillCodes = skillCodes; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public String getFinalValue() { return finalValue; }
    public void setFinalValue(String finalValue) { this.finalValue = finalValue; }
    public Long getCatId() { return catId; }
    public void setCatId(Long catId) { this.catId = catId; }
    public boolean isConsumed() { return consumed; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
