package com.miaocang.service;

import com.miaocang.entity.LearnCard;
import com.miaocang.repository.LearnCardRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学透：学习卡片复习调度（简化 SM-2）。
 * 防反噬规则：每日到期卡 ≤ {@link #DAILY_LIMIT} 张封顶（超出自动顺延）；
 * 连续 3 次「记住」自动休眠（掌握，不再到期）；连续 3 次「忘记」降频为周检；
 * 「今日 3 分钟」轻入口 = limit 3（对抗一想到要复习 30 张就不打开）。
 */
@Service
public class LearnService {

    /** 每日复习上限（防「复习不动」焦虑源） */
    public static final int DAILY_LIMIT = 10;
    /** 「今日 3 分钟」轻入口张数 */
    public static final int LIGHT_LIMIT = 3;
    /** 连续 N 次忘记 → 降频周检；连续 N 次记住 → 休眠 */
    private static final int STREAK_LAPSE = 3;
    private static final int STREAK_PASS = 3;

    private final LearnCardRepository repo;

    public LearnService(LearnCardRepository repo) {
        this.repo = repo;
    }

    /** 今日到期卡流（≤ limit 张，先到期先复习）+ 学习统计 */
    public Map<String, Object> today(Long catId, Integer limit) {
        LocalDate today = LocalDate.now();
        List<LearnCard> due = repo.findByCatIdAndDormantFalseAndDueLessThanEqualOrderByDueAscIdAsc(catId, today);
        int cap = limit == null || limit <= 0 ? DAILY_LIMIT : Math.min(limit, DAILY_LIMIT);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (LearnCard c : due.subList(0, Math.min(cap, due.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("kind", c.getKind());
            m.put("front", c.getFront());
            m.put("back", c.getBack());
            m.put("bookTitle", c.getBookTitle());
            m.put("bookFile", c.getBookFile());
            m.put("understandingCardId", c.getUnderstandingCardId());
            m.put("due", c.getDue().toString());
            m.put("reviews", c.getReviews());
            cards.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("catId", catId);
        r.put("today", today.toString());
        r.put("dueTotal", due.size());
        r.put("shown", cards.size());
        r.put("capped", due.size() > cap);
        r.put("cards", cards);
        r.put("totalCount", repo.countByCatId(catId));
        r.put("masteredCount", repo.countByCatIdAndDormantTrue(catId));
        return r;
    }

    /** 自评复习：remember 记住 / vague 模糊 / forget 忘记 → 简化 SM-2 调度 */
    public Map<String, Object> review(Long catId, Long cardId, String grade) {
        LearnCard c = repo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("学习卡不存在: " + cardId));
        if (!c.getCatId().equals(catId)) throw new IllegalArgumentException("学习卡不属于这只喵");
        LocalDate today = LocalDate.now();
        String g = grade == null ? "" : grade.strip().toLowerCase();
        switch (g) {
            case "remember" -> {
                c.setInterval(c.getInterval() <= 0 ? 1 : Math.max(1, (int) Math.round(c.getInterval() * c.getEase())));
                c.setPassStreak(c.getPassStreak() + 1);
                c.setLapseStreak(0);
                if (c.getPassStreak() >= STREAK_PASS) c.setDormant(true);
                c.setDue(today.plusDays(c.getInterval()));
            }
            case "vague" -> {
                if (c.getInterval() <= 0) c.setInterval(1);
                c.setPassStreak(0);
                c.setLapseStreak(0);
                c.setDue(today.plusDays(c.getInterval()));
            }
            case "forget" -> {
                c.setInterval(1);
                c.setLapseStreak(c.getLapseStreak() + 1);
                c.setPassStreak(0);
                if (c.getLapseStreak() >= STREAK_LAPSE) c.setInterval(7); // 降频为周检
                c.setDue(today.plusDays(c.getInterval()));
            }
            default -> throw new IllegalArgumentException("grade 必须是 remember / vague / forget");
        }
        c.setReviews(c.getReviews() + 1);
        repo.save(c);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", c.getId());
        r.put("grade", g);
        r.put("interval", c.getInterval());
        r.put("due", c.getDue().toString());
        r.put("dormant", c.isDormant());
        r.put("masteredCount", repo.countByCatIdAndDormantTrue(catId));
        r.put("dueLeft", repo.findByCatIdAndDormantFalseAndDueLessThanEqualOrderByDueAscIdAsc(catId, today).size());
        return r;
    }
}
