package com.miaocang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.Book;
import com.miaocang.entity.CatalogNode;
import com.miaocang.entity.ContentItem;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.CatalogNodeRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 视图卡片组装：把内容条目转成前端友好的 Map（带书名/目录路径/建议等）。
 */
@Service
public class CardAssembler {

    private final BookRepository bookRepo;
    private final CatalogNodeRepository catalogRepo;
    private final ExtractPipeline extractPipeline;
    private final ObjectMapper objectMapper;

    /** 建议（typeScores JSON）的解析形态 */
    public record Suggestion(Long typeId, String name, String icon, String color, double score, List<String> matched) {}

    public CardAssembler(BookRepository bookRepo, CatalogNodeRepository catalogRepo,
                         ExtractPipeline extractPipeline, ObjectMapper objectMapper) {
        this.bookRepo = bookRepo;
        this.catalogRepo = catalogRepo;
        this.extractPipeline = extractPipeline;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> card(ContentItem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("title", c.getTitle());
        m.put("contentType", c.getContentType());
        m.put("source", c.getSource());
        m.put("summary", c.getSummary());
        m.put("matchedTags", c.getMatchedTags());
        m.put("autoClassified", c.isAutoClassified());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("bookId", c.getBookId());
        m.put("catId", c.getCatId());
        m.put("catalogNodeId", c.getCatalogNodeId());
        if (c.getBookId() != null) {
            bookRepo.findById(c.getBookId()).ifPresent(b -> m.put("bookTitle", b.getTitle()));
        }
        if (c.getCatalogNodeId() != null) {
            catalogRepo.findById(c.getCatalogNodeId()).ifPresent(n -> {
                m.put("catalogName", n.getName());
                if (n.getParentId() != null) {
                    catalogRepo.findById(n.getParentId()).ifPresent(p -> m.put("parentCatalogName", p.getName()));
                }
            });
        }
        m.put("suggestions", suggestions(c));
        m.put("extract", extractPipeline.parseExtract(c));
        m.put("extractRunning", extractPipeline.isRunning(c.getId()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> suggestions(ContentItem c) {
        if (c.getTypeScores() == null || c.getTypeScores().isBlank()) return List.of();
        try {
            return objectMapper.readValue(c.getTypeScores(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 猫摘要（侧栏名单 + 图谱入口） */
    public Map<String, Object> catCard(com.miaocang.entity.Cat c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("workspacePath", c.getWorkspacePath());
        m.put("icon", c.getIcon());
        m.put("color", c.getColor());
        m.put("description", c.getDescription());
        return m;
    }

    /** 类型摘要 */
    public Map<String, Object> typeCard(com.miaocang.entity.BookType t, long bookCount, long contentCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("catId", t.getCatId());
        m.put("name", t.getName());
        m.put("icon", t.getIcon());
        m.put("color", t.getColor());
        m.put("description", t.getDescription());
        m.put("gravityKeywords", t.getGravityKeywords());
        m.put("catalogRules", t.getCatalogRules());
        m.put("preset", t.isPreset());
        m.put("bookCount", bookCount);
        m.put("contentCount", contentCount);
        return m;
    }

    /** 书摘要 */
    public Map<String, Object> bookCard(Book b, long contentCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("title", b.getTitle());
        m.put("typeId", b.getTypeId());
        m.put("description", b.getDescription());
        m.put("defaultBook", b.isDefaultBook());
        m.put("contentCount", contentCount);
        return m;
    }
}
