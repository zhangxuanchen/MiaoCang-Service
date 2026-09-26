package com.miaocang.controller;

import com.miaocang.auth.CurrentUser;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.TypeAttachRepository;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.LibrarySyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端喵文件互通：与喵藏客户端（macOS 桌面猫）的单喵书库（平铺分类目录 + 内容 md）双向同步。
 *
 * 协议（全部走登录态 Bearer，手动校验猫归属——/api/client/** 不在拦截器 CAT_PATH 覆盖内）：
 * - GET  /manifest        该喵工作区内容 md 清单 [{path, sha256, bytes, modifiedAt}]，客户端 diff 本地挑差异；
 *                         两边都有但 sha 不同 = 冲突（客户端列清单让用户选覆盖方向），仅一边 = 上传/下载
 * - GET  /file?path=      拉取单个文件原文
 * - PUT  /file            上传单个文件（越界即拒）
 * - POST /apply           单喵收编（rescan 式：补录新文件、剔除消失文件）+ git 提交
 * - GET  /types           类目清单（共享 + 该喵自有，带 keywords/attached）——客户端只读，不可改类目
 *
 * 同步范围仅内容文章 md；INDEX.md / .catalog.json 两边各自再生，不同步。
 */
@RestController
@RequestMapping("/api/client/cats/{catId}/sync")
public class ClientSyncController {

    private final LibrarySyncService librarySync;
    private final CatRepository catRepo;
    private final BookTypeRepository typeRepo;
    private final TypeAttachRepository attachRepo;
    private final CardAssembler cards;
    private final ContentItemRepository contentRepo;
    private final BookRepository bookRepo;

    public ClientSyncController(LibrarySyncService librarySync, CatRepository catRepo,
                                BookTypeRepository typeRepo, TypeAttachRepository attachRepo,
                                CardAssembler cards, ContentItemRepository contentRepo,
                                BookRepository bookRepo) {
        this.librarySync = librarySync;
        this.catRepo = catRepo;
        this.typeRepo = typeRepo;
        this.attachRepo = attachRepo;
        this.cards = cards;
        this.contentRepo = contentRepo;
        this.bookRepo = bookRepo;
    }

    /** 猫存在且属于当前登录用户（/api/client/** 不走拦截器的 CAT_PATH 校验，这里手动兜住） */
    private Cat requireOwnedCat(Long catId) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "猫不存在"));
        if (!cat.getUserId().equals(CurrentUser.get().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "这不是你养的喵");
        }
        return cat;
    }

    /** 该喵工作区的内容 md 清单（path 相对工作区，客户端拿它与本地清单对齐 diff） */
    @GetMapping("/manifest")
    public List<Map<String, Object>> manifest(@PathVariable Long catId) {
        return librarySync.manifest(requireOwnedCat(catId));
    }

    /** 拉取单个内容文件原文 */
    @GetMapping("/file")
    public ResponseEntity<String> file(@PathVariable Long catId, @RequestParam String path) {
        Cat cat = requireOwnedCat(catId);
        if (!librarySync.isLegalContentPath(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法路径: " + path);
        }
        Path p = librarySync.resolveWorkspace(cat.getWorkspacePath()).resolve(path).normalize();
        if (!Files.exists(p)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + path);
        }
        try {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取失败: " + e.getMessage());
        }
    }

    /** 图片资产单文件上限：与 file-b64 预览的 20MB 上限对齐 */
    private static final int ASSET_MAX_BYTES = 20 * 1024 * 1024;

    /** 上传单个内容文件（覆盖 = 客户端侧冲突裁决「用本地覆盖远程」的落点）；
     *  带 base64 字段时为图片资产上传（剪藏 _images/，二进制按 base64 解码落盘） */
    @PutMapping("/file")
    public Map<String, Object> putFile(@PathVariable Long catId, @RequestBody Map<String, String> body) {
        Cat cat = requireOwnedCat(catId);
        String b64 = body.get("base64");
        if (b64 != null && !b64.isBlank()) {
            return putAsset(cat, body.get("path"), b64);
        }
        /* 两级 <分类>/<文件>.md 归位到 origin/ 下（客户端同步的原始文档统一住工作区 origin/） */
        String path = LibrarySyncService.toOriginPath(body.get("path"));
        String content = body.get("content");
        if (path == null || content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 path 与 content");
        }
        if (!librarySync.isLegalContentPath(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法路径: " + path);
        }
        try {
            librarySync.writeArtifact(cat.getWorkspacePath(), path, content);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "写入失败: " + e.getMessage());
        }
        return Map.of("ok", true);
    }

    /**
     * 图片资产上传：path 支持 {@code _images/…}（md 在工作区根的历史形式）、
     * {@code <分类>/_images/…}（自动归位 origin/，与 md 的 toOriginPath 归位一致——
     * md 落在 origin/<分类>/ 下，其相对引用的 _images 也在同目录）、{@code origin/<分类>/_images/…} 原样。
     */
    private Map<String, Object> putAsset(Cat cat, String rawPath, String b64) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 path");
        }
        String path = rawPath.strip();
        int slash = path.indexOf('/');
        if (slash > 0 && !"origin".equals(path.substring(0, slash))
                && path.substring(slash + 1).startsWith("_images/")) {
            path = "origin/" + path;
        }
        if (!librarySync.isLegalAssetPath(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法图片路径: " + rawPath);
        }
        byte[] data;
        try {
            data = java.util.Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base64 解码失败");
        }
        if (data.length == 0 || data.length > ASSET_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片大小需在 1B ~ 20MB 之间");
        }
        try {
            librarySync.writeArtifactBinary(cat.getWorkspacePath(), path, data);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "写入失败: " + e.getMessage());
        }
        return Map.of("ok", true, "path", path, "size", data.length);
    }

    /** 单喵收编：客户端上传完差异文件后调用——补录/剔除 + 重建该喵工作区索引 + git 提交 */
    @PostMapping("/apply")
    @Transactional
    public Map<String, Object> apply(@PathVariable Long catId) {
        Cat cat = requireOwnedCat(catId);
        Map<String, Object> result = librarySync.applyForCat(cat);
        result.put("committed", librarySync.commit("客户端同步: " + cat.getName()));
        return result;
    }

    /** 类目清单：该喵自有（preset=false）+ 共享 preset=true（keywords 供客户端分类词表，attached 标记挂接态）——客户端只读 */
    @GetMapping("/types")
    public List<Map<String, Object>> types(@PathVariable Long catId) {
        Cat cat = requireOwnedCat(catId);
        List<Map<String, Object>> out = new ArrayList<>();
        // 该喵专属分类（preset=false 才算自有；preset=true 虽 cat_id 可能非空但属全局共享，走 shared 分支输出一次）
        for (BookType t : typeRepo.findByCatIdAndPresetFalseOrderByIdAsc(cat.getId())) {
            out.add(clientTypeCard(t, cat, false));
        }
        for (BookType t : typeRepo.findByPresetTrueOrderByIdAsc()) {
            out.add(clientTypeCard(t, cat, true));
        }
        return out;
    }

    /**
     * 轻量内容元信息列表（不含正文）：客户端文件管理页/列表 UI 用，避免拉全量正文。
     * path 为工作区相对路径（与 manifest/pull 一致，可直接用于 /file 与 /push）；
     * status：PENDING=收集箱待裁决、CLASSIFIED=已归档；文件缺失时 missing=true。
     */
    @GetMapping("/contents")
    public List<Map<String, Object>> contents(@PathVariable Long catId) {
        Cat cat = requireOwnedCat(catId);
        String ws = cat.getWorkspacePath();
        Path wsRoot = librarySync.resolveWorkspace(ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem it : contentRepo.findByCatId(catId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("title", it.getTitle());
            m.put("status", it.getStatus());
            m.put("contentType", it.getContentType());
            if (it.getBookId() != null) {
                bookRepo.findById(it.getBookId()).map(Book::getTitle).ifPresent(b -> m.put("bookTitle", b));
            }
            if (it.getLibraryPath() != null && !it.getLibraryPath().isBlank()) {
                String rel = LibrarySyncService.stripWsPrefix(it.getLibraryPath(), ws);
                m.put("path", rel);
                Path p = wsRoot.resolve(rel).normalize();
                if (Files.exists(p)) {
                    try {
                        m.put("sha256", LibrarySyncService.sha256Hex(p));
                        m.put("bytes", Files.size(p));
                        m.put("modifiedAt", Files.getLastModifiedTime(p).toInstant().toString());
                    } catch (Exception ignored) { m.put("missing", true); }
                } else {
                    m.put("missing", true);
                }
            }
            m.put("createdAt", it.getCreatedAt() == null ? null : it.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    // ================= 客户端聚合接口：pull / push =================

    /**
     * 内容拉取：全量内容文件（含正文）+ 与客户端基准的 diff + 冲突标记 + 类目清单。
     * body 可空；{"base": {"<path>": "<上次同步记下的sha256>"}} 时输出 added/changed/removed/conflicts，
     * changed = 两边都有但内容不同 → 冲突，客户端让用户修正后走 /push 提交。
     */
    @PostMapping("/pull")
    public Map<String, Object> pull(@PathVariable Long catId, @RequestBody(required = false) Map<String, Object> body) {
        Cat cat = requireOwnedCat(catId);
        @SuppressWarnings("unchecked")
        Map<String, String> base = body == null || body.get("base") == null
                ? null : (Map<String, String>) body.get("base");
        Map<String, Object> out = librarySync.pullForCat(cat, base);
        out.put("types", types(catId)); /* 分类一并下发：共享 + 本喵专属，客户端只读 */
        return out;
    }

    /**
     * 内容提交：files = [{path, content, baseSha?}]。冲突闸：服务端已有该文件且 baseSha 对不上当前版本
     * （含未带 baseSha）→ 不覆盖，返回 serverSha256 + serverContent，客户端修正后重新提交；
     * 接受的文件落盘 → 自动收编入库 → git 提交，一次请求完成同步闭环。
     */
    @PostMapping("/push")
    @Transactional
    public Map<String, Object> push(@PathVariable Long catId, @RequestBody Map<String, Object> body) {
        Cat cat = requireOwnedCat(catId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        if (files == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 files 数组");
        }
        Map<String, Object> out = librarySync.pushForCat(cat, files);
        /* 收编入库：仅在有文件被接受时执行（纯冲突/拒绝轮不动库） */
        if (!((List<?>) out.get("accepted")).isEmpty()) {
            out.put("apply", librarySync.applyForCat(cat));
            out.put("committed", librarySync.commit("客户端提交: " + cat.getName()));
        }
        return out;
    }

    private Map<String, Object> clientTypeCard(BookType t, Cat cat, boolean shared) {
        Map<String, Object> m = cards.typeCard(t, 0, 0);
        m.put("shared", shared);
        m.put("attached", shared ? attachRepo.existsByTypeIdAndCatId(t.getId(), cat.getId()) : false);
        m.put("keywords", t.getGravityKeywords() == null || t.getGravityKeywords().isBlank()
                ? List.of() : List.of(t.getGravityKeywords().split(",")));
        return m;
    }
}
