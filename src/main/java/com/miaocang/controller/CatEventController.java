package com.miaocang.controller;

import com.miaocang.service.CatEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 喵事件 API（路径协议照抄 Memory-Observatory /api/v1）。
 *
 * <p>写：
 * <ul>
 *   <li>POST /api/v1/events —— 单条事件上报（字段与原协议 CreateEventDTO 对齐，camelCase）</li>
 * </ul>
 * 读（喵的日记三页面）：
 * <ul>
 *   <li>GET /api/v1/agents —— Agent 列表（= 各工作区，附猫信息）</li>
 *   <li>GET /api/v1/agents/{agentId}/events —— 事件列表（分页 + 多维筛选）</li>
 *   <li>GET /api/v1/agents/{agentId}/events-stats —— 筛选范围统计（总数/会话/Token）</li>
 *   <li>GET /api/v1/events/{eventId} —— 事件详情（含同 Turn 的 action 子事件）</li>
 *   <li>GET /api/v1/agents/{agentId}/turns —— Turn 日记流（按轮聚合）</li>
 *   <li>GET /api/v1/agents/{agentId}/token-stats —— Token 分层/分操作/趋势</li>
 *   <li>GET /api/v1/agents/{agentId}/token-analytics —— Token 多维分析</li>
 *   <li>GET /api/v1/analytics/problems —— 问题分析（阈值聚合）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class CatEventController {

    private final CatEventService svc;

    public CatEventController(CatEventService svc) {
        this.svc = svc;
    }

    /** 单条事件上报（原协议）：agentId 必填，其余缺省补齐。 */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> createEvent(@RequestBody Map<String, Object> dto) {
        try {
            var e = svc.report(dto);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("eventId", e.getEventId());
            result.put("agentId", e.getAgentId());
            result.put("traceId", e.getTraceId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Agent 列表（附猫信息）。 */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        return Map.of("agents", svc.agents());
    }

    /** 事件列表（喵喵记录 · 事件表）。agentId 是工作区路径（admin/cat-1），URL 里 / 用 ~ 代替，这里还原。 */
    @GetMapping("/agents/{agentId}/events")
    public Map<String, Object> events(@PathVariable String agentId,
                                      @RequestParam(required = false) String sessionId,
                                      @RequestParam(required = false) String eventId,
                                      @RequestParam(required = false) String op,
                                      @RequestParam(required = false) String layer,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return svc.events(decAgent(agentId), sessionId, eventId, op, layer, keyword, parseInstant(from), parseInstant(to), limit, offset);
    }

    /** 筛选范围统计（喵喵记录 KPI）。 */
    @GetMapping("/agents/{agentId}/events-stats")
    public Map<String, Object> eventStats(@PathVariable String agentId,
                                          @RequestParam(required = false) String sessionId,
                                          @RequestParam(required = false) String op,
                                          @RequestParam(required = false) String layer,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        Map<String, Object> result = new LinkedHashMap<>();
        agentId = decAgent(agentId);
        result.put("agentId", agentId);
        result.putAll(svc.eventStats(agentId, sessionId, null, op, layer, keyword, parseInstant(from), parseInstant(to)));
        return result;
    }

    /** 事件详情抽屉（含 action 子事件）。 */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> eventDetail(@PathVariable String eventId) {
        Map<String, Object> event = svc.eventDetail(eventId);
        return event == null ? ResponseEntity.status(404).body(Map.of("error", "event not found")) : ResponseEntity.ok(event);
    }

    /** Turn 日记流（喵喵记录 · 日记卡片）。 */
    @GetMapping("/agents/{agentId}/turns")
    public Map<String, Object> turns(@PathVariable String agentId,
                                     @RequestParam(required = false) String sessionId,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "30") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return svc.turns(decAgent(agentId), sessionId, q, limit, offset);
    }

    /** Token 统计（喵喵消耗 · 基础图表）。 */
    @GetMapping("/agents/{agentId}/token-stats")
    public Map<String, Object> tokenStats(@PathVariable String agentId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return svc.tokenStats(decAgent(agentId), parseInstant(from), parseInstant(to));
    }

    /** Token 多维分析（喵喵消耗 · Top 会话/小时分布/异常 key）。 */
    @GetMapping("/agents/{agentId}/token-analytics")
    public Map<String, Object> tokenAnalytics(@PathVariable String agentId,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return svc.tokenAnalytics(decAgent(agentId), parseInstant(from), parseInstant(to));
    }

    /** 问题分析（喵喵问题 · 阈值聚合）。 */
    @GetMapping("/analytics/problems")
    public Map<String, Object> problems(@RequestParam(defaultValue = "7") int days,
                                        @RequestParam(required = false) String agent) {
        return svc.problems(days, agent);
    }

    /** URL 路径里的 agentId：工作区路径中的 / 前端已替换为 ~（PathPattern 单段无法匹配斜杠），这里还原第一个。 */
    private static String decAgent(String agentId) {
        return agentId == null ? null : agentId.replaceFirst("~", "/");
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
