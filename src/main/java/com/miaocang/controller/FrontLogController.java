package com.miaocang.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端异常上报：页面上的 JS 报错 / 未捕获 Promise 异常自动 POST 到这里，
 * 打进服务日志，用于远程排查「图谱卡住」这类本地无法复现的前端故障。
 */
@RestController
@RequestMapping("/api")
public class FrontLogController {

    private static final Logger log = LoggerFactory.getLogger(FrontLogController.class);

    public record Report(String kind, String msg, String extra, String url) {}

    @PostMapping("/front-log")
    public void report(@RequestBody Report r) {
        log.warn("[前端异常][{}] {} @ {}{}", r.kind(), r.msg(), r.url(),
                r.extra() == null || r.extra().isBlank() ? "" : "\n" + r.extra());
    }
}
