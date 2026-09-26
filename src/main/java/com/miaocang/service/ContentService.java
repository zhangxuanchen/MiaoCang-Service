package com.miaocang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.CatalogNode;
import com.miaocang.entity.ContentItem;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatalogNodeRepository;
import com.miaocang.repository.ContentItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 内容采集与流转服务：采集 -> 引力归档 -> 手动调整 / 重分类。
 */
@Service
public class ContentService {

    private final ContentItemRepository contentRepo;
    private final BookRepository bookRepo;
    private final BookTypeRepository bookTypeRepo;
    private final CatalogNodeRepository catalogRepo;
    private final ExtractService extractService;
    private final ClassifyService classifyService;
    private final LibrarySyncService librarySync;
    private final ExtractPipeline extractPipeline;
    private final ObjectMapper objectMapper;

    public ContentService(ContentItemRepository contentRepo, BookRepository bookRepo,
                          BookTypeRepository bookTypeRepo, CatalogNodeRepository catalogRepo,
                          ExtractService extractService, ClassifyService classifyService,
                          LibrarySyncService librarySync, ExtractPipeline extractPipeline,
                          ObjectMapper objectMapper) {
        this.contentRepo = contentRepo;
        this.bookRepo = bookRepo;
        this.bookTypeRepo = bookTypeRepo;
        this.catalogRepo = catalogRepo;
        this.extractService = extractService;
        this.classifyService = classifyService;
        this.librarySync = librarySync;
        this.extractPipeline = extractPipeline;
        this.objectMapper = objectMapper;
    }

    /** 采集网页链接（catId = 投放喵上下文：谁投的算谁的，引力打分也只在该喵可用分类内） */
    @Transactional
    public ContentItem collectUrl(Long catId, String url) {
        String normalized = normalizeUrl(url);
        ExtractService.ExtractResult r = extractService.extractUrl(normalized);
        ContentItem item = new ContentItem();
        item.setTitle(r.title());
        item.setContentType(ContentItem.TYPE_URL);
        item.setSource(normalized);
        item.setRawText(r.rawText());
        item.setSummary(r.summary());
        return finishCollect(item, catId);
    }

    /** 采集纯文本 */
    @Transactional
    public ContentItem collectText(Long catId, String title, String text) {
        ContentItem item = new ContentItem();
        item.setTitle(title == null || title.isBlank() ? firstLine(text) : title.trim());
        item.setContentType(ContentItem.TYPE_TEXT);
        item.setSource(null);
        item.setRawText(text);
        item.setSummary(abbreviate(text, 200));
        return finishCollect(item, catId);
    }

    /** 采集上传的 Word / Excel 文件 */
    @Transactional
    public ContentItem collectFile(Long catId, ExtractService.MultipartFileLike file) throws Exception {
        ExtractService.StoredFile stored = extractService.store(file);
        ExtractService.ExtractResult r = extractService.extractFile(stored.path(), stored.originalName());
        ContentItem item = new ContentItem();
        item.setTitle(r.title());
        item.setContentType(guessType(stored.originalName()));
        item.setSource(stored.originalName());
        item.setStoredPath(stored.path().toString());
        item.setRawText(r.rawText());
        item.setSummary(r.summary());
        return finishCollect(item, catId);
    }

    /** 采集收尾：挂上投放喵 -> 引力归档 -> 归档成功则落盘书库 -> git 提交 -> 喵喵自动提取（异步） */
    private ContentItem finishCollect(ContentItem item, Long catId) {
        if (catId != null) item.setCatId(catId);
        contentRepo.saveAndFlush(item);
        classifyService.autoAssign(item, catId);
        if (ContentItem.STATUS_CLASSIFIED.equals(item.getStatus())) {
            librarySync.writeContent(item);
        }
        librarySync.commit("采集: " + item.getTitle());
        ContentItem saved = contentRepo.save(item);
        extractPipeline.extractAsync(saved.getId());
        return saved;
    }

    /** 手动移动：可放入某本书的某个目录（catalogNodeId 可为空 = 书根）；catId = 归档喵（谁归档算谁的，移回收集箱则清空） */
    @Transactional
    public ContentItem move(Long id, Long bookId, Long catalogNodeId, Long catId) {
        ContentItem item = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + id));
        if (bookId != null) {
            Book book = bookRepo.findById(bookId)
                    .orElseThrow(() -> new IllegalArgumentException("书不存在: " + bookId));
            item.setBookId(book.getId());
            if (catId != null) item.setCatId(catId);
            if (catalogNodeId != null) {
                CatalogNode node = catalogRepo.findById(catalogNodeId)
                        .orElseThrow(() -> new IllegalArgumentException("目录不存在: " + catalogNodeId));
                if (!node.getBookId().equals(bookId)) {
                    throw new IllegalArgumentException("目录不属于该书");
                }
                item.setCatalogNodeId(node.getId());
            } else {
                item.setCatalogNodeId(null);
            }
            item.setStatus(ContentItem.STATUS_CLASSIFIED);
            item.setAutoClassified(false);
        } else {
            // 移回收集箱（取消归档：归属喵一并清空）
            item.setBookId(null);
            item.setCatalogNodeId(null);
            item.setCatId(null);
            item.setStatus(ContentItem.STATUS_PENDING);
        }
        if (ContentItem.STATUS_CLASSIFIED.equals(item.getStatus())) {
            librarySync.relocateContent(item);
        } else {
            librarySync.removeContent(item);
        }
        librarySync.commit("移动: " + item.getTitle());
        return contentRepo.save(item);
    }

    /** 按收集箱建议归档：归入指定类型的默认书并匹配两级目录；catId = 归档喵（谁归档算谁的） */
    @Transactional
    public ContentItem confirmSuggestion(Long id, Long typeId, Long catId) {
        ContentItem item = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + id));
        BookType type = bookTypeRepo.findById(typeId)
                .orElseThrow(() -> new IllegalArgumentException("类型不存在: " + typeId));
        GravityEngine.TypeScore best = null;
        if (item.getTypeScores() != null) {
            try {
                List<Map<String, Object>> scores = objectMapper.readValue(item.getTypeScores(), List.class);
                for (Map<String, Object> s : scores) {
                    Object tid = s.get("typeId");
                    if (tid != null && String.valueOf(tid).equals(String.valueOf(typeId))) {
                        @SuppressWarnings("unchecked")
                        List<String> matched = (List<String>) s.getOrDefault("matched", List.of());
                        double score = s.get("score") == null ? 1 : ((Number) s.get("score")).doubleValue();
                        best = new GravityEngine.TypeScore(type.getId(), type.getName(), type.getIcon(),
                                type.getColor(), score, matched);
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        classifyService.assignToType(item, type, best);
        item.setStatus(ContentItem.STATUS_CLASSIFIED);
        item.setAutoClassified(false);
        if (catId != null) item.setCatId(catId);
        librarySync.writeContent(item);
        librarySync.commit("归档: " + item.getTitle());
        return contentRepo.save(item);
    }

    /** 重新执行引力分类（保持条目归属喵不变，打分限定该喵可用分类） */
    @Transactional
    public ContentItem reclassify(Long id) {
        ContentItem item = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + id));
        item.setBookId(null);
        item.setCatalogNodeId(null);
        classifyService.autoAssign(item, item.getCatId());
        if (ContentItem.STATUS_CLASSIFIED.equals(item.getStatus())) {
            librarySync.relocateContent(item);
        } else {
            librarySync.removeContent(item);
        }
        librarySync.commit("重分类: " + item.getTitle());
        return contentRepo.save(item);
    }

    @Transactional
    public void delete(Long id) {
        contentRepo.findById(id).ifPresent(item -> {
            librarySync.removeContent(item);
            librarySync.commit("删除: " + item.getTitle());
        });
        contentRepo.deleteById(id);
    }

    /** 手动编辑标题/摘要（整理已归档内容，如纠正抓取失败的站点通用标题） */
    @Transactional
    public ContentItem edit(Long id, String title, String summary) {
        ContentItem item = contentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + id));
        boolean changed = false;
        if (title != null && !title.isBlank() && !title.equals(item.getTitle())) {
            item.setTitle(title.strip());
            changed = true;
        }
        if (summary != null) {
            String s = summary.strip();
            if (!s.equals(item.getSummary() == null ? "" : item.getSummary())) {
                item.setSummary(s.isEmpty() ? null : s);
                changed = true;
                // 主人手改了摘要 = 对喵喵提取结果的一次反馈（若该内容有提取结果）
                if (item.getExtract() != null) {
                    extractPipeline.record(id, com.miaocang.entity.AgentFeedback.ACTION_EDIT_SUMMARY,
                            null, s.isEmpty() ? "（清空）" : s);
                }
            }
        }
        if (!changed) return item;
        ContentItem saved = contentRepo.save(item);
        if (saved.getLibraryPath() != null && ContentItem.STATUS_CLASSIFIED.equals(saved.getStatus())) {
            librarySync.relocateContent(saved);
            librarySync.commit("编辑: " + saved.getTitle());
        }
        return saved;
    }

    /** 书删除时，其内容退回收集箱 */
    @Transactional
    public void returnBookContentsToInbox(Long bookId) {
        List<ContentItem> items = contentRepo.findByBookIdOrderByCreatedAtDesc(bookId);
        for (ContentItem item : items) {
            item.setBookId(null);
            item.setCatalogNodeId(null);
            item.setStatus(ContentItem.STATUS_PENDING);
            librarySync.removeContent(item);
            contentRepo.save(item);
        }
        if (!items.isEmpty()) {
            librarySync.commit("书删除，内容退回收集箱");
        }
    }

    /** 分类删除时，其下每本书的内容都退回收集箱，返回退回的条数。
     *  与删书同一条路：清 bookId/catalogNodeId 转 PENDING、库文件移出。
     *  catId 刻意保留 —— 内容仍属原来那只喵，落进的是「对应喵」的收集箱，而不是变成无主的在每只喵下面都冒出来。 */
    @Transactional
    public int returnTypeContentsToInbox(Long typeId) {
        int moved = 0;
        for (Book b : bookRepo.findByTypeIdOrderByIdAsc(typeId)) {
            for (ContentItem item : contentRepo.findByBookIdOrderByCreatedAtDesc(b.getId())) {
                item.setBookId(null);
                item.setCatalogNodeId(null);
                item.setStatus(ContentItem.STATUS_PENDING);
                librarySync.removeContent(item);
                contentRepo.save(item);
                moved++;
            }
        }
        if (moved > 0) {
            librarySync.commit("分类删除，内容退回收集箱");
        }
        return moved;
    }

    private String normalizeUrl(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u;
    }

    private String guessType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return ContentItem.TYPE_WORD;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return ContentItem.TYPE_EXCEL;
        return ContentItem.TYPE_TEXT;
    }

    private String firstLine(String text) {
        if (text == null) return "未命名内容";
        String line = text.strip().split("\n")[0];
        return line.isBlank() ? "未命名内容" : abbreviate(line, 60);
    }

    private String abbreviate(String s, int len) {
        if (s == null) return "";
        String stripped = s.strip();
        return stripped.length() <= len ? stripped : stripped.substring(0, len) + "…";
    }
}
