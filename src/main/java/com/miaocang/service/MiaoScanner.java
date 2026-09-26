package com.miaocang.service;

import com.miaocang.repository.CatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 喵喵巡逻：每分钟扫一遍每只喵的书库——
 * 用内容指纹（SHA-256，比 MD5 更稳）判断文章有没有新增/修改；
 * 有变化且喵空闲 → 自动触发增量整理（归纳 + 书架自动合并/分裂）；
 * 正在整理 → 本轮跳过，不重复触发。
 */
@Component
public class MiaoScanner {

    private static final Logger log = LoggerFactory.getLogger(MiaoScanner.class);

    private final CatRepository catRepo;
    private final ReadingService reading;
    private final MiaomiaoTraceService trace;

    public MiaoScanner(CatRepository catRepo, ReadingService reading, MiaomiaoTraceService trace) {
        this.catRepo = catRepo;
        this.reading = reading;
        this.trace = trace;
    }

    /** 每小时巡逻一轮（可在前端关掉某只喵的自动整理，改为手动触发） */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 20_000)
    public void patrol() {
        for (var cat : catRepo.findAll()) {
            if (!cat.getAutoTidy()) continue; /* 主人关掉了这只喵的自动整理 */
            try {
                patrolCat(cat.getId(), cat.getName());
            } catch (Exception e) {
                log.warn("[喵喵巡逻] {} 巡逻失败: {}", cat.getName(), e.getMessage());
            }
        }
    }

    private void patrolCat(Long catId, String name) {
        Map<String, Object> snap = trace.snapshot(catId);
        if (Boolean.TRUE.equals(snap.get("working"))) {
            return; /* 正在整理：不打扰、不记日志刷屏 */
        }
        Map<String, Object> plan = reading.plan(catId);
        int fresh = num(plan.get("freshCount"));
        int updated = num(plan.get("updatedCount"));
        int total = num(plan.get("total"));
        boolean chatChanged = Boolean.TRUE.equals(plan.get("chatChanged"));
        if (fresh == 0 && updated == 0 && !chatChanged) {
            trace.resting(catId, "上次巡逻 " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    + " · " + total + " 篇文章无变化 · 零消耗");
            return;
        }
        trace.event(catId, "🔍", "发现变化：新增 " + fresh + " 篇 · 有修改 " + updated + " 篇"
                + (chatChanged ? " · 会话有新内容" : "") + "，开始整理");
        try {
            reading.start(catId, "auto");
            log.info("[喵喵巡逻] {}: 触发自动整理（新增 {} / 修改 {}）", name, fresh, updated);
        } catch (Exception e) {
            trace.event(catId, "⚠️", "整理启动失败：" + e.getMessage());
            trace.resting(catId, "整理启动失败，下轮再试");
        }
    }

    private int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
