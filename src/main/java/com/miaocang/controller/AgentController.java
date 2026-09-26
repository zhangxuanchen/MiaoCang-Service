package com.miaocang.controller;

import com.miaocang.entity.AgentConfig;
import com.miaocang.entity.AgentSkill;
import com.miaocang.entity.CatAgentConfig;
import com.miaocang.service.ExtractPipeline;
import com.miaocang.service.MiaomiaoService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 喵喵 Agent：每只猫的预设配置（设置/技能）/ 内容提取 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final MiaomiaoService miaomiao;
    private final ExtractPipeline pipeline;

    public AgentController(MiaomiaoService miaomiao, ExtractPipeline pipeline) {
        this.miaomiao = miaomiao;
        this.pipeline = pipeline;
    }

    // ================= 该猫的喵喵设置（预设配置文件） =================

    /** 该猫生效的配置：猫行优先；未配置过时返回全局默认值并标注 ownConfig=false */
    @GetMapping("/cats/{catId}/agent-config")
    public Map<String, Object> catConfig(@PathVariable Long catId) {
        CatAgentConfig row = miaomiao.catConfigRow(catId);
        AgentConfig eff = miaomiao.config(catId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", eff.getProvider());
        m.put("baseUrl", eff.getBaseUrl());
        m.put("model", eff.getModel());
        m.put("hasKey", eff.getApiKey() != null && !eff.getApiKey().isBlank());
        m.put("ownConfig", row != null);
        m.put("mode", miaomiao.liveMode(catId) ? "live" : "mock");
        m.put("agentName", miaomiao.agent(catId).name());
        return m;
    }

    /** 保存该猫的专属配置：apiKey 为 null = 不改；空串 = 清除 */
    @PutMapping("/cats/{catId}/agent-config")
    public Map<String, Object> updateCatConfig(@PathVariable Long catId, @RequestBody Map<String, Object> body) {
        CatAgentConfig cc = miaomiao.catConfigRow(catId);
        if (cc == null) {
            cc = new CatAgentConfig();
            cc.setCatId(catId);
        }
        if (body.get("provider") != null) cc.setProvider(String.valueOf(body.get("provider")));
        if (body.get("baseUrl") != null) cc.setBaseUrl(blankToNull(String.valueOf(body.get("baseUrl"))));
        if (body.get("model") != null) cc.setModel(blankToNull(String.valueOf(body.get("model"))));
        if (body.get("apiKey") != null) {
            String k = String.valueOf(body.get("apiKey"));
            cc.setApiKey(k.isBlank() ? null : k.strip());
        }
        miaomiao.saveCatConfig(cc);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", cc.getProvider());
        m.put("mode", miaomiao.liveMode(catId) ? "live" : "mock");
        m.put("message", miaomiao.liveMode(catId) ? "已接入真实模型，这只猫开工了" : "已切换为模拟输出");
        return m;
    }

    // ================= 全局默认模型配置（书房 · 默认 AK 管理） =================

    /** 全局默认配置：所有未做专属配置的喵共用这份 */
    @GetMapping("/agent/default-config")
    public Map<String, Object> defaultConfig() {
        AgentConfig g = miaomiao.config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", g.getProvider());
        m.put("baseUrl", g.getBaseUrl());
        m.put("model", g.getModel());
        m.put("hasKey", g.getApiKey() != null && !g.getApiKey().isBlank());
        return m;
    }

    /** 保存全局默认配置：apiKey 为 null = 不改；空串 = 清除 */
    @PutMapping("/agent/default-config")
    public Map<String, Object> updateDefaultConfig(@RequestBody Map<String, Object> body) {
        AgentConfig g = miaomiao.config();
        g.setId(1L);
        if (body.get("provider") != null) g.setProvider(String.valueOf(body.get("provider")));
        if (body.get("baseUrl") != null) g.setBaseUrl(blankToNull(String.valueOf(body.get("baseUrl"))));
        if (body.get("model") != null) g.setModel(blankToNull(String.valueOf(body.get("model"))));
        if (body.get("apiKey") != null) {
            String k = String.valueOf(body.get("apiKey"));
            g.setApiKey(k.isBlank() ? null : k.strip());
        }
        miaomiao.saveConfig(g);
        boolean live = g.getApiKey() != null && !g.getApiKey().isBlank()
                && !AgentConfig.PROVIDER_MOCK.equals(g.getProvider());
        return Map.of("message", live ? "全局默认已接入真实模型，未专属配置的喵都会用它" : "全局默认已保存");
    }

    // ================= 内容提取 =================

    /** 手动触发/重新提取（同步返回结果） */
    @PostMapping("/agent/extract/{contentId}")
    public Map<String, Object> extract(@PathVariable Long contentId) throws Exception {
        return pipeline.extractNow(contentId);
    }

    /** 采纳归类建议 */
    @PostMapping("/agent/extract/{contentId}/apply")
    public Map<String, Object> applySuggestion(@PathVariable Long contentId) {
        return pipeline.applySuggestion(contentId);
    }

    /** 采纳标签 */
    @PostMapping("/agent/extract/{contentId}/accept-tags")
    public Map<String, Object> acceptTags(@PathVariable Long contentId) {
        pipeline.acceptTags(contentId);
        return Map.of("ok", true);
    }

    /** 记录反馈（ignore 等） */
    @PostMapping("/agent/extract/{contentId}/feedback")
    public Map<String, Object> feedback(@PathVariable Long contentId, @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "ignore");
        pipeline.record(contentId, action, null, blankToNull(body.get("finalValue")));
        return Map.of("ok", true);
    }

    /** 清空提取结果 */
    @DeleteMapping("/agent/extract/{contentId}")
    public Map<String, Object> clearExtract(@PathVariable Long contentId) {
        pipeline.clearExtract(contentId);
        return Map.of("ok", true);
    }

    /** 最近的反馈流水 */
    @GetMapping("/agent/feedback")
    public List<Map<String, Object>> feedbackList() {
        return pipeline.recentFeedback().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("contentId", f.getContentId());
            m.put("action", f.getAction());
            m.put("skillCodes", f.getSkillCodes());
            m.put("suggestion", f.getSuggestion());
            m.put("finalValue", f.getFinalValue());
            m.put("consumed", f.isConsumed());
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    // ================= 该猫的喵喵技能（预设配置文件） =================

    @GetMapping("/cats/{catId}/skills")
    public List<AgentSkill> skills(@PathVariable Long catId) {
        return pipeline.skills(catId);
    }

    /** 新建/更新技能（带 id = 更新），归属到这只猫 */
    @PostMapping("/cats/{catId}/skills")
    public AgentSkill saveSkill(@PathVariable Long catId, @RequestBody AgentSkill skill) {
        skill.setCatId(catId);
        return pipeline.saveSkill(skill);
    }

    @DeleteMapping("/cats/{catId}/skills/{id}")
    public Map<String, Object> deleteSkill(@PathVariable Long catId, @PathVariable Long id) {
        pipeline.deleteSkill(id);
        return Map.of("ok", true);
    }

    @PostMapping("/cats/{catId}/skills/{id}/toggle")
    public AgentSkill toggleSkill(@PathVariable Long catId, @PathVariable Long id) {
        return pipeline.toggleSkill(id);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
