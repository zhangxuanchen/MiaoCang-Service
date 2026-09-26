package com.miaocang.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 喵喵足迹：每只喵的工作动态，类似 log 但用人话写——
 * 「发现了什么变化 / 正在整理哪一步 / 整理出了什么 / 消耗多少 token / 现在是在整理还是休息」。
 * 内存态（重启清零），每只喵最多保留 200 条事件。
 */
@Service
public class MiaomiaoTraceService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_EVENTS = 200;

    /** 一条足迹事件 */
    public record TraceEvent(String time, String icon, String text) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", time);
            m.put("icon", icon);
            m.put("text", text);
            return m;
        }
    }

    /** 每只喵的运行态 */
    private static class CatTrace {
        volatile boolean working = false;          /* true=整理中 false=休息中 */
        volatile String phase = "";                /* read/thin/enrich/learn/tidy */
        volatile int stageDone;
        volatile int stageTotal;
        volatile String detail = "";               /* 人话进度 */
        volatile long lastScanAt;                  /* 上次巡逻时间戳 */
        final AtomicLong tokens = new AtomicLong(); /* 本轮整理累计 token */
        final Deque<TraceEvent> events = new ArrayDeque<>();
    }

    private final Map<Long, CatTrace> cats = new ConcurrentHashMap<>();

    private CatTrace of(Long catId) {
        return cats.computeIfAbsent(catId, k -> new CatTrace());
    }

    /** 记一条事件（人话），同时不打断当前状态 */
    public void event(Long catId, String icon, String text) {
        CatTrace t = of(catId);
        synchronized (t.events) {
            t.events.addFirst(new TraceEvent(LocalTime.now().format(HHMM), icon, text));
            while (t.events.size() > MAX_EVENTS) t.events.removeLast();
        }
    }

    /** 整理中：镜像阅读任务的实时进度 */
    public void working(Long catId, String phase, int done, int total, String detail) {
        CatTrace t = of(catId);
        t.working = true;
        t.phase = phase == null ? "" : phase;
        t.stageDone = done;
        t.stageTotal = total;
        t.detail = detail == null ? "" : detail;
    }

    /** 休息中：定时巡逻没发现变化 */
    public void resting(Long catId, String lastScanNote) {
        CatTrace t = of(catId);
        t.working = false;
        t.phase = "";
        t.stageDone = 0;
        t.stageTotal = 0;
        t.detail = lastScanNote == null ? "" : lastScanNote;
        t.lastScanAt = System.currentTimeMillis();
    }

    /** 累计 token（模型调用回调写入） */
    public void addToken(Long catId, long total) {
        if (total > 0) of(catId).tokens.addAndGet(total);
    }

    /** 当前累计 token（供阶段记账：阶段结束减去阶段开始的值 = 本阶段消耗） */
    public long currentTokens(Long catId) {
        return of(catId).tokens.get();
    }

    /** 开始一轮整理：token 计数清零 */
    public void beginWork(Long catId, String icon, String text) {
        CatTrace t = of(catId);
        t.tokens.set(0);
        t.working = true;
        event(catId, icon, text);
    }

    /** 结束整理：恢复休息并写总结事件 */
    public void endWork(Long catId, String icon, String text) {
        CatTrace t = of(catId);
        t.working = false;
        t.phase = "";
        event(catId, icon, text + "（消耗 " + fmtTokens(t.tokens.get()) + " tokens）");
        t.tokens.set(0);
    }

    /** 前端快照：状态 + 进度 + 事件流（新的在前） */
    public Map<String, Object> snapshot(Long catId) {
        CatTrace t = of(catId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("working", t.working);
        m.put("phase", t.phase);
        m.put("stageDone", t.stageDone);
        m.put("stageTotal", t.stageTotal);
        m.put("detail", t.detail);
        m.put("tokens", t.tokens.get());
        m.put("lastScanAt", t.lastScanAt);
        List<Map<String, Object>> evs = new ArrayList<>();
        synchronized (t.events) {
            for (TraceEvent e : t.events) evs.add(e.toMap());
        }
        m.put("events", evs);
        return m;
    }

    private String fmtTokens(long n) {
        if (n >= 10000) return String.format("%.1fk", n / 1000.0);
        return String.valueOf(n);
    }
}
