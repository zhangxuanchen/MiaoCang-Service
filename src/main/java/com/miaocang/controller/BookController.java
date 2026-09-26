package com.miaocang.controller;

import com.miaocang.auth.CurrentUser;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.CatalogNode;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.User;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatalogNodeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.StudyTopicRepository;
import com.miaocang.repository.TypeAttachRepository;
import com.miaocang.service.CardAssembler;
import com.miaocang.service.ContentService;
import com.miaocang.service.ReadingService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 书架 / 书 / 两级目录管理 */
@RestController
@RequestMapping("/api")
public class BookController {

    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final CatalogNodeRepository catalogRepo;
    private final ContentItemRepository contentRepo;
    private final CatRepository catRepo;
    private final CardAssembler cards;
    private final ContentService contentService;
    private final ReadingService reading;
    private final StudyTopicRepository studyTopicRepo;
    private final TypeAttachRepository attachRepo;

    public BookController(BookTypeRepository typeRepo, BookRepository bookRepo,
                          CatalogNodeRepository catalogRepo, ContentItemRepository contentRepo,
                          CatRepository catRepo, CardAssembler cards, ContentService contentService,
                          ReadingService reading, StudyTopicRepository studyTopicRepo,
                          TypeAttachRepository attachRepo) {
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.catalogRepo = catalogRepo;
        this.contentRepo = contentRepo;
        this.catRepo = catRepo;
        this.cards = cards;
        this.contentService = contentService;
        this.reading = reading;
        this.studyTopicRepo = studyTopicRepo;
        this.attachRepo = attachRepo;
    }

    /** 书架：当前用户的猫名单 + 每只猫照看的分类 + 各自的书 + 统计（types 扁平列表保留兼容旧视图） */
    @GetMapping("/shelf")
    @Transactional(readOnly = true)
    public Map<String, Object> shelf() {
        User user = CurrentUser.get();
        List<Cat> myCats = catRepo.findByUserIdOrderByOrderIndexAscIdAsc(user.getId());
        List<Long> myCatIds = myCats.stream().map(Cat::getId).toList();
        List<Map<String, Object>> allTypes = new ArrayList<>();
        List<Map<String, Object>> catCards = new ArrayList<>();
        /* 猫维度收集箱徽标：按引力建议归属计数；完全无归属的（notOwned）在每只猫的收集箱都会出现，计入每只猫 */
        Map<Long, Long> pendingPerCat = new HashMap<>();
        long unownedPending = 0;
        for (ContentItem ci : contentRepo.findByStatusOrderByCreatedAtDesc(ContentItem.STATUS_PENDING)) {
            Long owner = resolvePendingCatId(ci);
            if (owner == null) unownedPending++;
            else pendingPerCat.merge(owner, 1L, Long::sum);
        }
        for (Cat c : myCats) {
            Map<String, Object> cc = cards.catCard(c);
            /* 预设分类不分喵：全部 preset 分类（shared）混排进每只喵的视图，排在自有分类之后 */
            List<Map<String, Object>> typeCards = new ArrayList<>();
            List<Long> ownTypeIds = new ArrayList<>();
            for (BookType t : typeRepo.findByCatIdOrderByIdAsc(c.getId())) {
                typeCards.add(typeWithBooks(t, List.of(c.getId()), t.isPreset()));
                ownTypeIds.add(t.getId());
            }
            for (BookType t : typeRepo.findByPresetTrueOrderByIdAsc()) {
                if (ownTypeIds.contains(t.getId())) continue;
                typeCards.add(typeWithBooks(t, List.of(c.getId()), true));
            }
            cc.put("types", typeCards);
            /* 猫行内容徽标：该喵名下的内容数（谁归档算谁的） */
            cc.put("contentCount", contentRepo.countByCatId(c.getId()));
            /* 侧栏「xx 的整理」菜单徽标：理解卡数 + 书数 */
            Map<String, Integer> rs = reading.readingStats(c.getId());
            cc.put("readingCards", rs.get("cards"));
            cc.put("readingBooks", rs.get("books"));
            /* 侧栏猫行的收集箱徽标：与 GET /api/inbox?catId= 的条数一致 */
            cc.put("inboxCount", unownedPending + pendingPerCat.getOrDefault(c.getId(), 0L));
            /* 侧栏「我的学习」主题按钮 */
            cc.put("studyTopics", studyTopicRepo.findByCatIdOrderBySortAscIdAsc(c.getId()).stream()
                    .map(t -> Map.of("id", t.getId(), "name", t.getName(),
                            "icon", t.getIcon() == null ? "🧠" : t.getIcon(),
                            "description", t.getDescription() == null ? "" : t.getDescription(),
                            "keywords", t.getKeywords() == null ? "" : t.getKeywords()))
                    .toList());
            catCards.add(cc);
        }
        /* types 扁平列表（旧视图兼容）：该用户的自有分类 + 共享预设分类，内容计数按该用户名下的喵汇总 */
        List<Long> seenTypeIds = new ArrayList<>();
        for (Cat c : myCats) {
            for (BookType t : typeRepo.findByCatIdOrderByIdAsc(c.getId())) {
                allTypes.add(typeWithBooks(t, myCatIds, t.isPreset()));
                seenTypeIds.add(t.getId());
            }
        }
        for (BookType t : typeRepo.findByPresetTrueOrderByIdAsc()) {
            if (seenTypeIds.contains(t.getId())) continue;
            allTypes.add(typeWithBooks(t, myCatIds, true));
        }
        allTypes.sort(Comparator.comparingLong(m -> ((Number) m.get("id")).longValue()));

        long myInbox = unownedPending;
        for (Long cid : myCatIds) myInbox += pendingPerCat.getOrDefault(cid, 0L);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("cats", catCards);
        res.put("types", allTypes);
        res.put("inboxCount", myInbox);
        res.put("totalCount", contentRepo.countByCatIdIn(myCatIds));
        return res;
    }

    /** PENDING 内容的猫归属：优先内容自身 catId（谁归档算谁的）；否则 bookId → type → cat，无书时退回引力建议的类型；与 ContentController.resolveCatId 同口径 */
    private Long resolvePendingCatId(ContentItem c) {
        if (c.getCatId() != null) return c.getCatId();
        Long typeId = null;
        if (c.getBookId() != null) {
            typeId = bookRepo.findById(c.getBookId()).map(Book::getTypeId).orElse(null);
        }
        if (typeId == null) {
            typeId = cards.suggestions(c).stream()
                    .filter(s -> s.get("typeId") != null)
                    .map(s -> ((Number) s.get("typeId")).longValue())
                    .findFirst().orElse(null);
        }
        return typeId == null ? null : typeRepo.findById(typeId)
                .map(BookType::getCatId).orElse(null);
    }

    /** 类型卡 + 它的书卡列表（含内容计数）；viewerCatIds 非空时书内容按这批喵名下条目计数（多用户隔离 + 共享分类的猫视角）；
     *  单喵视角额外标注 attached：该喵是否把共享分类挂上了图谱（图谱对挂接的空分类也展示） */
    private Map<String, Object> typeWithBooks(BookType t, List<Long> viewerCatIds, boolean shared) {
        /* 书按查看者过滤（多用户隔离）：
         * - null/空 = 无归属视角或该用户没有喵 → null 全量（管理员全库场景），空集一律不可见
         * - 自有分类（书主是自己的喵）→ 全保留
         * - 共享分类的书 → 只在该喵名下有内容时可见（谁投的算谁的，别人的书不外露） */
        List<Book> books = bookRepo.findByTypeIdOrderByIdAsc(t.getId()).stream()
                .filter(b -> visibleTo(b, t, viewerCatIds))
                .toList();
        long contentCount = 0;
        for (Book b : books) {
            contentCount += scopedCount(b.getId(), viewerCatIds);
        }
        Map<String, Object> tc = cards.typeCard(t, books.size(), contentCount);
        tc.put("shared", shared);
        if (viewerCatIds != null && viewerCatIds.size() == 1) {
            tc.put("attached", attachRepo.existsByTypeIdAndCatId(t.getId(), viewerCatIds.get(0)));
        }
        List<Map<String, Object>> bookCards = new ArrayList<>();
        for (Book b : books) {
            Map<String, Object> bc = cards.bookCard(b, scopedCount(b.getId(), viewerCatIds));
            bc.put("shared", shared);
            bookCards.add(bc);
        }
        tc.put("books", bookCards);
        return tc;
    }

    /** 书对查看者是否可见（多用户隔离）：null = 无归属视角全量可见；空集（用户还没有喵）= 不可见；
     *  自有分类（书主是自己的喵）= 全保留；共享分类的书 = 该喵名下有内容才可见（谁投的算谁的，别人的书不外露） */
    private boolean visibleTo(Book b, BookType t, List<Long> viewerCatIds) {
        if (viewerCatIds == null) return true;
        if (viewerCatIds.isEmpty()) return false;
        if (t.getCatId() != null && viewerCatIds.contains(t.getCatId())) return true;
        return scopedCount(b.getId(), viewerCatIds) > 0;
    }

    /** 书内容计数：null = 全部（管理员全库视角）；空集 = 0（该用户没有喵，任何内容都不可见）；否则按这批喵名下条目汇总 */
    private long scopedCount(Long bookId, List<Long> viewerCatIds) {
        if (viewerCatIds == null) return contentRepo.countByBookId(bookId);
        if (viewerCatIds.isEmpty()) return 0;
        long n = 0;
        for (Long cid : viewerCatIds) n += contentRepo.countByBookIdAndCatId(bookId, cid);
        return n;
    }

    // ================= 客户端同步快照（书库同步 UI 已移除，同步由客户端拉取） =================

    /**
     * 客户端同步：当前用户的知识图谱结构 + 预设置分类，一次拉全。
     * 图谱三层 = 猫（仅当前用户名下） → 分类（types，自有 + 共享 preset 混排，含书） → 内容条目（contents，挂归属喵）；
     * presets = 预设置分类（全局共享不分喵，客户端可据此做本地引导）。
     */
    @GetMapping("/client/sync")
    @Transactional(readOnly = true)
    public Map<String, Object> clientSync() {
        User user = CurrentUser.get();
        List<Cat> myCats = catRepo.findByUserIdOrderByOrderIndexAscIdAsc(user.getId());
        List<Long> myCatIds = myCats.stream().map(Cat::getId).toList();
        List<Map<String, Object>> catList = new ArrayList<>();
        for (Cat c : myCats) {
            Map<String, Object> cc = cards.catCard(c);
            /* 与 shelf 同口径：自有分类 + 共享 preset 分类混排 */
            List<Map<String, Object>> typeList = new ArrayList<>();
            List<Long> ownTypeIds = new ArrayList<>();
            for (BookType t : typeRepo.findByCatIdOrderByIdAsc(c.getId())) {
                typeList.add(typeWithBooks(t, List.of(c.getId()), t.isPreset()));
                ownTypeIds.add(t.getId());
            }
            for (BookType t : typeRepo.findByPresetTrueOrderByIdAsc()) {
                if (ownTypeIds.contains(t.getId())) continue;
                typeList.add(typeWithBooks(t, List.of(c.getId()), true));
            }
            /* 图谱叶子：该猫名下的内容条目（谁归档算谁的），客户端用于构建 Cat→Category→Content 三层图谱 */
            List<Map<String, Object>> leaves = contentRepo.findByCatId(c.getId()).stream()
                    .filter(ci -> ci.getBookId() != null).map(cards::card).toList();
            cc.put("contents", leaves);
            cc.put("types", typeList);
            catList.add(cc);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("exportedAt", java.time.LocalDateTime.now().withNano(0).toString());
        res.put("cats", catList);
        res.put("presets", typeRepo.findByPresetTrueOrderByIdAsc().stream()
                .map(t -> typeWithBooks(t, myCatIds, true)).toList());
        return res;
    }

    /** 书详情：两级目录树 + 每个目录下的内容 */
    @GetMapping("/books/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> book(@PathVariable Long id) {
        Book book = bookRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("书不存在"));
        BookType type = typeRepo.findById(book.getTypeId()).orElse(null);

        List<CatalogNode> nodes = catalogRepo.findByBookIdOrderByOrderIndexAscIdAsc(book.getId());
        List<ContentItem> contents = contentRepo.findByBookIdOrderByCreatedAtDesc(book.getId());

        Map<Long, List<Map<String, Object>>> byNode = new HashMap<>();
        List<Map<String, Object>> loose = new ArrayList<>();
        for (ContentItem c : contents) {
            if (c.getCatalogNodeId() == null) {
                loose.add(cards.card(c));
            } else {
                byNode.computeIfAbsent(c.getCatalogNodeId(), k -> new ArrayList<>()).add(cards.card(c));
            }
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        for (CatalogNode l1 : nodes) {
            if (l1.getParentId() != null) continue;
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("node", nodeMap(l1));
            section.put("contents", byNode.getOrDefault(l1.getId(), List.of()));
            List<Map<String, Object>> children = new ArrayList<>();
            for (CatalogNode l2 : nodes) {
                if (l1.getId().equals(l2.getParentId())) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    child.put("node", nodeMap(l2));
                    child.put("contents", byNode.getOrDefault(l2.getId(), List.of()));
                    children.add(child);
                }
            }
            section.put("children", children);
            sections.add(section);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("book", cards.bookCard(book, contents.size()));
        if (type != null) {
            res.put("type", cards.typeCard(type, 0, 0));
        }
        /* 喵的提取状态：指纹比对判定读过多少篇 + 反追踪来源表查出该书内容产出的理解卡片 */
        res.put("tidy", reading.tidyState(contents, type == null ? null : type.getCatId()));
        res.put("sections", sections);
        res.put("loose", loose);
        return res;
    }

    /** 新建书 */
    @PostMapping("/books")
    public Map<String, Object> createBook(@RequestBody Map<String, Object> body) {
        String title = (String) body.get("title");
        Number typeId = (Number) body.get("typeId");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("书名不能为空");
        if (typeId == null) throw new IllegalArgumentException("typeId 不能为空");
        BookType type = typeRepo.findById(typeId.longValue())
                .orElseThrow(() -> new IllegalArgumentException("类型不存在"));
        Book book = new Book();
        book.setTitle(title.trim());
        book.setTypeId(type.getId());
        book.setDescription((String) body.get("description"));
        book.setDefaultBook(false);
        bookRepo.save(book);
        return cards.bookCard(book, 0);
    }

    /** 重命名书 */
    @PutMapping("/books/{id}")
    public Map<String, Object> renameBook(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Book book = bookRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("书不存在"));
        String title = (String) body.get("title");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("书名不能为空");
        book.setTitle(title.trim());
        bookRepo.save(book);
        return cards.bookCard(book, contentRepo.countByBookId(book.getId()));
    }

    /** 删除书：内容退回收集箱 */
    @DeleteMapping("/books/{id}")
    @Transactional
    public Map<String, Object> deleteBook(@PathVariable Long id) {
        Book book = bookRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("书不存在"));
        contentService.returnBookContentsToInbox(id);
        catalogRepo.findByBookIdOrderByOrderIndexAscIdAsc(id).forEach(catalogRepo::delete);
        bookRepo.delete(book);
        return Map.of("ok", true);
    }

    /** 在书内新建目录（parentId 为空 = 一级目录） */
    @PostMapping("/books/{bookId}/catalogs")
    public Map<String, Object> createCatalog(@PathVariable Long bookId, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("目录名不能为空");
        Book book = bookRepo.findById(bookId).orElseThrow(() -> new IllegalArgumentException("书不存在"));
        Number parentId = (Number) body.get("parentId");
        CatalogNode node = new CatalogNode();
        node.setBookId(book.getId());
        node.setName(name.trim());
        if (parentId != null) {
            CatalogNode parent = catalogRepo.findById(parentId.longValue())
                    .orElseThrow(() -> new IllegalArgumentException("父目录不存在"));
            if (!parent.getBookId().equals(book.getId()) || parent.getParentId() != null) {
                throw new IllegalArgumentException("只允许在一级目录下创建二级目录");
            }
            node.setParentId(parent.getId());
        }
        node.setOrderIndex(catalogRepo.findByBookIdOrderByOrderIndexAscIdAsc(bookId).size());
        catalogRepo.save(node);
        return nodeMap(node);
    }

    /** 重命名目录 */
    @PutMapping("/catalogs/{id}")
    public Map<String, Object> renameCatalog(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        CatalogNode node = catalogRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目录不存在"));
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("目录名不能为空");
        node.setName(name.trim());
        catalogRepo.save(node);
        return nodeMap(node);
    }

    /** 删除空目录 */
    @DeleteMapping("/catalogs/{id}")
    @Transactional
    public Map<String, Object> deleteCatalog(@PathVariable Long id) {
        CatalogNode node = catalogRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目录不存在"));
        if (catalogRepo.countByParentId(id) > 0) {
            throw new IllegalArgumentException("请先删除该目录下的二级目录");
        }
        if (contentRepo.countByCatalogNodeId(id) > 0) {
            throw new IllegalArgumentException("目录下还有内容，请先移走");
        }
        catalogRepo.delete(node);
        return Map.of("ok", true);
    }

    private Map<String, Object> nodeMap(CatalogNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("bookId", n.getBookId());
        m.put("parentId", n.getParentId());
        m.put("name", n.getName());
        m.put("orderIndex", n.getOrderIndex());
        m.put("contentCount", contentRepo.countByCatalogNodeId(n.getId()));
        return m;
    }
}
