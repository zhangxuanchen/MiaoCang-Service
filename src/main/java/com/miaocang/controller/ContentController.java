package com.miaocang.controller;

import com.miaocang.entity.ContentItem;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.ContentService;
import com.miaocang.service.LibrarySyncService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 内容操作：收集箱 / 详情 / 移动 / 重分类 / 删除 / 下载 / 搜索 */
@RestController
@RequestMapping("/api")
public class ContentController {

    private final ContentItemRepository contentRepo;
    private final ContentService contentService;
    private final CardAssembler cards;
    private final BookRepository bookRepo;
    private final BookTypeRepository typeRepo;
    private final CatRepository catRepo;
    private final com.miaocang.service.ReadingService reading;

    public ContentController(ContentItemRepository contentRepo, ContentService contentService, CardAssembler cards,
                             BookRepository bookRepo, BookTypeRepository typeRepo, CatRepository catRepo,
                             com.miaocang.service.ReadingService reading) {
        this.contentRepo = contentRepo;
        this.contentService = contentService;
        this.cards = cards;
        this.bookRepo = bookRepo;
        this.typeRepo = typeRepo;
        this.catRepo = catRepo;
        this.reading = reading;
    }

    /** 内容归属猫：优先内容自身 catId（谁归档算谁的）；旧数据沿 book→type→cat，收集箱待归档沿引力建议反查 */
    private Long resolveCatId(ContentItem c) {
        if (c.getCatId() != null) return c.getCatId();
        Long typeId = null;
        if (c.getBookId() != null) {
            typeId = bookRepo.findById(c.getBookId())
                    .map(com.miaocang.entity.Book::getTypeId).orElse(null);
        }
        if (typeId == null) {
            typeId = cards.suggestions(c).stream()
                    .filter(s -> s.get("typeId") != null)
                    .map(s -> ((Number) s.get("typeId")).longValue())
                    .findFirst().orElse(null);
        }
        return typeId == null ? null : typeRepo.findById(typeId)
                .map(com.miaocang.entity.BookType::getCatId).orElse(null);
    }

    /** 收集箱：引力未达标的内容，附带类型建议；catId 可选，按猫维度过滤（暂无法判定归属的内容在所有猫下可见） */
    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox(@RequestParam(required = false) Long catId) {
        return contentRepo.findByStatusOrderByCreatedAtDesc(ContentItem.STATUS_PENDING)
                .stream()
                .filter(c -> catId == null || notOwned(c) || catId.equals(resolveCatId(c)))
                .map(cards::card).collect(Collectors.toList());
    }

    /** 内容详情（含完整正文与引力打分明细） */
    @GetMapping("/contents/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        ContentItem c = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在"));
        Map<String, Object> m = new LinkedHashMap<>(cards.card(c));
        m.put("rawText", c.getRawText());
        /* 正文在书库里的落点（工作区相对路径 + 归属猫）：正文里的相对路径图片要靠它定位。
           书库文件在磁盘上是 <user>/cat-N/origin/…，浏览器按 SPA 根解析必然 404，
           前端拿这两个字段走 /api/cats/{catId}/chat/file-b64 取图转 data URL 回填。 */
        Long catId = resolveCatId(c);
        m.put("wsCatId", catId);
        m.put("wsPath", wsRelPath(c, catId));
        /* 喵喵整理状态：指纹判定这篇文章被整理管线读过没有 + 反追踪出的理解卡片（书本详情弹窗的「喵喵提取」区用） */
        m.put("tidy", reading.tidyState(List.of(c), catId));
        /* 有无源文件（上传的原始文件还在磁盘上）：决定弹窗底部显示「下载源文件」还是「导出 MD」 */
        m.put("hasRawFile", c.getStoredPath() != null && !c.getStoredPath().isBlank()
                && java.nio.file.Files.exists(java.nio.file.Paths.get(c.getStoredPath())));
        return m;
    }

    /** libraryPath（带 <user>/cat-N 前缀）→ 该猫工作区的相对路径；没有书库落点时返回 null */
    private String wsRelPath(ContentItem c, Long catId) {
        if (catId == null || c.getLibraryPath() == null || c.getLibraryPath().isBlank()) return null;
        return catRepo.findById(catId)
                .map(cat -> LibrarySyncService.stripWsPrefix(c.getLibraryPath(), cat.getWorkspacePath()))
                .orElse(null);
    }

    /** 手动移动（bookId 为空 = 移回收集箱）；catId = 归档喵上下文（谁归档算谁的） */
    @PutMapping("/contents/{id}/move")
    public Map<String, Object> move(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Number bookId = (Number) body.get("bookId");
        Number catalogNodeId = (Number) body.get("catalogNodeId");
        Number catId = (Number) body.get("catId");
        return cards.card(contentService.move(id,
                bookId == null ? null : bookId.longValue(),
                catalogNodeId == null ? null : catalogNodeId.longValue(),
                catId == null ? null : catId.longValue()));
    }

    /** 编辑标题/摘要（同步重写书库文件） */
    @PutMapping("/contents/{id}/edit")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        String summary = body.get("summary") == null ? null : String.valueOf(body.get("summary"));
        return cards.card(contentService.edit(id, title, summary));
    }

    /** 按收集箱建议归档到指定类型的默认书；catId = 归档喵上下文（谁归档算谁的） */
    @PostMapping("/contents/{id}/confirm-suggestion")
    public Map<String, Object> confirmSuggestion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Number typeId = (Number) body.get("typeId");
        if (typeId == null) throw new IllegalArgumentException("typeId 不能为空");
        Number catId = (Number) body.get("catId");
        return cards.card(contentService.confirmSuggestion(id, typeId.longValue(),
                catId == null ? null : catId.longValue()));
    }

    /** 重新执行引力波分类 */
    @PostMapping("/contents/{id}/reclassify")
    public Map<String, Object> reclassify(@PathVariable Long id) {
        return cards.card(contentService.reclassify(id));
    }

    @DeleteMapping("/contents/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        contentService.delete(id);
        return Map.of("ok", true);
    }

    /** 下载上传的原始文件 */
    @GetMapping("/files/{id}")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) throws Exception {
        ContentItem c = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在"));
        if (c.getStoredPath() == null || c.getStoredPath().isBlank()) {
            throw new IllegalArgumentException("该内容没有原始文件");
        }
        Path path = Paths.get(c.getStoredPath());
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件已丢失");
        }
        String filename = URLEncoder.encode(
                c.getSource() == null ? "file" : c.getSource(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /** 全文搜索；catId 可选，限定当前猫内检索（暂无法判定归属的内容始终可见） */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String q,
                                            @RequestParam(required = false) Long catId) {
        if (q == null || q.isBlank()) return List.of();
        return contentRepo.search(q.trim()).stream()
                .filter(c -> catId == null || notOwned(c) || catId.equals(resolveCatId(c)))
                .map(cards::card).collect(Collectors.toList());
    }

    /** 沿书→分类→猫与建议两条链都判不出归属（旧数据 / 建议已失效） */
    private boolean notOwned(ContentItem c) {
        return resolveCatId(c) == null;
    }
}
