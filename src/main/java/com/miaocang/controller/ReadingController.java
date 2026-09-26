package com.miaocang.controller;

import com.miaocang.service.MiaomiaoTraceService;
import com.miaocang.service.ReadingService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 喵的整理：把一只喵的知识分类分层成一本本二级主题书的大纲（md 落盘工作区） */
@RestController
@RequestMapping("/api")
public class ReadingController {

    private final ReadingService reading;
    private final MiaomiaoTraceService trace;
    private final com.miaocang.repository.CatRepository catRepo;

    public ReadingController(ReadingService reading, MiaomiaoTraceService trace,
                             com.miaocang.repository.CatRepository catRepo) {
        this.reading = reading;
        this.trace = trace;
        this.catRepo = catRepo;
    }

    /** 该猫的整理书架：已有大纲 + 素材概览 */
    @GetMapping("/cats/{catId}/reading")
    public Map<String, Object> list(@PathVariable Long catId) {
        return reading.list(catId);
    }

    /** 归纳前预估：本次将处理哪些文档（增量=未读过+有修改；全量=全部重处理），给主人确认弹窗展示 */
    @GetMapping("/cats/{catId}/reading/plan")
    public Map<String, Object> plan(@PathVariable Long catId) {
        return reading.plan(catId);
    }

    /** 喵喵足迹：这只喵在干什么（整理中/休息）、进度、token 消耗、人话事件流 */
    @GetMapping("/cats/{catId}/trace")
    public Map<String, Object> trace(@PathVariable Long catId) {
        Map<String, Object> snap = trace.snapshot(catId);
        catRepo.findById(catId).ifPresent(c -> snap.put("on", c.getAutoTidy()));
        return snap;
    }

    /** 切换这只喵的自动整理开关（关掉后巡逻不再自动触发，只能手动整理） */
    @PutMapping("/cats/{catId}/auto-tidy")
    public Map<String, Object> setAutoTidy(@PathVariable Long catId, @RequestBody Map<String, Object> body) {
        var cat = catRepo.findById(catId).orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        cat.setAutoTidy(Boolean.parseBoolean(String.valueOf(body.get("on"))));
        catRepo.save(cat);
        if (!cat.getAutoTidy()) trace.event(catId, "⏸", "主人关掉了自动整理，喵转入待命（可随时手动「立即整理」）");
        return Map.of("on", cat.getAutoTidy());
    }

    /** 启动整理：mode=auto（默认）增量归纳新内容归位进已有书；mode=full 手动全量重归纳（清空重建） */
    @PostMapping("/cats/{catId}/reading/generate")
    public ReadingService.ReadingTask generate(@PathVariable Long catId,
                                               @RequestParam(required = false) String mode) {
        return reading.start(catId, mode);
    }

    /** 任务进度轮询 */
    @GetMapping("/reading/tasks/{id}")
    public ReadingService.ReadingTask task(@PathVariable String id) {
        return reading.task(id);
    }

    /** 读单本书 md 原文 */
    @GetMapping("/cats/{catId}/reading/file")
    public Map<String, Object> file(@PathVariable Long catId, @RequestParam String name) {
        return reading.readFile(catId, name);
    }

    /** 学习中心「理解卡片」页签：全部理解卡片（读厚产物） */
    @GetMapping("/cats/{catId}/reading/cards")
    public java.util.List<Map<String, Object>> cards(@PathVariable Long catId) {
        return reading.listCards(catId);
    }

    /** 书架结构体检：分裂/合并建议（规则粗筛 + 模型细判），只提示，主人确认后才执行 */
    @GetMapping("/cats/{catId}/reading/suggestions")
    public java.util.List<Map<String, Object>> suggestions(@PathVariable Long catId) {
        return reading.suggestions(catId);
    }

    /** 合并两本书：body = {"files":["01-a.md","02-b.md"]} */
    @PostMapping("/cats/{catId}/reading/merge")
    public Map<String, Object> merge(@PathVariable Long catId,
                                     @RequestBody Map<String, java.util.List<String>> body) throws Exception {
        return reading.mergeBooks(catId, body.get("files"));
    }

    /** 分裂一本书：body = {"file":"01-x.md"} */
    @PostMapping("/cats/{catId}/reading/split")
    public Map<String, Object> split(@PathVariable Long catId,
                                     @RequestBody Map<String, String> body) throws Exception {
        return reading.splitBook(catId, body.get("file"));
    }
}
