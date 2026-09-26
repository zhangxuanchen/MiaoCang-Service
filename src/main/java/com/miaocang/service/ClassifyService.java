package com.miaocang.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.*;
import com.miaocang.laya.LayaClassifyService;
import com.miaocang.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 归档编排服务：引力打分 -> 归入某类型的默认书 -> 在书内匹配两级目录。
 *
 * 两级目录生成逻辑（像引力波把内容"折入"书页）：
 * 1. 一级目录：与该类型的 catalogRules[].keywords 匹配，取最强场；
 *    无匹配则落入"综合收录"。
 * 2. 二级目录：优先匹配一级目录规则的 children；否则用内容命中的
 *    最强引力关键词作为目录名（目录随内容自然生长）；再否则"默认收录"。
 */
@Service
public class ClassifyService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyService.class);

    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final CatalogNodeRepository catalogRepo;
    private final ContentItemRepository contentRepo;
    private final GravityEngine engine;
    private final ObjectMapper objectMapper;
    /** laya 语义分类：可用时用概率判定取代关键词阈值，不可用时自动降级（fail-open）。 */
    private final LayaClassifyService layaClassify;

    @Value("${miaocang.gravity.min-score:2.0}")
    private double minScore;

    @Value("${miaocang.gravity.title-weight:3.0}")
    private double titleWeight;

    public ClassifyService(BookTypeRepository typeRepo, BookRepository bookRepo,
                           CatalogNodeRepository catalogRepo, ContentItemRepository contentRepo,
                           GravityEngine engine, ObjectMapper objectMapper,
                           com.miaocang.laya.LayaClassifyService layaClassify) {
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.catalogRepo = catalogRepo;
        this.contentRepo = contentRepo;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.layaClassify = layaClassify;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogRule {
        public String name;
        public List<String> keywords;
        public List<CatalogRule> children;
    }

    /** 对新内容执行引力归档（决定归书 + 两级目录，或进收集箱） */
    @Transactional
    public void autoAssign(ContentItem item) {
        autoAssign(item, null);
    }

    /** 带猫上下文的引力归档：打分范围限定「该喵可用的分类」，避免投给喵喵的内容被吸进小肥名下的分类 */
    @Transactional
    public void autoAssign(ContentItem item, Long catId) {
        List<BookType> types = catId == null ? typeRepo.findAll() : availableTypes(catId);
        LayaClassifyService.Decision d = score(types, item);
        List<GravityEngine.TypeScore> scores = d.scores();

        if (scores.isEmpty()) {
            item.setStatus(ContentItem.STATUS_PENDING);
            log.info("[引力] '{}' 无任何分类命中，进入收集箱", item.getTitle());
            return;
        }
        if (!archivable(d, scores)) {
            item.setStatus(ContentItem.STATUS_PENDING);
            if (d.modelGate() != null) {
                log.info("[引力] '{}' 未过语义门控，进入收集箱；{}", item.getTitle(), d.note());
            } else {
                log.info("[引力] '{}' 未达到引力阈值({}<{})，进入收集箱", item.getTitle(),
                        scores.get(0).score(), minScore);
            }
            return;
        }
        GravityEngine.TypeScore best = scores.get(0);
        BookType type = typeRepo.findById(best.typeId()).orElseThrow();
        assignToType(item, type, best);
        item.setStatus(ContentItem.STATUS_CLASSIFIED);
        log.info("[引力] '{}' 被吸引到「{}」 score={}{}", item.getTitle(), type.getName(), best.score(),
                d.note() == null ? "" : "；" + d.note());
    }

    /**
     * 打分（接线 laya）：先跑零成本的关键词引力，再交给 {@link LayaClassifyService} 做语义判定。
     * 侧车不可用 / 超时 / 已禁用 / 中文之外等任何异常都在 LayaClassifyService 内部吞掉并回退，
     * 这里拿到的始终是可直接使用的一组分数，同时把 top5 写进 item（前端展示口径不变）。
     */
    private LayaClassifyService.Decision score(List<BookType> types, ContentItem item) {
        List<GravityEngine.TypeScore> keyword = engine.scoreAll(types, item.getTitle(), item.getRawText());
        LayaClassifyService.Decision d = layaClassify.decide(types, item.getTitle(), item.getRawText(), keyword);
        List<GravityEngine.TypeScore> scores = d.scores();
        item.setTypeScores(toJson(top(scores, 5)));
        if (!scores.isEmpty() && !scores.get(0).matchedKeywords().isEmpty()) {
            item.setMatchedTags(String.join(",", scores.get(0).matchedKeywords()));
        }
        return d;
    }

    /** 归档门控：laya 参与时用概率间隔，未参与时回退关键词阈值（两个量纲不同，不叠加）。 */
    private boolean archivable(LayaClassifyService.Decision d, List<GravityEngine.TypeScore> scores) {
        if (d.modelGate() != null) return d.modelGate();
        return scores.get(0).score() >= minScore;
    }

    /** 该喵可用的分类：全局共享预设 + 该喵自有（挂接的共享分类本身 preset=true，已被覆盖） */
    private List<BookType> availableTypes(Long catId) {
        List<BookType> out = new ArrayList<>(typeRepo.findByPresetTrueOrderByIdAsc());
        for (BookType t : typeRepo.findByCatIdAndPresetFalseOrderByIdAsc(catId)) {
            if (out.stream().noneMatch(x -> x.getId().equals(t.getId()))) out.add(t);
        }
        return out;
    }

    /** 只执行引力打分并写入 typeScores / matchedTags，不做归档决策（供书库导入的二次分类使用） */
    public List<GravityEngine.TypeScore> scoreOnly(ContentItem item) {
        return score(typeRepo.findAll(), item).scores();
    }

    /** 把内容归入指定类型的默认书，并自动落到两级目录 */
    @Transactional
    public void assignToType(ContentItem item, BookType type, GravityEngine.TypeScore best) {
        Book book = bookRepo.findByTypeIdAndDefaultBookTrue(type.getId())
                .orElseGet(() -> createDefaultBook(type));
        item.setBookId(book.getId());

        CatalogRule l1Rule = matchLevel1Rule(type, item);
        CatalogNode level1 = findOrCreateLevel1(book, l1Rule == null ? "综合收录" : l1Rule.name);
        CatalogNode level2 = findOrCreateLevel2(book, level1, l1Rule, item, best);

        item.setCatalogNodeId(level2.getId());
    }

    // ---------------- 一级目录 ----------------

    private CatalogRule matchLevel1Rule(BookType type, ContentItem item) {
        List<CatalogRule> rules = parseRules(type.getCatalogRules());
        CatalogRule best = null;
        double bestScore = 0;
        String title = nullToEmpty(item.getTitle());
        String body = nullToEmpty(item.getRawText());
        for (CatalogRule rule : rules) {
            if (rule.keywords == null) continue;
            double s = 0;
            for (String kw : rule.keywords) {
                if (kw == null || kw.isBlank()) continue;
                s += GravityEngine.countOccurrences(title, kw) * engineTitleWeight()
                        + Math.min(GravityEngine.countOccurrences(body, kw), 5);
            }
            if (s > bestScore) {
                bestScore = s;
                best = rule;
            }
        }
        return best;
    }

    private CatalogNode findOrCreateLevel1(Book book, String name) {
        return catalogRepo.findByBookIdAndName(book.getId(), name)
                .orElseGet(() -> {
                    CatalogNode n = new CatalogNode();
                    n.setBookId(book.getId());
                    n.setParentId(null);
                    n.setName(name);
                    n.setOrderIndex(nextOrder(book.getId()));
                    return catalogRepo.save(n);
                });
    }

    // ---------------- 二级目录 ----------------

    private CatalogNode findOrCreateLevel2(Book book, CatalogNode level1, CatalogRule l1Rule,
                                           ContentItem item, GravityEngine.TypeScore best) {
        // 1) 匹配一级目录规则的 children
        if (l1Rule != null && l1Rule.children != null) {
            CatalogRule bestChild = null;
            double bestScore = 0;
            String title = nullToEmpty(item.getTitle());
            String body = nullToEmpty(item.getRawText());
            for (CatalogRule child : l1Rule.children) {
                if (child.keywords == null) continue;
                double s = 0;
                for (String kw : child.keywords) {
                    if (kw == null || kw.isBlank()) continue;
                    s += GravityEngine.countOccurrences(title, kw) * engineTitleWeight()
                            + Math.min(GravityEngine.countOccurrences(body, kw), 5);
                }
                if (s > bestScore) {
                    bestScore = s;
                    bestChild = child;
                }
            }
            if (bestChild != null) {
                return findOrCreateLevel2Node(book, level1, bestChild.name);
            }
        }
        // 2) 用最强命中关键词作为二级目录名（目录自然生长）
        if (best != null && !best.matchedKeywords().isEmpty()) {
            return findOrCreateLevel2Node(book, level1, best.matchedKeywords().get(0));
        }
        // 3) 兜底
        return findOrCreateLevel2Node(book, level1, "默认收录");
    }

    private CatalogNode findOrCreateLevel2Node(Book book, CatalogNode level1, String name) {
        return catalogRepo.findByBookIdAndParentIdAndName(book.getId(), level1.getId(), name)
                .orElseGet(() -> {
                    CatalogNode n = new CatalogNode();
                    n.setBookId(book.getId());
                    n.setParentId(level1.getId());
                    n.setName(name);
                    n.setOrderIndex(nextOrder(book.getId()));
                    return catalogRepo.save(n);
                });
    }

    // ---------------- helpers ----------------

    private Book createDefaultBook(BookType type) {
        Book book = new Book();
        book.setTitle("《" + type.getName() + "》");
        book.setTypeId(type.getId());
        book.setDescription(type.getDescription());
        book.setDefaultBook(true);
        return bookRepo.save(book);
    }

    private int nextOrder(Long bookId) {
        return (int) catalogRepo.findByBookIdOrderByOrderIndexAscIdAsc(bookId).size();
    }

    private List<CatalogRule> parseRules(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            CatalogRule[] rules = objectMapper.readValue(json, CatalogRule[].class);
            return rules == null ? List.of() : Arrays.asList(rules);
        } catch (Exception e) {
            log.warn("catalogRules 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<GravityEngine.TypeScore> top(List<GravityEngine.TypeScore> scores, int n) {
        return scores.subList(0, Math.min(n, scores.size()));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private double engineTitleWeight() {
        return titleWeight;
    }
}
