package com.miaocang.controller;

import com.miaocang.entity.Cat;
import com.miaocang.repository.CatRepository;
import com.miaocang.auth.CurrentUser;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.ContentService;
import com.miaocang.service.ExtractService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

/** 内容采集入口：网页链接 / 纯文本 / Word / Excel（catId = 投放喵，谁投的算谁的） */
@RestController
@RequestMapping("/api/collect")
public class CollectController {

    private final ContentService contentService;
    private final CardAssembler cards;
    private final CatRepository catRepo;

    public CollectController(ContentService contentService, CardAssembler cards, CatRepository catRepo) {
        this.contentService = contentService;
        this.cards = cards;
        this.catRepo = catRepo;
    }

    /** 校验投放喵存在且属于当前登录用户；空则返回 null（走无上下文兼容路径） */
    private Long ownedCat(Long catId) {
        if (catId == null) return null;
        Cat cat = catRepo.findById(catId).orElseThrow(() -> new IllegalArgumentException("喵不存在: " + catId));
        if (!cat.getUserId().equals(CurrentUser.get().getId())) {
            throw new IllegalArgumentException("这不是你养的喵");
        }
        return catId;
    }

    @PostMapping("/url")
    public Map<String, Object> collectUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        Long catId = body.get("catId") == null || body.get("catId").isBlank() ? null : Long.valueOf(body.get("catId"));
        return cards.card(contentService.collectUrl(ownedCat(catId), url.trim()));
    }

    @PostMapping("/text")
    public Map<String, Object> collectText(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        Long catId = body.get("catId") == null || body.get("catId").isBlank() ? null : Long.valueOf(body.get("catId"));
        return cards.card(contentService.collectText(ownedCat(catId), body.get("title"), text));
    }

    @PostMapping("/file")
    public Map<String, Object> collectFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "catId", required = false) Long catId) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        ExtractService.MultipartFileLike like = new ExtractService.MultipartFileLike() {
            @Override public String originalName() {
                String name = file.getOriginalFilename();
                return name == null ? "unnamed" : name;
            }
            @Override public String contentType() { return file.getContentType(); }
            @Override public InputStream inputStream() throws Exception { return file.getInputStream(); }
        };
        return cards.card(contentService.collectFile(ownedCat(catId), like));
    }
}
