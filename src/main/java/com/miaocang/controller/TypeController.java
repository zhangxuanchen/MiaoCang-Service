package com.miaocang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.auth.CurrentUser;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.TypeAttach;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatalogNodeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.TypeAttachRepository;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.ContentService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 书籍类型管理：22 个预设 + 自定义 */
@RestController
@RequestMapping("/api/types")
public class TypeController {

    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final CardAssembler cards;
    private final ObjectMapper objectMapper;
    private final CatRepository catRepo;
    private final TypeAttachRepository attachRepo;
    private final ContentItemRepository contentRepo;
    private final CatalogNodeRepository catalogRepo;
    private final ContentService contentService;

    public TypeController(BookTypeRepository typeRepo, BookRepository bookRepo,
                          CardAssembler cards, ObjectMapper objectMapper,
                          CatRepository catRepo, TypeAttachRepository attachRepo,
                          ContentItemRepository contentRepo, CatalogNodeRepository catalogRepo,
                          ContentService contentService) {
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.cards = cards;
        this.objectMapper = objectMapper;
        this.catRepo = catRepo;
        this.attachRepo = attachRepo;
        this.contentRepo = contentRepo;
        this.catalogRepo = catalogRepo;
        this.contentService = contentService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> list = new ArrayList<>();
        typeRepo.findAll().forEach(t -> {
            long bookCount = bookRepo.countByTypeId(t.getId());
            list.add(cards.typeCard(t, bookCount, 0));
        });
        return list;
    }

    /** 新建自定义类型（自带引力关键词与可选目录规则），并创建默认书 */
    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("类型名不能为空");
        Object catIdObj = body.get("catId");
        if (catIdObj == null) throw new IllegalArgumentException("请指定照看这只分类的喵");
        Cat cat = catRepo.findById(((Number) catIdObj).longValue())
                .orElseThrow(() -> new IllegalArgumentException("猫不存在"));
        assertNameFree(name.trim(), null, cat.getUserId());
        BookType type = new BookType();
        apply(type, body);
        type.setName(name.trim());
        type.setPreset(false);
        typeRepo.save(type);

        Book book = new Book();
        book.setTitle("《" + type.getName() + "》");
        book.setTypeId(type.getId());
        book.setDescription(type.getDescription());
        book.setDefaultBook(true);
        bookRepo.save(book);
        return cards.typeCard(type, 1, 0);
    }

    /** 挂接共享预设分类到某只喵的图谱（共享数据不动，只在图谱结构上常驻该节点） */
    @PostMapping("/{id}/attach")
    @Transactional
    public Map<String, Object> attach(@PathVariable Long id, @RequestParam Long catId) {
        BookType type = typeRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (!type.isPreset()) throw new IllegalArgumentException("只有共享预设分类可挂接，专属分类本就属于你的喵");
        Cat cat = catRepo.findById(catId).orElseThrow(() -> new IllegalArgumentException("猫不存在"));
        if (!attachRepo.existsByTypeIdAndCatId(id, cat.getId())) {
            TypeAttach a = new TypeAttach();
            a.setTypeId(id);
            a.setCatId(cat.getId());
            attachRepo.save(a);
        }
        return Map.of("ok", true);
    }

    /** 解除挂接：该喵的图谱上摘掉这个共享分类节点（数据与其他喵不受影响） */
    @DeleteMapping("/{id}/attach")
    @Transactional
    public Map<String, Object> detach(@PathVariable Long id, @RequestParam Long catId) {
        attachRepo.deleteByTypeIdAndCatId(id, catId);
        return Map.of("ok", true);
    }

    /**
     * 分类名查重（多用户口径）：
     * 与全局共享预设重名 → 拒绝并引导走挂接；与同主人的专属分类重名 → 拒绝；
     * 其他主人的专属分类不影响。
     */
    private void assertNameFree(String name, Long selfTypeId, Long ownerUserId) {
        for (BookType t : typeRepo.findByName(name)) {
            if (t.getId().equals(selfTypeId)) continue;
            if (t.isPreset() || t.getCatId() == null) {
                throw new IllegalArgumentException("共享预设里已有「" + name + "」，可用「挂接共享分类」直接把它挂上图谱");
            }
            Cat owner = catRepo.findById(t.getCatId()).orElse(null);
            if (owner != null && owner.getUserId() != null && owner.getUserId().equals(ownerUserId)) {
                throw new IllegalArgumentException("你的书房里已有「" + name + "」这个分类");
            }
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BookType type = typeRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("类型不存在"));
        apply(type, body);
        typeRepo.save(type);
        return cards.typeCard(type, bookRepo.countByTypeId(id), 0);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        BookType type = typeRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("类型不存在"));
        /* 预设分类也可删（书房总览「预设置分类」的 ✕ 就是走这里）：
       它是全体喵共享的，删掉后所有喵的图谱里都不再出现；启动播种只在库完全为空时跑，不会把它塞回来。
       分类下的内容不丢弃 —— 先整批退回收集箱，再把空书 / 目录清掉。 */
        int movedToInbox = contentService.returnTypeContentsToInbox(id);
        List<Book> books = bookRepo.findByTypeIdOrderByIdAsc(id);
        for (Book b : books) {
            /* 内容已在上面退回收集箱，这里剩下的都是空书（含新建分类自动附带的默认书）：连目录带书一起清 */
            catalogRepo.findByBookIdOrderByOrderIndexAscIdAsc(b.getId()).forEach(catalogRepo::delete);
            bookRepo.delete(b);
        }
        attachRepo.deleteByTypeId(id);
        typeRepo.delete(type);
        return Map.of("ok", true, "movedToInbox", movedToInbox);
    }

    private void apply(BookType type, Map<String, Object> body) {
        if (body.containsKey("name")) {
            String name = (String) body.get("name");
            if (name != null && !name.isBlank() && !name.trim().equals(type.getName())) {
                String trimmed = name.trim();
                Cat owner = type.getCatId() == null ? null : catRepo.findById(type.getCatId()).orElse(null);
                assertNameFree(trimmed, type.getId(), owner == null ? null : owner.getUserId());
                String oldName = type.getName();
                type.setName(trimmed);
                // 改名同步：只纠正「从未单独改过名」的默认书（《旧分类名》→《新分类名》），用户自定义书名不动
                bookRepo.findByTypeIdOrderByIdAsc(type.getId()).stream()
                        .filter(b -> b.isDefaultBook() && ("《" + oldName + "》").equals(b.getTitle()))
                        .forEach(b -> b.setTitle("《" + trimmed + "》"));
            }
        }
        if (body.containsKey("catId")) {
            Object catId = body.get("catId");
            type.setCatId(catId == null ? null : ((Number) catId).longValue());
        }
        if (body.containsKey("icon")) type.setIcon((String) body.get("icon"));
        if (body.containsKey("color")) type.setColor((String) body.get("color"));
        if (body.containsKey("description")) type.setDescription((String) body.get("description"));
        if (body.containsKey("gravityKeywords")) {
            Object kw = body.get("gravityKeywords");
            type.setGravityKeywords(kw == null ? "" : kw.toString());
        }
        if (body.containsKey("catalogRules")) {
            Object rules = body.get("catalogRules");
            if (rules == null || rules.toString().isBlank()) {
                type.setCatalogRules(null);
            } else if (rules instanceof String s) {
                validateRulesJson(s);
                type.setCatalogRules(s);
            } else {
                try {
                    String json = objectMapper.writeValueAsString(rules);
                    validateRulesJson(json);
                    type.setCatalogRules(json);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException("catalogRules 序列化失败: " + e.getMessage());
                }
            }
        }
    }

    /** 校验目录规则 JSON 结构合法（保证引擎可解析） */
    private void validateRulesJson(String json) {
        try {
            ClassifyRules[] rules = objectMapper.readValue(json, ClassifyRules[].class);
            for (ClassifyRules r : rules) {
                if (r.name == null || r.name.isBlank()) {
                    throw new IllegalArgumentException("目录规则缺少 name");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("catalogRules 不是合法的 JSON 数组: " + e.getMessage());
        }
    }

    public static class ClassifyRules {
        public String name;
        public List<String> keywords;
        public List<ClassifyRules> children;
    }
}
