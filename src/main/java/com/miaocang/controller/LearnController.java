package com.miaocang.controller;

import com.miaocang.service.LearnService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 学透：学习卡片复习（今日到期卡流 + 自评 SM-2 调度） */
@RestController
@RequestMapping("/api")
public class LearnController {

    private final LearnService learn;

    public LearnController(LearnService learn) {
        this.learn = learn;
    }

    /** 今日到期卡流（默认 ≤10 张封顶；?limit=3 即「今日 3 分钟」轻入口） */
    @GetMapping("/cats/{catId}/learn/today")
    public Map<String, Object> today(@PathVariable Long catId,
                                     @RequestParam(required = false) Integer limit) {
        return learn.today(catId, limit);
    }

    /** 自评复习：grade = remember（记住）/ vague（模糊）/ forget（忘记） */
    @PostMapping("/cats/{catId}/learn/cards/{cardId}/review")
    public Map<String, Object> review(@PathVariable Long catId, @PathVariable Long cardId,
                                      @RequestParam String grade) {
        return learn.review(catId, cardId, grade);
    }
}
