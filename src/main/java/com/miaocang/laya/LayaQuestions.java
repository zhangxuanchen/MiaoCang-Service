package com.miaocang.laya;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * questions 构造器：{@code /v1/predict} 的 questions 是必填参数（{id → 定义}），
 * 直接手写嵌套 Map 太容易写错，这里收口成三个提问原语的构造方法。
 *
 * <p>结构与上游 {@code com.convai.laya.model.Q} 同构，只是不引那份 SDK。
 * 三种原语的含义见上游文档：choice（多分类）/ score（序数评分）/ noul（校准布尔概率）。
 */
public final class LayaQuestions {

    private LayaQuestions() {
    }

    /** 按给定顺序组装 questions（顺序即展示顺序，用 LinkedHashMap 保序）。 */
    @SafeVarargs
    public static Map<String, Object> questions(Map.Entry<String, Object>... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    public static Map.Entry<String, Object> entry(String id, Map<String, Object> def) {
        return new AbstractMap.SimpleEntry<>(id, def);
    }

    /** 多分类：criteria 的 key = 候选标签，value = 该标签的判定说明（保序）。 */
    public static Map<String, Object> choice(String instructions, Map<String, String> criteria) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "choice");
        def.put("instructions", instructions);
        def.put("criteria", new LinkedHashMap<>(criteria));
        return def;
    }

    /** 序数评分：levels 从低到高。 */
    public static Map<String, Object> score(String instructions, List<String> levels) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "score");
        def.put("instructions", instructions);
        def.put("criteria", List.copyOf(levels));
        return def;
    }

    /** 校准布尔概率：返回 P(true)。 */
    public static Map<String, Object> noul(String instructions) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "noul");
        def.put("instructions", instructions);
        return def;
    }
}