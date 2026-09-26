package com.miaocang.controller;

import com.miaocang.auth.CurrentUser;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.User;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.ExtractPipeline;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 猫管理：侧栏的宠物名单，每只猫照看自己的知识分类 */
@RestController
@RequestMapping("/api/cats")
public class CatController {

    private final CatRepository catRepo;
    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final ContentItemRepository contentRepo;
    private final CardAssembler cards;
    private final ExtractPipeline pipeline;
    private final com.miaocang.chat.CatWorkspaceService catWorkspace;

    public CatController(CatRepository catRepo, BookTypeRepository typeRepo, BookRepository bookRepo,
                         ContentItemRepository contentRepo, CardAssembler cards, ExtractPipeline pipeline,
                         com.miaocang.chat.CatWorkspaceService catWorkspace) {
        this.catRepo = catRepo;
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.contentRepo = contentRepo;
        this.cards = cards;
        this.pipeline = pipeline;
        this.catWorkspace = catWorkspace;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Long userId = CurrentUser.get().getId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Cat c : catRepo.findByUserIdOrderByOrderIndexAscIdAsc(userId)) {
            Map<String, Object> m = cards.catCard(c);
            m.put("typeCount", typeRepo.findByCatIdOrderByIdAsc(c.getId()).size());
            list.add(m);
        }
        return list;
    }

    /** 最多同时养的猫数（书房容纳有限，超过请先送养） */
    private static final int MAX_CATS = 5;

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        User user = CurrentUser.get();
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("猫名不能为空");
        if (catRepo.countByUserId(user.getId()) >= MAX_CATS) {
            throw new IllegalArgumentException("书房最多养 " + MAX_CATS + " 只喵，想再领养请先送养一只");
        }
        if (catRepo.findByUserIdAndName(user.getId(), name.trim()).isPresent()) {
            throw new IllegalArgumentException("已经养了一只叫「" + name.trim() + "」的猫");
        }
        Cat cat = new Cat();
        cat.setName(name.trim());
        cat.setUserId(user.getId());
        apply(cat, body);
        cat.setOrderIndex((int) catRepo.countByUserId(user.getId()));
        catRepo.save(cat);
        // 工作区路径锚点 = "<用户名>/cat-{id}"（一旦生成永不改变，作为会话区/记忆状态隔离的稳定键；
        // 每个用户一个专属根目录，其子目录是该用户养的喵）
        cat.setWorkspacePath(user.getUsername() + "/cat-" + cat.getId());
        catRepo.save(cat);
        pipeline.seedPresetsForCat(cat.getId());
        return cards.catCard(cat);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Cat cat = catRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("猫不存在"));
        String name = (String) body.get("name");
        if (name != null && !name.isBlank()) {
            catRepo.findByUserIdAndName(cat.getUserId(), name.trim())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new IllegalArgumentException("已经养了一只叫「" + name.trim() + "」的猫"); });
            cat.setName(name.trim());
        }
        apply(cat, body);
        catRepo.save(cat);
        return cards.catCard(cat);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        Cat cat = catRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("猫不存在"));
        if (typeRepo.findByCatIdOrderByIdAsc(id).size() > 0) {
            throw new IllegalArgumentException("这只猫还照看着知识分类，请先把分类移交给别的猫");
        }
        catRepo.delete(cat);
        return Map.of("ok", true);
    }

    /** 知识图谱内容叶子：这只猫的书（含共享预设分类的书）下该喵名下的内容（轻量清单，按书挂叶子节点用） */
    @GetMapping("/{catId}/graph-contents")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> graphContents(@PathVariable Long catId) {
        /* 预设分类不分喵：可见的书 = 自有分类的书 + 全部共享预设分类的书，内容按归属喵过滤 */
        List<Long> typeIds = new ArrayList<>();
        for (var t : typeRepo.findByCatIdOrderByIdAsc(catId)) typeIds.add(t.getId());
        for (var t : typeRepo.findByPresetTrueOrderByIdAsc()) {
            if (!typeIds.contains(t.getId())) typeIds.add(t.getId());
        }
        List<Long> bookIds = new ArrayList<>();
        for (Long tid : typeIds) {
            bookRepo.findByTypeIdOrderByIdAsc(tid).forEach(b -> bookIds.add(b.getId()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem c : contentRepo.findByBookIdIn(bookIds)) {
            if (!catId.equals(c.getCatId())) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("summary", c.getSummary());
            m.put("bookId", c.getBookId());
            m.put("contentType", c.getContentType());
            m.put("matchedTags", c.getMatchedTags());
            m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /**
     * 知识图谱的 origin 实时结构：每次现扫文件系统，图谱随增删文件即时变化。
     * 一级类目 = origin/ 下的顶层文件夹；内容（书）= 文件夹里的文件；
     * origin/ 根下的散文件 = 猫直接关联的内容（folder 为空串的一组）。
     * 非递归（只到文件夹第一层文件），跳过 . 开头的内部项；path 为相对该猫工作区的路径，可直接用于文件读取/删除接口。
     */
    @GetMapping("/{catId}/graph-fs")
    public List<Map<String, Object>> graphFs(@PathVariable Long catId) {
        Cat cat = catRepo.findById(catId).orElseThrow();
        List<Map<String, Object>> out = new ArrayList<>();
        java.nio.file.Path origin = catWorkspace.workspaceRoot(cat.getWorkspacePath())
                .resolve("origin").normalize();
        if (!java.nio.file.Files.isDirectory(origin)) return out;
        List<Map<String, Object>> loose = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(origin)) {
            List<java.nio.file.Path> entries = st
                    .sorted(java.util.Comparator.comparing((java.nio.file.Path p) ->
                            java.nio.file.Files.isDirectory(p) ? 0 : 1).thenComparing(p -> p.getFileName().toString()))
                    .toList();
            for (java.nio.file.Path p : entries) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (java.nio.file.Files.isDirectory(p)) {
                    List<Map<String, Object>> files = listOriginFiles(p, "origin/" + name);
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("folder", name);
                    m.put("files", files);
                    out.add(m);
                } else {
                    loose.add(fileEntry(p, "origin/" + name));
                }
            }
        } catch (Exception e) {
            /* 扫描失败返回已有部分：图谱不应因文件系统异常白屏 */
        }
        if (!loose.isEmpty()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("folder", "");
            m.put("files", loose);
            out.add(m);
        }
        return out;
    }

    /** 列出目录第一层的文件条目（跳过 . 开头与子目录，不递归），按文件名排序 */
    private List<Map<String, Object>> listOriginFiles(java.nio.file.Path dir, String relPrefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
            List<java.nio.file.Path> files = st
                    .filter((java.nio.file.Path p) -> java.nio.file.Files.isRegularFile(p))
                    .filter((java.nio.file.Path p) -> !p.getFileName().toString().startsWith("."))
                    .sorted(java.util.Comparator.comparing((java.nio.file.Path p) -> p.getFileName().toString()))
                    .toList();
            for (java.nio.file.Path f : files) {
                out.add(fileEntry(f, relPrefix + "/" + f.getFileName().toString()));
            }
        } catch (Exception e) {
            /* 目录不可读则视为空 */
        }
        return out;
    }

    /** 文件条目：相对工作区路径 + 展示名 + 大小 + 修改时间 */
    private Map<String, Object> fileEntry(java.nio.file.Path p, String relPath) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("path", relPath);
        m.put("name", p.getFileName().toString());
        try {
            m.put("size", java.nio.file.Files.size(p));
            m.put("lastModified", java.nio.file.Files.getLastModifiedTime(p).toInstant().toString());
        } catch (Exception e) {
            m.put("size", 0);
            m.put("lastModified", null);
        }
        return m;
    }

    private void apply(Cat cat, Map<String, Object> body) {
        if (body.containsKey("icon")) cat.setIcon((String) body.get("icon"));
        if (body.containsKey("color")) cat.setColor((String) body.get("color"));
        if (body.containsKey("description")) cat.setDescription((String) body.get("description"));
    }
}
