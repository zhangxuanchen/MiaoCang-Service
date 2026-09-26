package com.miaocang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.LearnCard;
import com.miaocang.entity.UnderstandingCard;
import com.miaocang.entity.UnderstandingCardSource;
import com.miaocang.chat.CatWorkspaceService;
import com.miaocang.harness.GateResult;
import com.miaocang.harness.HarnessRunContext;
import com.miaocang.harness.HarnessSpec;
import com.miaocang.harness.PipelineRunner;
import com.miaocang.harness.QualityGate;
import com.miaocang.harness.SkillAssembler;
import com.miaocang.laya.LayaCardRouter;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.LearnCardRepository;
import com.miaocang.repository.UnderstandingCardRepository;
import com.miaocang.repository.UnderstandingCardSourceRepository;
import com.miaocang.service.agent.MiaoMiaoAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 喵的整理：把一只喵的全部知识（书库内容 + 人与喵的会话记录）分类、分层，
 * 规划成一本一本「二级主题书」的大纲——每本书 = 书标题 + 描述（主要记录什么）+ 二级主题清单。
 *
 * <p>产出以 md 文件落盘在猫工作区的 {@code reading/} 目录（一本书一个 md，形态像一个个 skill），
 * 并 git 提交进书库。流程：list（书架 + 素材概览）→ start（异步任务，调 Agent.chat 生成大纲 JSON
 * → 逐书写 md）→ 前端轮询任务进度。
 */
@Service
public class ReadingService {

    private static final Logger log = LoggerFactory.getLogger(ReadingService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String READING_DIR = "reading";
    /** 知识点卡片库目录（一点一 md，形态像 skill） */
    private static final String POINTS_DIR = "points";
    /** 会话记忆最多摘录的轮数（防 prompt 超长） */
    private static final int MAX_CHAT_TURNS = 40;
    /** 单篇正文喂给模型的最大字数（防 prompt 超长） */
    private static final int MAX_BODY_LEN = 6000;
    /** 一次任务最多精读的篇数（防任务过久） */
    private static final int MAX_ARTICLES = 120;

    private static final String SYSTEM_PROMPT = """
            你是「喵的整理」的编辑猫：读书猫已经把一篇篇文章读懂、拆成了理解卡片，\
            现在请你把这些理解卡片做分类、分层，归纳成一本一本「二级主题书」。
            规则：
            1. 每本书 = 书标题 + 一句话描述（说明这本书主要记录什么）+ 3~8 个二级主题；
            2. 每个二级主题 = 主题名 + 一句话说明（这个主题收拢哪些理解），并把归入该主题的理解卡片编号列进 cards；
            3. 所有卡片都必须归进某个二级主题，每张卡片只归一处——就像内容引力波，把同类理解吸进同一本书；
            4. 书与书之间主题不重叠，书数按卡片自然聚类决定（通常 2~6 本），宁缺毋滥；
            5. 只输出一个 JSON 数组，不要输出任何解释文字或 markdown 代码块。
            输出格式：
            [{"title":"书名","description":"这本书主要记录什么","sections":[{"name":"二级主题名","point":"一句话说明","cards":["C1","C3"]}]}]""";

    /** 精读阶段：单篇文章 → 理解卡片（同一篇文章可拆多张） */
    private static final String READ_PROMPT = """
            你是「喵的整理」的读书猫：正在把一篇知识文章仔细读懂，产出「理解卡片」。
            规则：
            1. 通读全文，抓住文章里所有值得沉淀的知识点，拆成 1~3 张理解卡片；
            2. 每张卡片 = topic（这张卡讲什么主题，简短）+ point（读完后用自己的话把理解说清楚，1~2 句，保留关键细节）；
            3. 卡片是「读完后的理解」，不是原文摘抄——同一篇文章可以拆出多张不同主题的卡片；
            4. 只输出一个 JSON 数组，不要输出任何解释文字或 markdown 代码块。
            输出格式：
            [{"topic":"卡片主题","point":"这张卡的理解"}]""";

    /** 按书聚合精读：整本书一次读透，产出四类知识点 + 书内关联（kind/no/usage/links） */
    private static final String BOOK_PROMPT = """
            你是「喵的整理」的读书猫：正在把一本书的全文一次读完，为这本书提炼「知识点图谱」。
            规则：
            1. 通读全书，提炼 3~12 个知识点，分四类（kind 枚举必须用这些值）：
               KEY=关键（理解这本书绕不开的核心概念）、FOCUS=重点（作者着墨最多的论点）、
               DIFFICULT=难点（容易卡住或误解的地方）、FACT=知识点（事实/数据/术语）；
            2. 每个知识点 = no（P1、P2…全书内唯一递增）+ name（知识点名称，短而准）
               + summary（一句话简介，自己的话不是抄原文）
               + usage（什么时候会用到这个知识点，1~3 条场景，数组）
               + kind（四类枚举之一）；
            3. links：同书内与之关联的其他知识点 no 数组（如 ["P2","P5"]）——每个知识点都应有关联：
               或主动关联别人（links 非空），或被别人关联；孤立的点说明它可能不该单独成点；
            4. srcs：这个知识点主要从哪几篇文章读出来的——书里每篇正文前有 [A1]《标题》 这样的编号标记，
               写成编号数组（如 ["A1","A3"]）；通常 1~2 篇，确实通篇融合才多标；
            5. 只输出一个 JSON 数组，不要输出任何解释文字或 markdown 代码块。
            输出格式：
            [{"no":"P1","kind":"KEY","name":"知识点名称","summary":"一句话简介","usage":["什么时候用","另一个场景"],"links":["P3"],"srcs":["A1"]}]""";

    /** ENRICH：对重点/难点逐点读厚——产出补充展开正文 */
    private static final String ENRICH_PROMPT = """
            你是「喵的整理」的读书猫：正在把一个知识点「读厚」——围绕它写一段补充展开。
            规则：
            1. 只输出一段正文（不是 JSON），150~400 字，结构自然组织：先说它为什么重要/难在哪，再给一个具体例子或类比，最后点出常见误解或使用注意；
            2. 基于你自己的理解组织语言，禁止整段照抄原文；
            3. 不要 markdown 标题，不要列表符号，就是一段连贯的话。""";

    /** 单书全文护栏：超过则每篇内容按比例截短（防 prompt 爆炸） */
    private static final int MAX_BOOK_BODY = 9000;
    /** 单个知识点详情最多补全的字符上限（门禁） */
    private static final int MAX_DETAIL_LEN = 800;

    private final CatRepository catRepo;
    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final ContentItemRepository contentRepo;
    private final CatWorkspaceService catWorkspace;
    private final LibrarySyncService librarySync;
    private final MiaomiaoService miaomiao;
    private final ExtractService extractService;
    private final ObjectMapper objectMapper;
    private final StudySearchService studySearch;
    private final UnderstandingCardRepository understandingRepo;
    private final UnderstandingCardSourceRepository sourceRepo;
    private final LearnCardRepository learnRepo;
    private final SkillAssembler skills;
    private final MiaomiaoTraceService trace;
    /** 成书归位（P0-3）：把卡片判进节 —— 比内容归档更细一层的分类，laya 主导、LLM 兜底 */
    private final LayaCardRouter cardRouter;

    /** 内存任务表（单机足够） */
    private final Map<String, ReadingTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, (r) -> {
        Thread t = new Thread(r, "miao-reading");
        t.setDaemon(true);
        return t;
    });

    public ReadingService(CatRepository catRepo, BookTypeRepository typeRepo, BookRepository bookRepo,
                          ContentItemRepository contentRepo,
                          CatWorkspaceService catWorkspace, LibrarySyncService librarySync,
                          MiaomiaoService miaomiao, ExtractService extractService, ObjectMapper objectMapper,
                          UnderstandingCardRepository understandingRepo, LearnCardRepository learnRepo,
                          UnderstandingCardSourceRepository sourceRepo, SkillAssembler skills,
                          StudySearchService studySearch, MiaomiaoTraceService trace,
                          LayaCardRouter cardRouter) {
        this.catRepo = catRepo;
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.contentRepo = contentRepo;
        this.catWorkspace = catWorkspace;
        this.librarySync = librarySync;
        this.miaomiao = miaomiao;
        this.extractService = extractService;
        this.objectMapper = objectMapper;
        this.understandingRepo = understandingRepo;
        this.sourceRepo = sourceRepo;
        this.learnRepo = learnRepo;
        this.skills = skills;
        this.studySearch = studySearch;
        this.trace = trace;
        this.cardRouter = cardRouter;
    }

    /** 归纳完成后刷新检索索引（失败只打日志，不影响任务完成态） */
    private void refreshSearchIndex(Long catId) {
        try {
            Map<String, Object> r = studySearch.reindexCat(catId);
            log.info("[喵的整理] 检索索引已刷新: {}", r);
        } catch (Exception e) {
            log.warn("[喵的整理] 刷新检索索引失败 cat={}: {}", catId, e.getMessage());
        }
    }

    // ================= 查询：书架 + 素材概览 =================

    /** 该猫的整理书架：reading/ 下已有的书大纲 + 素材概览 */
    public Map<String, Object> list(Long catId) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        List<Map<String, Object>> books = readShelf(cat.getWorkspacePath());

        // 素材概览：书库内容 + 会话记忆规模（可读书架 = 自有分类的书 + 共享预设分类的书，只数该喵名下条目）
        int contentCount = 0;
        int bookCount = 0;
        for (Book book : readableBooks(catId)) {
            long n = contentRepo.countByBookIdAndCatId(book.getId(), catId);
            if (n > 0) { bookCount++; contentCount += (int) n; }
        }
        List<Map<String, Object>> turns = collectChats(cat);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("typeCount", catTypeIds(catId).size());
        overview.put("bookCount", bookCount);
        overview.put("contentCount", contentCount);
        overview.put("sessionCount", sessionCount(cat));
        overview.put("chatTurns", turns.size());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("catId", catId);
        r.put("catName", cat.getName());
        r.put("icon", cat.getIcon());
        r.put("color", cat.getColor());
        r.put("mode", miaomiao.liveMode(catId) ? "live" : "mock");
        r.put("agentName", miaomiao.agent(catId).name());
        r.put("overview", overview);
        r.put("books", books);
        return r;
    }

    /** 侧栏菜单徽标：某只喵的整理卡数与书数（读卡表+书架文件，轻量不调模型） */
    public Map<String, Integer> readingStats(Long catId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("cards", (int) understandingRepo.countByCatId(catId));
        int books = catRepo.findById(catId)
                .map(cat -> readShelf(cat.getWorkspacePath()).size())
                .orElse(0);
        m.put("books", books);
        return m;
    }

    /** 读某猫工作区 reading/ 目录下全部书大纲（按文件名排序） */
    public List<Map<String, Object>> readShelf(String workspacePath) {
        List<Map<String, Object>> books = new ArrayList<>();
        Path dir = readingDir(workspacePath);
        if (!Files.isDirectory(dir)) return books;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().toList();
            for (Path p : files) books.add(parseBookMd(p));
        } catch (Exception e) {
            log.warn("[喵的整理] 读取书架失败 ({}): {}", workspacePath, e.getMessage());
        }
        return books;
    }

    /** 读单本书的 md 原文（供前端查看文件本体） */
    public Map<String, Object> readFile(Long catId, String name) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("非法文件名");
        }
        if (!name.endsWith(".md")) throw new IllegalArgumentException("只支持 md 文件");
        Path p = readingDir(cat.getWorkspacePath()).resolve(name).normalize();
        if (!p.startsWith(readingDir(cat.getWorkspacePath())) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + name);
        }
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", name);
            m.put("markdown", Files.readString(p, StandardCharsets.UTF_8));
            return m;
        } catch (Exception e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
    }

    // ================= 生成任务 =================

    public ReadingTask start(Long catId) {
        return start(catId, "auto");
    }

    /**
     * mode=auto（默认）：自动增量——库中已有知识点且书骨架存在时，只精读新内容并把新卡归位进已有书；
     * mode=full：手动全量——清空重建（卡片/关联/学习卡/全部 md 重新生成）。
     */
    public ReadingTask start(Long catId, String mode) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        /* 同猫防重：已经在整理就不允许再触发（手动按钮与定时巡逻共用此闸） */
        for (ReadingTask t : tasks.values()) {
            if (catId.equals(t.catId) && "running".equals(t.status)) {
                throw new IllegalStateException("「" + cat.getName() + "」正在整理中，等这轮结束再说");
            }
        }
        // 素材：书架上的书（按书聚合精读）+ 会话记录（也做知识点化）
        List<BookGroup> groups = groupBooks(cat);
        List<Map<String, Object>> turns = collectChats(cat);
        if (groups.isEmpty() && turns.isEmpty()) {
            throw new IllegalArgumentException("这只喵还没有任何书库内容和会话记忆，先投入点内容或和它聊聊吧");
        }

        ReadingTask task = new ReadingTask();
        task.id = UUID.randomUUID().toString().substring(0, 8);
        task.catId = catId;
        task.catName = cat.getName();
        task.mode = "full".equals(mode) ? "full" : "auto";
        task.status = "running";
        task.stage = "read";
        /* 增量模式先做零变化检查（可能零消耗直接结束），文案不能预支「精读/回味」 */
        task.current = "full".equals(task.mode)
                ? "全量重归纳：准备精读 " + groups.size() + " 本书 · 回味 " + turns.size() + " 轮会话…"
                : "增量检查中：" + groups.size() + " 本书 · " + turns.size() + " 轮会话（无变化零消耗）…";
        tasks.put(task.id, task);

        List<ContentItem> items = groups.stream().flatMap(g -> g.items().stream()).toList();
        trace.beginWork(catId, "⚙️", task.current);
        executor.submit(() -> run(task, cat, items, turns));
        log.info("[喵的整理] 启动任务 {}: 猫「{}」mode={} 素材 {} 本书 / {} 轮会话",
                task.id, cat.getName(), task.mode, groups.size(), turns.size());
        return task;
    }

    /**
     * 归纳前预估：列出本次将处理哪些文档——增量 = 未读过的 + 有修改的（指纹变化）；全量 = 全部。
     * 给主人点「整理」按钮后的确认弹窗展示：全量处理 N 篇 / 增量处理 M 篇（新增 x + 修改 y）。
     */
    public Map<String, Object> plan(Long catId) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        List<BookGroup> groups = groupBooks(cat);
        List<Map<String, Object>> turns = collectChats(cat);
        List<ContentItem> items = groups.stream().flatMap(g -> g.items().stream()).toList();
        Map<Long, String> cur = fingerprintMap(items);
        Map<Long, String> stored = new LinkedHashMap<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (UnderstandingCardSource s : sourceRepo.findByCatId(cat.getId())) {
            seen.add(s.getContentId());
            stored.putIfAbsent(s.getContentId(), s.getFingerprint());
        }
        Map<Long, String> bookTitleOf = new LinkedHashMap<>();
        for (BookGroup g : groups) for (ContentItem it : g.items()) bookTitleOf.put(it.getId(), g.book().getTitle());
        List<Map<String, Object>> pending = new ArrayList<>();
        int fresh = 0, updated = 0, processed = 0;
        for (ContentItem it : items) {
            String f = stored.get(it.getId());
            String reason;
            if (f == null) reason = seen.contains(it.getId()) ? null : "new";
            else reason = f.equals(cur.get(it.getId())) ? null : "updated";
            if (reason == null) { processed++; continue; }
            if ("new".equals(reason)) fresh++; else updated++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.getId());
            row.put("title", it.getTitle());
            row.put("bookTitle", plain(bookTitleOf.get(it.getId())));
            row.put("reason", reason);
            pending.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("processedCount", processed);
        out.put("freshCount", fresh);
        out.put("updatedCount", updated);
        out.put("chatTurns", turns.size());
        out.put("chatChanged", chatChanged(cat, turns));
        out.put("pending", pending);
        return out;
    }

    /** 会话指纹 = SHA-256(全部轮次的 user|assistant|prefold 拼接)：与上次整理时比对，没变就不回味、不烧 token */
    private String chatFingerprint(List<Map<String, Object>> turns) {
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : turns) {
            sb.append(t.get("user")).append('\u0001').append(t.get("assistant")).append('\u0001')
              .append(t.getOrDefault("prefold", "")).append('\u0002');
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(sb.length()); /* 理论不可达：退化用长度当指纹 */
        }
    }

    private Path chatFpPath(Cat cat) {
        return catWorkspace.workspaceRoot(cat.getWorkspacePath()).resolve(READING_DIR + "/_chat.fp");
    }

    private String readChatFp(Cat cat) {
        try {
            Path p = chatFpPath(cat);
            return Files.isRegularFile(p) ? Files.readString(p).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 会话是否有新变化（与上次整理时落盘的指纹比对） */
    private boolean chatChanged(Cat cat, List<Map<String, Object>> turns) {
        String cur = chatFingerprint(turns);
        if (cur.isEmpty()) return false; /* 没有会话素材，谈不上变化 */
        String stored = readChatFp(cat);
        return !cur.equals(stored);
    }

    /**
     * 把「本轮已读过」的文章指纹落库（增量防重复的关键）。
     * 产出新卡的文章由 persistCards 记指纹；重复主题被旧卡挡住的文章没有新卡，
     * 若不在这里补记指纹，下轮增量会永远认为它「有修改」而反复重读同一篇（token 泄漏）。
     * 无卡文章的指纹行 no 用 "SELF" 占位（不参与知识点关联，仅供增量判重）。
     */
    private void markRead(Cat cat, List<ContentItem> fresh, Map<Long, String> fpCur) {
        if (fresh == null || fresh.isEmpty()) return;
        for (ContentItem it : fresh) {
            String fp = fpCur.get(it.getId());
            if (fp == null) continue;
            List<UnderstandingCardSource> rows = sourceRepo.findByCatIdAndContentId(cat.getId(), it.getId());
            UnderstandingCardSource s = rows.isEmpty() ? new UnderstandingCardSource() : rows.get(0);
            if (s.getId() == null) {
                s.setCatId(cat.getId());
                s.setNo("SELF");
                s.setContentId(it.getId());
            }
            s.setFingerprint(fp);
            sourceRepo.save(s);
        }
    }

    /** 文章指纹 = SHA-256(标题|摘要|正文)：内容一旦修改指纹即变，用于增量归纳判定「有修改需重读」 */
    private Map<Long, String> fingerprintMap(List<ContentItem> items) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (ContentItem it : items) out.put(it.getId(), fingerprint(it));
        return out;
    }

    private String fingerprint(ContentItem it) {
        String base = (it.getTitle() == null ? "" : it.getTitle()) + "|"
                + (it.getSummary() == null ? "" : it.getSummary()) + "|"
                + (it.getRawText() == null ? "" : it.getRawText());
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(base.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(base.hashCode());
        }
    }

    /** 增量判定：无关联记录 = 新文章；指纹不一致 = 文章有修改需重读；其余 = 已处理跳过（旧数据无指纹只认「没读过」） */
    private boolean needsRead(ContentItem it, java.util.Set<Long> seen, Map<Long, String> stored, Map<Long, String> cur) {
        String f = stored.get(it.getId());
        if (f == null) return !seen.contains(it.getId());
        return !f.equals(cur.get(it.getId()));
    }

    /** 内容/书本详情「喵喵提取」用：这批内容的整理状态。
     *  是否读过 = 权威指纹判定（UnderstandingCardSource 有记录且 SHA-256 指纹一致，markRead 读过就留痕无论是否产卡）；
     *  cards = 卡表 contentId 直接反查的理解卡片（不绕来源表的 no 匹配——历史批次编号撞号，绕表会张冠李戴）。
     *  内容条目自身没有归属喵时用 fallbackCatId（书的分类归属）兜底。 */
    public Map<String, Object> tidyState(List<ContentItem> contents, Long fallbackCatId) {
        int tidied = 0;
        Map<Long, List<UnderstandingCard>> byCat = new LinkedHashMap<>(); /* catId → 该批内容直接产出的卡 */
        for (ContentItem it : contents) {
            Long catId = it.getCatId() != null ? it.getCatId() : fallbackCatId;
            if (catId == null) continue;
            List<UnderstandingCardSource> rows = sourceRepo.findByCatIdAndContentId(catId, it.getId());
            if (rows.stream().anyMatch(s -> fingerprint(it).equals(s.getFingerprint()))) tidied++;
            List<UnderstandingCard> cards = understandingRepo.findByCatIdAndContentIdOrderByIdAsc(catId, it.getId());
            if (!cards.isEmpty()) byCat.computeIfAbsent(catId, k -> new ArrayList<>()).addAll(cards);
        }
        List<Map<String, Object>> cardRows = new ArrayList<>();
        for (Map.Entry<Long, List<UnderstandingCard>> e : byCat.entrySet()) {
            for (UnderstandingCard r : e.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("catId", e.getKey());
                row.put("no", displayNo(r));
                row.put("topic", r.getTopic() == null ? "" : r.getTopic());
                row.put("bookTitle", r.getBookTitle() == null ? "" : r.getBookTitle());
                cardRows.add(row);
            }
        }
        return Map.of("total", contents.size(), "tidied", tidied, "cards", cardRows);
    }

    /** 卡片展示编号：落库时未分配（历史批次）按行 id 回填，保证详情弹窗可定位 */
    private String displayNo(UnderstandingCard r) {
        return r.getNo() == null || r.getNo().isBlank() || "null".equals(r.getNo())
                ? String.format("P%04d", r.getId()) : r.getNo();
    }

    /**
     * 卡片编号修复：no 缺失（历史批次落库未分配）或撞号（两批整理各自从 P0001 编起）时，
     * 保留先入库（id 小）的编号，其余按当前最大编号顺延重编；来源表 no 与卡间 links 按旧→新映射同步。
     * runFull 编号续接前与启动时各跑一次，保证 maxNo 计算不受脏编号干扰、反追踪不再张冠李戴。
     */
    @Transactional
    public void fixCardNos(Long catId) {
        List<UnderstandingCard> cards = understandingRepo.findByCatIdOrderByIdAsc(catId);
        if (cards.isEmpty()) return;
        Set<String> used = new HashSet<>();
        Map<String, String> rename = new LinkedHashMap<>(); /* 旧 no → 新 no（仅被改号的卡） */
        int maxNo = 0;
        boolean dirty = false;
        for (UnderstandingCard r : cards) {
            String no = r.getNo();
            if (no != null && !no.isBlank() && !"null".equals(no)) {
                try { maxNo = Math.max(maxNo, Integer.parseInt(no.substring(1))); } catch (Exception ignore) { }
            }
        }
        for (UnderstandingCard r : cards) {
            String no = r.getNo();
            if (no == null || no.isBlank() || "null".equals(no)) {
                String fresh = String.format("P%04d", ++maxNo);
                while (!used.add(fresh)) fresh = String.format("P%04d", ++maxNo);
                r.setNo(fresh);
                dirty = true;
            } else if (!used.add(no)) { /* 撞号：后来者重编 */
                String fresh = String.format("P%04d", ++maxNo);
                while (!used.add(fresh)) fresh = String.format("P%04d", ++maxNo);
                r.setNo(fresh);
                rename.put(no, fresh);
                dirty = true;
            }
        }
        if (!dirty) return;
        understandingRepo.saveAll(cards);
        if (!rename.isEmpty()) {
            List<UnderstandingCardSource> sources = sourceRepo.findByCatId(catId);
            boolean srcChanged = false;
            for (UnderstandingCardSource s : sources) {
                if (s.getNo() != null && rename.containsKey(s.getNo())) {
                    s.setNo(rename.get(s.getNo()));
                    srcChanged = true;
                }
            }
            if (srcChanged) sourceRepo.saveAll(sources);
            /* 卡间 links 同步：引用旧编号的映射到新编号 */
            for (UnderstandingCard r : cards) {
                List<String> links = parseJsonArray(r.getLinks());
                if (links.isEmpty()) continue;
                boolean changed = false;
                List<String> mapped = new ArrayList<>();
                for (String l : links) {
                    String m = rename.getOrDefault(l, l);
                    if (!m.equals(l)) changed = true;
                    mapped.add(m);
                }
                if (changed) {
                    try {
                        r.setLinks(objectMapper.writeValueAsString(mapped));
                    } catch (Exception ignore) { }
                }
            }
            understandingRepo.saveAll(cards);
        }
        log.info("[卡片编号] 喵 {} 编号修复完成：重编 {} 张（含缺号补齐），来源表与 links 已同步", catId, rename.size());
    }

    /**
     * 孤儿卡清理：bookFile 对应的书 md 已不在 reading/ 目录（旧一轮整理的书被全量落盘淘汰），
     * 这类卡是老管线残留——主题与新卡重复、挂在已不存在的书上，导致「整理的东西不对」。
     * 来源指纹行保留（读过留痕，内容不会被重新整理产重复卡）。
     */
    @Transactional
    public void pruneOrphanCards(Long catId) {
        Cat cat = catRepo.findById(catId).orElse(null);
        if (cat == null) return;
        Path dir = readingDir(cat.getWorkspacePath());
        List<UnderstandingCard> cards = understandingRepo.findByCatIdOrderByIdAsc(catId);
        List<Long> orphans = new ArrayList<>();
        for (UnderstandingCard r : cards) {
            String file = r.getBookFile();
            if (file == null || file.isBlank()) continue; /* 无书卡（会话类）不清理 */
            if (!Files.isRegularFile(dir.resolve(file).normalize())) orphans.add(r.getId());
        }
        if (orphans.isEmpty()) return;
        understandingRepo.deleteAllById(orphans);
        log.info("[卡片清理] 喵 {} 移除孤儿卡 {} 张（旧书 md 已淘汰，指纹留痕防重读）", catId, orphans.size());
    }

    /** 去掉标题自带的书名号（book 表标题多带《》，拼接展示时避免双书名号） */
    private String plain(String s) {
        return s == null ? "" : s.replaceAll("^[《〈【\"]+", "").replaceAll("[》〉】\"]+$", "");
    }

    /** 学习卡阶段：基于书的二级主题 + 理解卡片，出 recall/feynman 学习卡（学透） */
    private static final String LEARN_PROMPT = """
            你是「喵的整理」的学习教练猫：把一本书的理解卡片提炼成「学习卡片」，帮主人在碎片时间主动回忆。
            规则：
            1. 每本书最多 5 张卡，宁少勿多——只出「值得记住的核心」；
            2. recall 卡：front = 一个值得回答的问题；back = 详细讲解，按「结论先行 → 展开讲透（为什么成立 / 怎么用 / 典型例子或反例）→ 一句话收束」的结构写，120~250 字，基于理解而非抄写原文；
            3. feynman 卡：front = 「用你自己的话解释 X」；back = 参考讲法：一个贴切的比喻 + 一个具体例子 + 最容易理解错的地方，120~250 字；
            4. 每张卡给 srcs：这张卡提炼自哪些理解卡片（用理解卡的编号，如 P0007）；
            5. 只输出一个 JSON 数组，不要输出任何解释文字或 markdown 代码块。
            输出格式：
            [{"kind":"recall","front":"问题","back":"详细讲解（120~250 字）","srcs":["P0007"]}]""";

    /** INSERT：增量归位——新知识点塞进已有书的章节树（主题不搭时才建新书） */
    private static final String INSERT_PROMPT = """
            你是「喵的整理」的读书猫：新精读产出了一批知识点卡片，请把它们归位到喵已有的书架上。
            规则：
            1. 每张卡必须给出归位：bookFile（已有书的文件名）+ chapter（该书的某个一级主题）+ section（该章下的某个二级主题）；
            2. 优先放进语义最贴的已有章/节；该章下确实缺一个主题时，给出新的 section 名称（会追加到章尾）；
            3. 实在与所有已有书都不搭时，bookFile 用 "NEW"，并给 title（新书名）/description/chapter/section（将创建新书）；
            4. 归位要考虑书的整体主题与学习路径，宁可用新节也不要硬塞进不相关的节；
            5. 只输出一个 JSON 数组，不要输出任何解释文字。
            输出格式：
            [{"no":"P0087","bookFile":"01-某某书.md","chapter":"某章","section":"某节"}]
            建新书时：[{"no":"P0090","bookFile":"NEW","title":"新书名","description":"一句话","chapter":"章名","section":"节名"}]""";

    /** 归位门禁：每张新卡都要有输出记录（宽松——个别漏归位的卡由「待归位」兜底） */
    private HarnessSpec insertSpec() {
        QualityGate gate = (out, ctx) -> {
            List<?> list = (List<?>) out;
            if (list.isEmpty()) return GateResult.fail("没有产出任何归位记录");
            return GateResult.ok();
        };
        return new HarnessSpec("新知识点归位进书架", "JSON 数组，每卡一条归位记录", List.of("书架骨架必须存在"), gate);
    }

    /** 任务入口：full = 全量重建（runFull）；auto = 自动增量（库中已有卡且书骨架存在时只读新内容归位，否则首次全量） */
    private void run(ReadingTask task, Cat cat, List<ContentItem> items, List<Map<String, Object>> turns) {
        /* 喵喵足迹镜像：整理期间每 2 秒把任务进度同步到足迹；任务结束写总结事件（含 token 消耗） */
        Thread mirror = new Thread(() -> {
            while ("running".equals(task.status)) {
                trace.working(task.catId, task.stage, task.stageDone, task.stageTotal, task.current);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if ("done".equals(task.status)) trace.endWork(task.catId, "✅", task.current);
            else trace.endWork(task.catId, "❌", "整理失败：" + (task.error == null ? "未知原因" : task.error));
        }, "miao-trace-mirror");
        mirror.setDaemon(true);
        mirror.start();

        boolean full = "full".equals(task.mode)
                || understandingRepo.countByCatId(cat.getId()) == 0
                || readSkeleton(cat) == null;
        if (full) {
            runFull(task, cat, items, turns);
        } else {
            runAuto(task, cat, items, turns);
        }
    }

    /** 增量阅读：只精读未读过的内容 → 新卡落库 → 归位进已有书骨架 → 增量读厚；不动旧卡与学习卡 */
    private void runAuto(ReadingTask task, Cat cat, List<ContentItem> items, List<Map<String, Object>> turns) {
        try {
            MiaoMiaoAgent agent = miaomiao.agent(cat.getId());
            boolean live = miaomiao.liveMode(cat.getId());
            AtomicInteger budget = new AtomicInteger(PipelineRunner.DEFAULT_TASK_BUDGET);
            Map<String, String> detailCache = loadDetailCache(cat.getId());
            Map<String, Object> skeleton = readSkeleton(cat);

            /* 编号修复：no 缺失/撞号的卡先顺延重编（来源表与 links 同步），防止本轮编号续接撞历史批次 */
            fixCardNos(cat.getId());

            /* 旧卡索引：bookId|topic → 旧卡 row（增量去重）；编号续接 */
            Map<String, UnderstandingCard> oldByKey = new LinkedHashMap<>();
            int maxNo = 0;
            for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(cat.getId())) {
                oldByKey.put(r.getBookId() + "|" + r.getTopic(), r);
                try {
                    maxNo = Math.max(maxNo, Integer.parseInt(r.getNo().replace("P", "")));
                } catch (Exception ignore) { }
            }
            /* 已读文章指纹表：无指纹记录=新增；指纹不一致=有修改需重读；一致=已处理跳过 */
            java.util.Set<Long> seen = new java.util.HashSet<>();
            Map<Long, String> fpStored = new LinkedHashMap<>();
            for (UnderstandingCardSource s : sourceRepo.findByCatId(cat.getId())) {
                seen.add(s.getContentId());
                fpStored.putIfAbsent(s.getContentId(), s.getFingerprint());
            }

            List<Map<String, Object>> newCards = new ArrayList<>();
            Map<String, String> noMap = new LinkedHashMap<>();   // 书内编号 → 全局编号（含复用旧卡，保 links 不断）
            int[] counter = {maxNo + 1};
            int freshBooks = 0;

            /* ===== READ_DEEP（增量）：只处理「未读过 / 有修改」的文章 ===== */
            List<BookGroup> allGroups = groupBooks(cat);
            Map<Long, String> fpCur = fingerprintMap(allGroups.stream().flatMap(g -> g.items().stream()).toList());
            List<BookGroup> toRead = new ArrayList<>();
            for (BookGroup g : allGroups) {
                if (g.items().stream().anyMatch(it -> needsRead(it, seen, fpStored, fpCur))) toRead.add(g);
            }
            /* 会话指纹：没变化就绝不回味（token 敏感——零变化时整轮零消耗） */
            String chatFp = chatFingerprint(turns);
            boolean chatChanged = !chatFp.isEmpty() && !chatFp.equals(readChatFp(cat));
            if (toRead.isEmpty() && !chatChanged) {
                task.status = "done";
                task.current = "文章与会话都没有新变化，喵一分 token 也没花。";
                trace.event(cat.getId(), "💤", "增量检查完成：无新增、无修改、会话无变化 → 本轮零消耗");
                return;
            }
            long tReadStart = trace.currentTokens(cat.getId());
            task.stage = "read";
            task.stageDone = 0;
            task.stageTotal = toRead.size() + (chatChanged && !turns.isEmpty() ? 1 : 0);
            for (BookGroup g : toRead) {
                List<ContentItem> fresh = g.items().stream()
                        .filter(it -> needsRead(it, seen, fpStored, fpCur)).toList();
                if (fresh.isEmpty()) continue;
                Map<String, Long> srcMap = new LinkedHashMap<>();
                String body = bookBody(fresh, srcMap);
                if (body.isBlank()) continue;
                if (budget.get() <= 0) { task.current = "读厚预算耗尽，剩余内容下次增量补读"; break; }
                freshBooks++;
                task.stageDone = freshBooks;
                task.current = "增量精读《" + clip(plain(g.book().getTitle()), 20) + "》第 " + freshBooks + "/" + toRead.size()
                        + " 本（" + fresh.size() + " 篇待处理）…";
                try {
                    HarnessRunContext ctx = newCtx(cat.getId(), "READ_DEEP",
                            "增量精读书的新增内容，产出知识点图谱。出参标准：JSON 数组 1~8 个知识点，"
                                    + "每点含 no/name/summary/usage/kind/links/srcs。",
                            buildBookPrompt(g, body), budget, task, live);
                    List<Map<String, Object>> piece = PipelineRunner.runStage(bookSpec(), ctx,
                            x -> chatPoints(agent, BOOK_PROMPT, x.buildPrompt("读懂新增内容，输出知识点图谱（只输出 JSON 数组）")),
                            x -> List.of());
                    for (Map<String, Object> p : piece) {
                        String local = String.valueOf(p.get("no"));
                        String topic = String.valueOf(p.get("topic"));
                        UnderstandingCard old = oldByKey.get(g.book().getId() + "|" + topic);
                        if (old != null) {
                            /* 旧卡已存在：不重复落库，但书内编号映射指向旧卡（保住同批 links） */
                            noMap.put(local, old.getNo());
                            continue;
                        }
                        String globalNo = String.format("P%04d", counter[0]++);
                        p.put("localNo", local);
                        p.put("no", globalNo);
                        p.put("bookId", g.book().getId());
                        p.put("bookTitle", clip(g.book().getTitle(), 300));
                        p.put("src", "《" + g.book().getTitle() + "》");
                        fillSources(p, srcMap, g.items());
                        noMap.put(local, globalNo);
                        newCards.add(p);
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 增量读《{}》失败，跳过: {}", g.book().getTitle(), e.getMessage());
                }
            }
            /* 读厚完成：记录本阶段 token 消耗 */
            if (freshBooks > 0) {
                long used = trace.currentTokens(cat.getId()) - tReadStart;
                trace.event(cat.getId(), "📖", "读厚完成：精读 " + freshBooks + " 本，新增 " + newCards.size() + " 个知识点（本阶段消耗 " + used + " tokens）");
            }
            /* 关键防重复：本批读过的文章，无论有没有产出新卡（重复主题会被旧卡挡住），
               都把指纹落库——否则每轮都认为「有修改」反复重读同一篇，token 永远烧不完 */
            for (BookGroup g : toRead) {
                markRead(cat, g.items().stream().filter(it -> needsRead(it, seen, fpStored, fpCur)).toList(), fpCur);
            }
            /* ===== 会话增量：只在会话指纹变化时回味（没新对话绝不烧 token） ===== */
            if (!turns.isEmpty() && chatChanged && budget.get() > 0) {
                long tChatStart = trace.currentTokens(cat.getId());
                task.current = "正在回味与主人的 " + turns.size() + " 轮对话…";
                try {
                    HarnessRunContext ctx = newCtx(cat.getId(), "READ_DEEP",
                            "回味与主人的对话，把值得沉淀的知识做成知识点。出参标准：JSON 数组 1~6 个。",
                            buildChatPrompt(turns), budget, task, live);
                    List<Map<String, Object>> piece = PipelineRunner.runStage(chatSpec(), ctx,
                            x -> chatPoints(agent, READ_PROMPT, x.buildPrompt("读懂这段对话，输出知识点（只输出 JSON 数组）")),
                            x -> List.of());
                    for (Map<String, Object> p : piece) {
                        String local = String.valueOf(p.get("no"));
                        String topic = String.valueOf(p.get("topic"));
                        UnderstandingCard old = oldByKey.get("null|" + topic);
                        if (old != null) { noMap.put(local, old.getNo()); continue; }
                        p.put("no", String.format("P%04d", counter[0]++));
                        p.put("kind", UnderstandingCard.KIND_FACT);
                        p.put("bookId", null);
                        p.put("bookTitle", "与主人的会话");
                        p.put("src", "与主人的会话");
                        noMap.put(local, String.valueOf(p.get("no")));
                        newCards.add(p);
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 会话卡片化失败，跳过: {}", e.getMessage());
                }
                /* 回味完成：更新会话指纹（下次同样的对话不再回味）+ 记录本阶段 token */
                librarySync.writeArtifact(cat.getWorkspacePath(), READING_DIR + "/_chat.fp", chatFp);
                final long used = trace.currentTokens(cat.getId()) - tChatStart;
                trace.event(cat.getId(), "💬", "回味完成：与主人的 " + turns.size() + " 轮对话已沉淀检查（本阶段消耗 " + used + " tokens）");
            } else if (!turns.isEmpty()) {
                trace.event(cat.getId(), "💬", "会话无新变化，跳过回味（省 token）");
            }
            /* links 映射（含复用旧卡的编号） */
            for (Map<String, Object> c : newCards) {
                Object links = c.get("links");
                if (links instanceof List<?> l) {
                    List<String> mapped = new ArrayList<>();
                    for (Object n : l) {
                        String g2 = noMap.get(String.valueOf(n));
                        if (g2 != null && !g2.equals(c.get("no")) && !mapped.contains(g2)) mapped.add(g2);
                    }
                    c.put("links", mapped);
                }
            }
            if (newCards.isEmpty()) {
                task.status = "done";
                /* 诚实交代 token 去向：没产出新卡 ≠ 没花钱——读厚/回味都是真实 LLM 工作 */
                long used = trace.currentTokens(cat.getId()) - tReadStart;
                if (freshBooks > 0) {
                    task.current = "精读了 " + freshBooks + " 本书的新内容（消耗 " + used + " tokens）：主题与已有知识点重合，没有产出新卡。想重新组织结构可点「全量重归纳」。";
                } else if (!turns.isEmpty() && chatChanged) {
                    task.current = "回味了与主人的 " + turns.size() + " 轮对话（消耗 " + used + " tokens）：聊的内容与已有知识点重合，没有新知识点。素材都精读过了，无新增内容。";
                } else {
                    task.current = "没有新内容需要读：素材都精读过了，知识点没有变化。想重新组织结构可点「全量重归纳」。";
                }
                return;
            }
            task.cardCount = newCards.size();

            /* 落库新卡 + 增量写新卡 md（不清旧文件） */
            List<UnderstandingCard> cardRows = saveCards(cat, batchId(task.id), newCards);
            writePointMds(cat.getWorkspacePath(), newCards, cardRows);

            /* ===== INSERT：把新卡归位进已有书骨架（允许新节/新书） ===== */
            task.current = "正在把 " + newCards.size() + " 张新知识点归位进已有书架…";
            insertPhase(task, cat, agent, live, newCards, cardRows, skeleton, budget);
            int enriched = enrichPhase(task, cat, agent, live, newCards, cardRows, detailCache, items, budget);
            task.enrichCount = enriched;
            librarySync.commit("喵的整理: " + cat.getName() + " 增量阅读 · 新增 " + newCards.size()
                    + " 个知识点（读厚 " + enriched + "）");
            refreshSearchIndex(cat.getId());
            /* 书架自动整理：归纳后体检并自动执行合并/分裂，不再请主人确认 */
            List<String> tidy = autoTidy(cat.getId());
            task.status = "done";
            task.current = "增量阅读完成：新读 " + freshBooks + " 本书的新内容 · 新增 " + newCards.size()
                    + " 个知识点（读厚 " + enriched + "）· 已归位进书架"
                    + (tidy.isEmpty() ? "" : " · 🧹 书架已自动整理：" + String.join("、", tidy));
            log.info("[喵的整理] 增量任务 {} 完成：新卡 {} · 读厚 {} · 自动整理 {} 项", task.id, newCards.size(), enriched, tidy.size());
        } catch (Exception e) {
            log.error("[喵的整理] 增量任务 {} 失败: {}", task.id, e.getMessage(), e);
            task.status = "error";
            task.error = e.getMessage();
        }
    }

    /** 出处文章编号 → 真实文章 id + 标题（map 上补 contentIds/srcTitles/contentId） */
    private void fillSources(Map<String, Object> p, Map<String, Long> srcMap, List<ContentItem> items) {
        List<Long> contentIds = new ArrayList<>();
        List<String> srcTitles = new ArrayList<>();
        if (p.get("srcs") instanceof List<?> keys) {
            for (Object k : keys) {
                Long cid = srcMap.get(String.valueOf(k).strip().toUpperCase());
                if (cid != null && !contentIds.contains(cid)) {
                    contentIds.add(cid);
                    items.stream().filter(it -> it.getId().equals(cid)).findFirst()
                            .ifPresent(it -> srcTitles.add(clip(it.getTitle(), 80)));
                }
            }
        }
        p.put("contentIds", contentIds);
        p.put("srcTitles", srcTitles);
        p.put("contentId", contentIds.isEmpty() ? null : contentIds.get(0));
    }

    /** INSERT：把新卡归位进已有书（优先已有章/节，允许追加新节；实在不适配时建新书）。完成后重渲染书 md */
    private void insertPhase(ReadingTask task, Cat cat, MiaoMiaoAgent agent, boolean live,
                             List<Map<String, Object>> newCards, List<UnderstandingCard> cardRows,
                             Map<String, Object> skeleton, AtomicInteger budget) {
        task.stage = "thin";
        task.stageDone = 0;
        task.stageTotal = newCards.size();
        Map<String, UnderstandingCard> rowByNo = new LinkedHashMap<>();
        for (UnderstandingCard r : cardRows) rowByNo.put(r.getNo(), r);
        StringBuilder sb = new StringBuilder("# 喵已有的书架\n\n");
        for (Map<String, Object> b : (List<Map<String, Object>>) skeleton.get("books")) {
            sb.append("- 《").append(b.get("title")).append("》 file=").append(b.get("file")).append("\n");
            for (Map<String, Object> ch : (List<Map<String, Object>>) b.get("chapters")) {
                sb.append("  - 章：").append(ch.get("title")).append("（节：");
                List<String> secNames = new ArrayList<>();
                for (Map<String, Object> s : (List<Map<String, Object>>) ch.get("sections")) secNames.add(String.valueOf(s.get("name")));
                sb.append(String.join("、", secNames)).append("）\n");
            }
        }
        sb.append("\n# 新产出的知识点卡片\n\n");
        for (Map<String, Object> c : newCards) {
            sb.append("- ").append(c.get("no")).append("「").append(c.get("topic")).append("」").append(c.get("point")).append("\n");
        }
        HarnessRunContext ctx = newCtx(cat.getId(), "INSERT",
                "把新知识点归位进已有书架。出参标准：JSON 数组，每张卡一条 {no, bookFile, chapter, section}；"
                        + "确实不适配任何书时 bookFile 用 NEW 并给 title/description。",
                sb.toString(), budget, task, live);
        List<Map<String, Object>> placements = PipelineRunner.runStage(insertSpec(), ctx,
                x -> {
                    try {
                        return parsePlacements(agent.chat(INSERT_PROMPT, x.buildPrompt("给每张新卡输出归位（只输出 JSON 数组）")));
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                },
                x -> List.of());

        List<Map<String, Object>> books = (List<Map<String, Object>>) skeleton.get("books");
        List<UnderstandingCard> toSave = new ArrayList<>();
        Map<String, Map<String, Object>> byFile = new LinkedHashMap<>();
        for (Map<String, Object> b : books) byFile.put(String.valueOf(b.get("file")), b);

        /* ===== 归位判定（laya 主导）=====
           骨架已存在，候选就是书架上全部的节；这里让 laya 逐卡判一次，
           过门控的以 laya 为准，没过门控的沿用上面 LLM 的判定兜底（不再多烧一次 LLM）。 */
        List<LayaCardRouter.Section> sections = skeletonSections(books);
        List<LayaCardRouter.Card> cardList = toRouterCards(newCards);
        LayaCardRouter.Ruling ruling = routeCards(sections, cardList);
        Map<String, Map<String, Object>> llmByNo = new LinkedHashMap<>();
        for (Map<String, Object> pl : placements) llmByNo.putIfAbsent(String.valueOf(pl.get("no")), pl);
        List<Map<String, Object>> finalPlacements = new ArrayList<>();
        for (LayaCardRouter.Card c : cardList) {
            LayaCardRouter.Section hit = ruling.routed().get(c.no());
            if (hit != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("no", c.no());
                p.put("bookFile", hit.key());
                p.put("chapter", hit.chapter());
                p.put("section", hit.name());
                finalPlacements.add(p);
            } else if (llmByNo.containsKey(c.no())) {
                finalPlacements.add(llmByNo.get(c.no()));
            }
        }
        if (ruling.judged() > 0) {
            log.info("[喵的整理] {}", ruling.note());
            trace.event(cat.getId(), "🧭", ruling.note());
        }

        for (Map<String, Object> pl : finalPlacements) {
            String no = String.valueOf(pl.get("no"));
            UnderstandingCard row = rowByNo.get(no);
            if (row == null) continue;
            String bf = String.valueOf(pl.getOrDefault("bookFile", ""));
            String chapter = clip(String.valueOf(pl.getOrDefault("chapter", "")).strip(), 98);
            String section = clip(String.valueOf(pl.getOrDefault("section", "")).strip(), 98);
            if (chapter.isBlank() || section.isBlank()) continue;
            Map<String, Object> book;
            if ("NEW".equalsIgnoreCase(bf) || !byFile.containsKey(bf)) {
                String title = clip(String.valueOf(pl.getOrDefault("title", "新书")).strip(), 60);
                String fname = nextBookFileName(books);
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("title", chapter);
                ch.put("sections", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("title", section, "point", "")))));
                book = new LinkedHashMap<>();
                book.put("file", fname);
                book.put("title", title);
                book.put("description", String.valueOf(pl.getOrDefault("description", "")).strip());
                book.put("chapters", new ArrayList<>(List.of(ch)));
                books.add(book);
                byFile.put(fname, book);
                bf = fname;
            } else {
                book = byFile.get(bf);
                List<Map<String, Object>> chapters = (List<Map<String, Object>>) book.get("chapters");
                Map<String, Object> ch = chapters.stream()
                        .filter(c -> String.valueOf(c.get("title")).equals(chapter)).findFirst().orElse(null);
                if (ch == null) {
                    ch = new LinkedHashMap<>();
                    ch.put("title", chapter);
                    ch.put("sections", new ArrayList<>());
                    chapters.add(ch);
                }
                List<Map<String, Object>> secs = (List<Map<String, Object>>) ch.get("sections");
                boolean has = secs.stream().anyMatch(s -> String.valueOf(s.get("name")).equals(section));
                if (!has) {
                    Map<String, Object> ns = new LinkedHashMap<>();
                    ns.put("name", section);
                    ns.put("point", "");
                    ns.put("cards", new ArrayList<>());
                    secs.add(ns);
                }
            }
            row.setBookFile(bf);
            row.setBookTitle(clip(String.valueOf(book.get("title")), 300));
            row.setChapter(chapter);
            row.setSection(section);
            toSave.add(row);
        }
        if (!toSave.isEmpty()) understandingRepo.saveAll(toSave);
        try {
            renderAllBooks(cat, skeleton);
        } catch (Exception e) {
            log.warn("[喵的整理] 归位后重渲染书 md 失败: {}", e.getMessage());
        }
    }

    // ================= 成书归位（P0-3：laya 主导、LLM 兜底） =================

    /**
     * 骨架 → 候选节列表（增量用）：key = 书文件名，判定结果直接就是卡表的 bookFile。
     */
    private List<LayaCardRouter.Section> skeletonSections(List<Map<String, Object>> books) {
        List<LayaCardRouter.Section> out = new ArrayList<>();
        if (books == null) return out;
        for (Map<String, Object> b : books) {
            String file = String.valueOf(b.get("file"));
            String title = String.valueOf(b.get("title"));
            Object chsObj = b.get("chapters");
            if (!(chsObj instanceof List<?> chs)) continue;
            for (Object chObj : chs) {
                Map<String, Object> ch = (Map<String, Object>) chObj;
                String chTitle = String.valueOf(ch.get("title"));
                Object secsObj = ch.get("sections");
                if (!(secsObj instanceof List<?> secs)) continue;
                for (Object sObj : secs) {
                    Map<String, Object> s = (Map<String, Object>) sObj;
                    out.add(new LayaCardRouter.Section(file, title, chTitle, String.valueOf(s.get("name"))));
                }
            }
        }
        return out;
    }

    /** 卡片 → 判定入参（只取 no/topic/point 三个字段）。 */
    private List<LayaCardRouter.Card> toRouterCards(List<Map<String, Object>> cards) {
        List<LayaCardRouter.Card> out = new ArrayList<>();
        for (Map<String, Object> c : cards) {
            out.add(new LayaCardRouter.Card(String.valueOf(c.get("no")),
                    str(c.get("topic")), str(c.get("point"))));
        }
        return out;
    }

    /**
     * 跑一轮归位判定。任何异常都吞掉并返回「全部交 LLM」——
     * 归位失败不该让整轮整理失败，与接入前的行为保持一致。
     */
    private LayaCardRouter.Ruling routeCards(List<LayaCardRouter.Section> sections,
                                             List<LayaCardRouter.Card> cards) {
        try {
            return cardRouter.route(sections, cards);
        } catch (Exception e) {
            log.warn("[喵的整理] 成书归位判定异常，全部沿用 LLM 判定: {}", e.toString());
            List<String> all = new ArrayList<>();
            for (LayaCardRouter.Card c : cards) all.add(c.no());
            return new LayaCardRouter.Ruling(Map.of(), all, 0, cards.size(), "判定异常，全部交 LLM");
        }
    }

    /**
     * 全量成书的卡片重排：结构（书/章/节）保持 LLM 生成的结论，**卡片归属以 laya 判定为准**。
     *
     * <p>为什么不能只靠 laya：laya 是 choice，只能从给定候选里挑，无法发明书名/节名 ——
     * 结构生成必须由 LLM 完成。这里 LLM 先出结构（顺带给出它自己的归属，作为兜底基线），
     * 再让 laya 逐卡重判细分归属：过门控的覆盖 LLM，没过的、以及主动选「都不贴切」的沿用 LLM。
     *
     * @return 就地重排后的 outline（各节 cards 已按最终归属重建）
     */
    private List<Map<String, Object>> rebalanceOutline(Cat cat, List<Map<String, Object>> outline,
                                                       List<Map<String, Object>> cards) {
        if (outline == null || outline.isEmpty() || cards == null || cards.isEmpty()) return outline;

        /* 1. 扁平化候选节：key = 书序/章序/节序；同时记下每张卡 LLM 给的原始归属 */
        List<LayaCardRouter.Section> sections = new ArrayList<>();
        Map<String, Map<String, Object>> sectionByKey = new LinkedHashMap<>();
        Map<String, String> llmKey = new LinkedHashMap<>();
        for (int bi = 0; bi < outline.size(); bi++) {
            Map<String, Object> b = outline.get(bi);
            Object chsObj = b.get("chapters");
            if (!(chsObj instanceof List<?> chs)) continue;
            for (int ci = 0; ci < chs.size(); ci++) {
                Map<String, Object> ch = (Map<String, Object>) chs.get(ci);
                Object secsObj = ch.get("sections");
                if (!(secsObj instanceof List<?> secs)) continue;
                for (int si = 0; si < secs.size(); si++) {
                    Map<String, Object> s = (Map<String, Object>) secs.get(si);
                    String key = bi + "/" + ci + "/" + si;
                    sections.add(new LayaCardRouter.Section(key, str(b.get("title")),
                            str(ch.get("title")), str(s.get("name"))));
                    sectionByKey.put(key, s);
                    Object nosObj = s.get("cards");
                    if (nosObj instanceof List<?> nos) {
                        for (Object n : nos) llmKey.putIfAbsent(String.valueOf(n), key);
                    }
                }
            }
        }
        if (sections.isEmpty()) return outline;

        /* 2. laya 逐卡重判 */
        List<LayaCardRouter.Card> cardList = toRouterCards(cards);
        LayaCardRouter.Ruling ruling = routeCards(sections, cardList);
        if (ruling.judged() == 0) return outline;   /* 侧车不可用 / 已禁用：保持 LLM 原样 */

        /* 3. 最终归属：laya 过门控的为准，其余沿用 LLM；两者都没有的兜到第一节，保证不丢卡 */
        Map<String, String> finalKey = new LinkedHashMap<>();
        for (LayaCardRouter.Card c : cardList) {
            LayaCardRouter.Section hit = ruling.routed().get(c.no());
            String key = hit != null ? hit.key() : llmKey.get(c.no());
            finalKey.put(c.no(), key != null ? key : sections.get(0).key());
        }

        /* 4. 清空各节 cards 后按卡序放回（顺序稳定 = 阅读顺序可预期） */
        for (Map<String, Object> s : sectionByKey.values()) s.put("cards", new ArrayList<String>());
        for (LayaCardRouter.Card c : cardList) {
            Map<String, Object> s = sectionByKey.get(finalKey.get(c.no()));
            if (s == null) continue;
            ((List<String>) s.get("cards")).add(c.no());
        }

        log.info("[喵的整理] {}", ruling.note());
        trace.event(cat.getId(), "🧭", ruling.note());
        return outline;
    }

    /** 取字符串字段，null/"null" 归一为空串。 */
    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return "null".equals(s) ? "" : s;
    }

    /** 书架下一个文件名：%02d-新书.md */
    private String nextBookFileName(List<Map<String, Object>> books) {
        int max = 0;
        for (Map<String, Object> b : books) {
            try {
                max = Math.max(max, Integer.parseInt(String.valueOf(b.get("file")).substring(0, 2)));
            } catch (Exception ignore) { }
        }
        return String.format("%02d-%s.md", max + 1, "新书");
    }

    /** 读书骨架（reading/_outline.json）；不存在返回 null */
    private Map<String, Object> readSkeleton(Cat cat) {
        Path f = readingDir(cat.getWorkspacePath()).resolve("_outline.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            return objectMapper.readValue(f.toFile(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("[喵的整理] 读取书骨架失败（可在阅读页点「全量重归纳」重建）: {}", e.getMessage());
            return null;
        }
    }

    /** 从模型回复解析归位列表：no/bookFile/chapter/section（NEW 时附 title/description） */
    private List<Map<String, Object>> parsePlacements(String reply) throws Exception {
        if (reply == null || reply.isBlank()) return List.of();
        int l = reply.indexOf('[');
        int r = reply.lastIndexOf(']');
        if (l < 0 || r <= l) return List.of();
        JsonNode arr = objectMapper.readTree(reply.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String no = n.path("no").asText("").strip();
            if (no.isBlank()) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("no", no);
            p.put("bookFile", n.path("bookFile").asText("").strip());
            p.put("chapter", n.path("chapter").asText("").strip());
            p.put("section", n.path("section").asText("").strip());
            p.put("title", n.path("title").asText("").strip());
            p.put("description", n.path("description").asText("").strip());
            out.add(p);
        }
        return out;
    }

    /** 全量流水线：读厚（按书聚合出知识点图谱）→ 读薄（章节化归纳成书）→ 读厚渐进（ENRICH 补全）→ 学透（出学习卡）。每阶段走 HarnessSpec 契约门禁 */
    private void runFull(ReadingTask task, Cat cat, List<ContentItem> items, List<Map<String, Object>> turns) {
        try {
            MiaoMiaoAgent agent = miaomiao.agent(cat.getId());
            boolean live = miaomiao.liveMode(cat.getId());
            AtomicInteger budget = new AtomicInteger(PipelineRunner.DEFAULT_TASK_BUDGET);
            /* detail 跨任务缓存：重跑时同书同名的重点/难点直接复用旧补全，不重复烧钱 */
            Map<String, String> detailCache = loadDetailCache(cat.getId());
            understandingRepo.deleteByCatId(cat.getId());
            sourceRepo.deleteByCatId(cat.getId());
            learnRepo.deleteByCatId(cat.getId());

            /* ===== 阶段一 READ_DEEP：按书聚合精读——读一本，把整本书的关键/重点/难点/知识点一次读透（每本书一个契约门禁单元） ===== */
            List<Map<String, Object>> cards = new ArrayList<>();
            int[] counter = {1};
            int total = 0;
            int done = 0;
            List<BookGroup> fullGroups = groupBooks(cat);
            task.stage = "read";
            task.stageDone = 0;
            task.stageTotal = fullGroups.size();
            for (BookGroup g : fullGroups) {
                total++;
                Map<String, Long> srcMap = new LinkedHashMap<>();
                String body = bookBody(g.items(), srcMap);
                if (body.isBlank()) {
                    log.warn("[喵的整理] 书《{}》没有任何可读正文，跳过", g.book().getTitle());
                    continue;
                }
                if (done >= MAX_ARTICLES) break;
                task.stageDone = done;
                task.current = "正在精读《" + clip(plain(g.book().getTitle()), 20) + "》第 " + (done + 1) + "/" + fullGroups.size() + " 本…";
                try {
                    HarnessRunContext ctx = newCtx(cat.getId(), "READ_DEEP",
                            "按书聚合精读：读完整本书，产出知识点图谱。出参标准：JSON 数组 3~12 个知识点，"
                                    + "每点含 no/name/summary/usage/kind/links/src，kind 用 KEY/FOCUS/DIFFICULT/FACT 枚举，"
                                    + "links 指向同书其他点，src 标注出处文章编号（对应正文里的 [A1] 标记）。",
                            buildBookPrompt(g, body), budget, task, live);
                    List<Map<String, Object>> piece = PipelineRunner.runStage(bookSpec(), ctx,
                            x -> chatPoints(agent, BOOK_PROMPT, x.buildPrompt("读懂这本书，输出知识点图谱（只输出 JSON 数组）")),
                            x -> List.of());
                    for (Map<String, Object> p : piece) {
                        String globalNo = String.format("P%04d", counter[0]++);
                        /* 书内编号 → 全局编号映射：模型给的是 P1/P2…，落库统一为全局唯一编号 */
                        p.put("localNo", p.get("no"));
                        p.put("no", globalNo);
                        p.put("bookId", g.book().getId());
                        p.put("bookTitle", clip(g.book().getTitle(), 300));
                        p.put("src", "《" + g.book().getTitle() + "》");
                        /* 模型标注的出处文章（A1/A2…）→ 真实文章 id + 标题：落关联表，md 与前端展示 */
                        List<Long> contentIds = new ArrayList<>();
                        List<String> srcTitles = new ArrayList<>();
                        if (p.get("srcs") instanceof List<?> keys) {
                            for (Object k : keys) {
                                Long cid = srcMap.get(String.valueOf(k).strip().toUpperCase());
                                if (cid != null && !contentIds.contains(cid)) {
                                    contentIds.add(cid);
                                    g.items().stream().filter(it -> it.getId().equals(cid)).findFirst()
                                            .ifPresent(it -> srcTitles.add(clip(it.getTitle(), 80)));
                                }
                            }
                        }
                        p.put("contentIds", contentIds);
                        p.put("srcTitles", srcTitles);
                        p.put("contentId", contentIds.isEmpty() ? null : contentIds.get(0));
                        cards.add(p);
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 书《{}》精读失败，跳过: {}", g.book().getTitle(), e.getMessage());
                }
                done++;
            }
            /* ---- 会话也过一遍阅读：主人与小喵的对话同样值得沉淀（虚拟书《与主人的会话》） ---- */
            if (!turns.isEmpty()) {
                task.current = "正在回味与主人的 " + turns.size() + " 轮对话…";
                try {
                    HarnessRunContext ctx = newCtx(cat.getId(), "READ_DEEP",
                            "回味与主人的对话，把值得沉淀的知识做成知识点。出参标准：JSON 数组 1~6 个。",
                            buildChatPrompt(turns), budget, task, live);
                    List<Map<String, Object>> piece = PipelineRunner.runStage(chatSpec(), ctx,
                            x -> chatPoints(agent, READ_PROMPT, x.buildPrompt("读懂这段对话，输出知识点（只输出 JSON 数组）")),
                            x -> List.of());
                    for (Map<String, Object> p : piece) {
                        p.put("no", String.format("P%04d", counter[0]++));
                        p.put("kind", UnderstandingCard.KIND_FACT);
                        p.put("bookId", null);
                        p.put("bookTitle", "与主人的会话");
                        p.put("src", "与主人的会话");
                        cards.add(p);
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 会话卡片化失败，跳过: {}", e.getMessage());
                }
                /* 全量也更新会话指纹：否则全量后下轮增量会重复回味同样的对话 */
                librarySync.writeArtifact(cat.getWorkspacePath(), READING_DIR + "/_chat.fp", chatFingerprint(turns));
            }
            /* links 里的书内编号（P1/P2…）→ 全局编号（P0001…）统一映射 */
            Map<String, String> noMap = new LinkedHashMap<>();
            for (Map<String, Object> c : cards) {
                Object local = c.get("localNo");
                if (local != null) noMap.put(String.valueOf(local), String.valueOf(c.get("no")));
            }
            for (Map<String, Object> c : cards) {
                Object links = c.get("links");
                if (links instanceof List<?> l) {
                    List<String> mapped = new ArrayList<>();
                    for (Object n : l) {
                        String g = noMap.get(String.valueOf(n));
                        if (g != null && !g.equals(c.get("no")) && !mapped.contains(g)) mapped.add(g);
                    }
                    c.put("links", mapped);
                }
            }
            if (cards.isEmpty()) throw new IllegalStateException("精读完成但没有产出任何知识点");
            task.cardCount = cards.size();

            /* ---- 清旧 points/ md，落库知识点并逐点写 md 卡片（skill 同款：名称+简介+使用场景+关联） ---- */
            clearPoints(cat.getWorkspacePath());
            List<UnderstandingCard> cardRows = saveCards(cat, batchId(task.id), cards);
            writePointMds(cat.getWorkspacePath(), cards, cardRows);

            /* ===== 阶段二 READ_THIN：知识点归纳成书（门禁：结构 + 全点收编 + 主题不重复） ===== */
            task.stage = "thin";
            task.stageDone = 0;
            task.stageTotal = 0;
            task.current = "读厚完成，共 " + cards.size() + " 个知识点，正在分类分层归纳成书…";
            HarnessRunContext thinCtx = newCtx(cat.getId(), "READ_THIN",
                    "把全部知识点分类、分层，归纳成一本本二级主题书。出参标准：JSON 数组 1~6 本，每书 1~8 个二级主题，每主题用 cards 编号收编知识点，书间主题不重复。",
                    buildOutlinePrompt(cat, cards), budget, task, live);
            List<Map<String, Object>> outline = PipelineRunner.runStage(thinSpec(cards), thinCtx,
                    x -> {
                        try {
                            return parseOutline(agent.chat(SYSTEM_PROMPT, x.buildPrompt("输出二级主题书大纲（只输出 JSON 数组）")));
                        } catch (Exception e) {
                            throw new RuntimeException(e.getMessage(), e);
                        }
                    },
                    x -> deterministicOutline(cards));

            task.current = "正在把卡片归进各书的二级主题（laya 逐卡判定）…";
            /* 结构由 LLM 定，卡片归属由 laya 重判（更细一层的分类）；未过门控的沿用 LLM 判定 */
            outline = rebalanceOutline(cat, outline, cards);

            task.current = "正在把 " + outline.size() + " 本书落盘到工作区…";
            List<Map<String, Object>> saved = writeReadingMd(cat, agent, outline, cards, total, done, turns.size(), cardRows);
            librarySync.commit("喵的整理: " + cat.getName() + " 的整理 · " + saved.size() + " 本书 · " + cards.size() + " 个知识点");
            task.books = saved;
            task.bookCount = saved.size();

            /* ===== 阶段三 ENRICH：标准档——只对重点/难点逐点读厚（detail 缓存复用，防重复烧钱） ===== */
            int enriched = enrichPhase(task, cat, agent, live, cards, cardRows, detailCache, items, budget);
            task.enrichCount = enriched;
            if (enriched > 0) {
                librarySync.commit("喵的整理: " + cat.getName() + " 的知识点读厚 · " + enriched + " 点补全");
            }

            /* ===== 阶段四 LEARN：每本书出学习卡片（≤5 张，落库 + md 学习卡片段） ===== */
            task.stage = "learn";
            task.current = "正在为 " + saved.size() + " 本书提炼学习卡片（学透）…";
            List<LearnCard> learned = learnPhase(task, cat, agent, live, saved, cards, budget);
            librarySync.commit("喵的整理: " + cat.getName() + " 的学习卡片 · " + learned.size() + " 张");
            refreshSearchIndex(cat.getId());
            /* 书架自动整理：归纳后体检并自动执行合并/分裂，不再请主人确认 */
            List<String> tidy = autoTidy(cat.getId());
            task.learnCount = learned.size();
            task.status = "done";
            task.current = "阅读完成：精读 " + done + " 本 · " + cards.size() + " 个知识点（读厚 " + enriched + "）· "
                    + saved.size() + " 本书 · " + learned.size() + " 张学习卡片"
                    + (tidy.isEmpty() ? "" : " · 🧹 书架已自动整理：" + String.join("、", tidy));
            log.info("[喵的整理] 任务 {} 完成：{} 本 → {} 知识点 → {} 本书 → 读厚 {} → {} 学习卡 · 自动整理 {} 项",
                    task.id, done, cards.size(), saved.size(), enriched, learned.size(), tidy.size());
        } catch (Exception e) {
            log.error("[喵的整理] 任务 {} 失败: {}", task.id, e.getMessage(), e);
            task.status = "error";
            task.error = e.getMessage();
        }
    }

    // ================= 阶段契约（HarnessSpec 四件套） =================

    /** 按书聚合精读的门禁：3~12 点；kind 四类枚举；no 唯一；name/summary 非空；usage≥1 条；每点有关联（links 非空或被他人引用） */
    private HarnessSpec bookSpec() {
        QualityGate gate = (out, ctx) -> {
            List<?> pts = (List<?>) out;
            if (pts.isEmpty()) return GateResult.fail("没有产出任何知识点（输出应为 JSON 数组）");
            if (pts.size() > 12) return GateResult.fail("一本书最多 12 个知识点，当前 " + pts.size() + " 个，请合并");
            java.util.Set<String> nos = new java.util.HashSet<>();
            java.util.Set<String> linked = new java.util.HashSet<>();
            for (Object o : pts) {
                Map<?, ?> p = (Map<?, ?>) o;
                String no = String.valueOf(p.get("no"));
                if (no.isBlank() || "null".equals(no)) return GateResult.fail("存在 no 为空的知识点");
                if (!nos.add(no)) return GateResult.fail("知识点编号重复：" + no);
                String kind = String.valueOf(p.get("kind"));
                if (!kind.equals(UnderstandingCard.KIND_KEY) && !kind.equals(UnderstandingCard.KIND_FOCUS)
                        && !kind.equals(UnderstandingCard.KIND_DIFFICULT) && !kind.equals(UnderstandingCard.KIND_FACT)) {
                    return GateResult.fail("知识点 " + no + " 的 kind 必须是 KEY/FOCUS/DIFFICULT/FACT，当前 " + kind);
                }
                if (String.valueOf(p.get("topic")).isBlank()) return GateResult.fail("知识点 " + no + " 的 name 为空");
                if (String.valueOf(p.get("point")).isBlank()) return GateResult.fail("知识点 " + no + " 的 summary 为空");
                Object usage = p.get("usage");
                if (!(usage instanceof List<?> ul) || ul.isEmpty()) {
                    return GateResult.fail("知识点 " + no + " 缺少 usage（什么时候使用，至少 1 条场景）");
                }
                Object links = p.get("links");
                if (links instanceof List<?> ll) {
                    for (Object n : ll) {
                        String t = String.valueOf(n);
                        if (t.equals(no)) return GateResult.fail("知识点 " + no + " 关联了自己");
                        linked.add(t);
                    }
                }
            }
            for (Object o : pts) {
                Map<?, ?> p = (Map<?, ?>) o;
                String no = String.valueOf(p.get("no"));
                Object links = p.get("links");
                boolean selfLinked = links instanceof List<?> l && !l.isEmpty();
                if (!selfLinked && !linked.contains(no)) {
                    return GateResult.fail("知识点 " + no + " 是孤点（既不关联别人，也没被关联）——每个知识点都必须与同书其他点相连");
                }
            }
            return GateResult.ok();
        };
        return new HarnessSpec("按书聚合精读产出知识点图谱", "JSON 数组 3~12 点，每点 no/name/summary/usage/kind/links",
                List.of("书的正文必须非空"), gate);
    }

    /** 逐点读厚（ENRICH）的门禁：正文 80~800 字；不是把原文原样搬来 */
    private HarnessSpec enrichSpec() {
        QualityGate gate = (out, ctx) -> {
            String detail = String.valueOf(out);
            if (detail.isBlank() || "null".equals(detail)) return GateResult.fail("补充展开为空");
            String d = detail.strip();
            if (d.length() < 80) return GateResult.fail("补充展开太短（" + d.length() + " 字），至少 80 字才算读厚");
            if (d.length() > MAX_DETAIL_LEN) return GateResult.fail("补充展开超过 " + MAX_DETAIL_LEN + " 字，请精炼");
            /* 防照抄：与原文开头高度重合（前 60 字相同）视为搬运 */
            String origin = String.valueOf(ctx.inputs);
            String head = origin.strip();
            if (head.length() > 60) head = head.substring(0, 60);
            if (!head.isBlank() && d.startsWith(head)) return GateResult.fail("补充展开疑似照抄原文开头，请用自己的话重写");
            return GateResult.ok();
        };
        return new HarnessSpec("逐点读厚：为知识点补充展开", "一段 80~800 字的连贯正文", List.of("知识点与出处必须非空"), gate);
    }

    /** 会话卡片的门禁：卡数∈[1,6] */
    private HarnessSpec chatSpec() {
        QualityGate gate = (out, ctx) -> {
            List<?> cards = (List<?>) out;
            if (cards.isEmpty()) return GateResult.fail("没有产出任何卡片（输出应为 JSON 数组）");
            if (cards.size() > 6) return GateResult.fail("一轮对话最多 6 张卡，当前 " + cards.size() + " 张");
            return GateResult.ok();
        };
        return new HarnessSpec("把会话沉淀成理解卡片", "JSON 数组，1~6 张卡", List.of("会话轮次必须非空"), gate);
    }

    /** 成书门禁：书/章/节数在界内；每张输入卡都被某书收编（无卡流失）；节名书内不重复 */
    private HarnessSpec thinSpec(List<Map<String, Object>> cards) {
        QualityGate gate = (out, ctx) -> {
            List<Map<String, Object>> outline = (List<Map<String, Object>>) out;
            if (outline.isEmpty()) return GateResult.fail("没有产出任何书");
            if (outline.size() > 6) return GateResult.fail("书数超过 6 本（当前 " + outline.size() + "），请合并");
            java.util.Set<String> covered = new java.util.HashSet<>();
            for (Map<String, Object> b : outline) {
                List<Map<String, Object>> chapters = (List<Map<String, Object>>) b.get("chapters");
                if (chapters == null || chapters.isEmpty()) return GateResult.fail("书「" + b.get("title") + "」没有一级主题（章）");
                if (chapters.size() > 6) return GateResult.fail("书「" + b.get("title") + "」章数超过 6，请拆书或归并");
                java.util.Set<String> secNames = new java.util.HashSet<>();
                for (Map<String, Object> ch : chapters) {
                    List<Map<String, Object>> secs = (List<Map<String, Object>>) ch.get("sections");
                    if (secs == null || secs.isEmpty()) return GateResult.fail("书「" + b.get("title") + "」章「" + ch.get("title") + "」没有二级主题");
                    for (Map<String, Object> s : secs) {
                        String name = String.valueOf(s.get("name")).strip();
                        if (name.isBlank() || "null".equalsIgnoreCase(name)) return GateResult.fail("书「" + b.get("title") + "」章「" + ch.get("title") + "」存在缺名的二级主题，请补全节名");
                        if (!secNames.add(name.toLowerCase())) return GateResult.fail("书「" + b.get("title") + "」内二级主题「" + name + "」重复，请归并");
                        Object nos = s.get("cards");
                        if (nos instanceof List<?> l) for (Object n : l) covered.add(String.valueOf(n));
                    }
                }
            }
            List<String> lost = new ArrayList<>();
            for (Map<String, Object> c : cards) {
                if (!covered.contains(String.valueOf(c.get("no")))) lost.add(String.valueOf(c.get("no")));
            }
            if (!lost.isEmpty()) return GateResult.fail("有 " + lost.size() + " 张卡片未被任何书收编（" + String.join(",", lost.subList(0, Math.min(5, lost.size()))) + "…），每张卡都必须归进某个二级主题");
            return GateResult.ok();
        };
        return new HarnessSpec("卡片分类分层成书", "JSON 数组 1~6 本，每书 1~6 章，每章 1~8 节，收编全部卡片", List.of("卡片数必须≥1"), gate);
    }

    /** 学习卡门禁：每书 1~5 张；front/back 非空且不雷同 */
    private HarnessSpec learnSpec() {
        QualityGate gate = (out, ctx) -> {
            List<?> cards = (List<?>) out;
            if (cards.isEmpty()) return GateResult.fail("没有产出任何学习卡");
            if (cards.size() > 5) return GateResult.fail("每本书最多 5 张学习卡，当前 " + cards.size() + " 张");
            for (Object o : cards) {
                Map<?, ?> c = (Map<?, ?>) o;
                String front = String.valueOf(c.get("front"));
                String back = String.valueOf(c.get("back"));
                if (front.isBlank() || back.isBlank()) return GateResult.fail("存在 front/back 为空的卡片");
                if (front.equalsIgnoreCase(back)) return GateResult.fail("存在正面与背面雷同的卡片");
            }
            return GateResult.ok();
        };
        return new HarnessSpec("从书出学习卡（recall/feynman）", "JSON 数组 1~5 张，每张 kind+front+back", List.of("书的结构必须完整"), gate);
    }

    // ================= 五区上下文与执行 =================

    /** 五区装配：目标区（goal+出参标准）+ 输入区 + 规则区（Skill 装配，按猫按阶段）；budget 为任务级共享熔断预算 */
    private HarnessRunContext newCtx(Long catId, String stage, String goal, String inputs,
                                     AtomicInteger budget, ReadingTask task, boolean live) {
        return new HarnessRunContext(catId, stage, goal, inputs,
                live ? skills.assemble(catId, stage) : "",
                (action, detail) -> log.info("[喵的整理] 偏好捕获 {} @ {}: {}", action, stage, detail),
                (msg, attempt) -> task.current = msg,
                budget);
    }

    /** chat 一次性调用 → 知识点列表（执行即销毁：每次调用独立，无跨书/跨任务会话状态） */
    private List<Map<String, Object>> chatPoints(MiaoMiaoAgent agent, String systemPrompt, String prompt) {
        try {
            return parsePoints(agent.chat(systemPrompt, prompt));
        } catch (Exception e) {
            throw new RuntimeException("模型调用失败: " + e.getMessage(), e);
        }
    }

    public ReadingTask task(String id) {
        ReadingTask t = tasks.get(id);
        if (t == null) throw new IllegalArgumentException("任务不存在或已过期: " + id);
        return t;
    }

    // ================= 素材收集 =================

    /** 一本书 + 它的全部内容条目（按书聚合精读的输入单元） */
    private record BookGroup(Book book, List<ContentItem> items) {
    }

    /** 该喵可用的分类 id：自有分类 + 全体共享预设分类（预设分类不分喵） */
    private List<Long> catTypeIds(Long catId) {
        List<Long> ids = new ArrayList<>();
        for (BookType type : typeRepo.findByCatIdOrderByIdAsc(catId)) ids.add(type.getId());
        for (BookType type : typeRepo.findByPresetTrueOrderByIdAsc()) {
            if (!ids.contains(type.getId())) ids.add(type.getId());
        }
        return ids;
    }

    /** 该喵可读的书：可用分类（含共享预设分类）下的全部书（去重） */
    private List<Book> readableBooks(Long catId) {
        List<Book> out = new ArrayList<>();
        for (Long tid : catTypeIds(catId)) out.addAll(bookRepo.findByTypeIdOrderByIdAsc(tid));
        return out;
    }

    /** 把该喵书架上的全部内容按书分组（书序 = 分类序 × 书序；每书内容按创建时间倒序）；
     *  预设分类共享后素材口径 = 自有分类的书 + 共享分类的书里「该喵名下」的条目（谁归档算谁的） */
    private List<BookGroup> groupBooks(Cat cat) {
        List<BookGroup> out = new ArrayList<>();
        for (Book book : readableBooks(cat.getId())) {
            List<ContentItem> items = contentRepo.findByBookIdOrderByCreatedAtDesc(book.getId()).stream()
                    .filter(c -> cat.getId().equals(c.getCatId())).toList();
            if (!items.isEmpty()) out.add(new BookGroup(book, items));
        }
        return out;
    }

    /** 按书聚合正文：每篇文章前加 [A1]《标题》 边界标记（模型据此为知识点标注出处文章），
     * A1→contentId 映射写入 srcMap；超长时等比例压缩每篇配额，保证整本书一次喂得下。
     */
    private String bookBody(List<ContentItem> items, Map<String, Long> srcMap) {
        if (items == null || items.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        int total = 0;
        int idx = 0;
        for (ContentItem c : items) {
            String body = articleBody(c);
            if (body.isBlank()) continue;
            idx++;
            srcMap.put("A" + idx, c.getId());
            parts.add("[A" + idx + "]《" + clip(c.getTitle(), 60) + "》\n" + body);
            total += body.length();
        }
        if (total <= MAX_BOOK_BODY) return String.join("\n\n", parts);
        /* 等比例压缩每篇的配额，保证整本书一次喂得下 */
        double ratio = (double) MAX_BOOK_BODY / total;
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            int nl = p.indexOf('\n');
            String head = nl > 0 ? p.substring(0, nl) : p;
            String text = nl > 0 ? p.substring(nl + 1) : "";
            int quota = Math.max(200, (int) (text.length() * ratio));
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(head).append('\n').append(text, 0, Math.min(text.length(), quota));
        }
        return sb.toString();
    }

    /**
     * 单篇正文：像人一样「把文章仔细读，有链接的点开链接读」——
     * rawText（投入时抽取的全文）优先；URL 类型且正文缺失时现场 Jsoup 抓链接；最后 summary 兜底。
     */
    private String articleBody(ContentItem c) {
        if (c.getRawText() != null && !c.getRawText().isBlank()) {
            return clip(c.getRawText(), MAX_BODY_LEN);
        }
        if ("URL".equalsIgnoreCase(c.getContentType()) && c.getSource() != null && !c.getSource().isBlank()) {
            try {
                ExtractService.ExtractResult r = extractService.extractUrl(c.getSource());
                if (r.ok() && r.rawText() != null && !r.rawText().isBlank()) {
                    return clip(r.rawText(), MAX_BODY_LEN);
                }
            } catch (Exception e) {
                log.warn("[喵的整理] 链接抓取失败 {}: {}", c.getSource(), e.getMessage());
            }
        }
        return c.getSummary() == null ? "" : clip(c.getSummary(), 600);
    }

    /** 会话素材：遍历该猫 .context/<b64(workspace/miaomiao)>/<sessionId>/agent_state.json 的对话轮 */
    private List<Map<String, Object>> collectChats(Cat cat) {
        List<Map<String, Object>> turns = new ArrayList<>();
        Path ctxRoot = catWorkspace.contextRoot(cat.getWorkspacePath());
        if (!Files.isDirectory(ctxRoot)) return turns;
        // 与 ConversationMemory.sessionDir 一致：URL-safe Base64、无 padding
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (cat.getWorkspacePath() + "/miaomiao").getBytes(StandardCharsets.UTF_8));
        Path agentDir = ctxRoot.resolve(b64);
        if (!Files.isDirectory(agentDir)) return turns;
        try (Stream<Path> s = Files.list(agentDir)) {
            List<Path> sessions = s.filter(Files::isDirectory).sorted().toList();
            for (Path sd : sessions) {
                Path f = sd.resolve("agent_state.json");
                if (!Files.isRegularFile(f)) continue;
                try {
                    JsonNode st = objectMapper.readTree(f.toFile());
                    String summary = st.path("summary").asText("");
                    JsonNode ctx = st.path("context");
                    String pendingUser = null;
                    for (JsonNode msg : ctx) {
                        String role = msg.path("role").asText("");
                        String text = clip(msgText(msg), 300);
                        if ("USER".equalsIgnoreCase(role)) {
                            pendingUser = text;
                        } else if ("ASSISTANT".equalsIgnoreCase(role)) {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("user", pendingUser == null ? "" : pendingUser);
                            t.put("assistant", text);
                            turns.add(t);
                            pendingUser = null;
                        }
                    }
                    // 该会话被折叠过的早期内容，用摘要补一笔（挂在最后一轮）
                    if (!summary.isBlank() && !turns.isEmpty()) {
                        turns.get(turns.size() - 1).put("prefold", clip(summary, 200));
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 解析会话 {} 失败: {}", sd, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[喵的整理] 遍历会话目录失败 ({}): {}", agentDir, e.getMessage());
        }
        return turns;
    }

    private int sessionCount(Cat cat) {
        Path agentDir = catWorkspace.contextRoot(cat.getWorkspacePath())
                .resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (cat.getWorkspacePath() + "/miaomiao").getBytes(StandardCharsets.UTF_8)));
        if (!Files.isDirectory(agentDir)) return 0;
        try (Stream<Path> s = Files.list(agentDir)) {
            return (int) s.filter(Files::isDirectory).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Msg 的纯文本：content 数组里 type=text 的 text 拼接 */
    private String msgText(JsonNode msg) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : msg.path("content")) {
            if ("text".equals(c.path("type").asText())) {
                sb.append(c.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    // ================= Prompt 与解析 =================

    /** 按书聚合精读输入：书名 + 该书全部内容正文 → 知识点图谱 */
    private String buildBookPrompt(BookGroup g, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 待精读的书：《").append(g.book().getTitle()).append("》\n\n");
        sb.append("- 本书共 ").append(g.items().size()).append(" 篇内容，全部拼在下面\n\n");
        sb.append("全书正文：\n").append(body).append("\n\n");
        sb.append("请读懂这本书，输出知识点图谱（只输出 JSON 数组，no 用 P1、P2…全书内递增）。\n");
        return sb.toString();
    }

    /** 精读会话：最近若干轮对话 → 理解卡片 */
    private String buildChatPrompt(List<Map<String, Object>> turns) {
        List<Map<String, Object>> picked = turns.size() > MAX_CHAT_TURNS
                ? turns.subList(turns.size() - MAX_CHAT_TURNS, turns.size()) : turns;
        StringBuilder sb = new StringBuilder();
        sb.append("# 待精读的对话记录（主人与小喵）\n\n");
        for (Map<String, Object> t : picked) {
            sb.append("- 主人：").append(t.get("user")).append("\n");
            if (t.get("assistant") != null && !String.valueOf(t.get("assistant")).isBlank()) {
                sb.append("  小喵：").append(t.get("assistant")).append("\n");
            }
            if (t.get("prefold") != null) {
                sb.append("  （该会话早期折叠要旨：").append(t.get("prefold")).append("）\n");
            }
        }
        sb.append("\n请把这段对话里值得沉淀的知识读懂，输出理解卡片（只输出 JSON 数组）。\n");
        return sb.toString();
    }

    /** 归纳：全部理解卡片 → 章节化书大纲（章（一级主题）→ 节（二级主题）→ 卡片编号归位） */
    private String buildOutlinePrompt(Cat cat, List<Map<String, Object>> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 这只喵（").append(cat.getName()).append("）精读后的全部理解卡片（共 ").append(cards.size()).append(" 张）\n\n");
        for (Map<String, Object> c : cards) {
            sb.append("- ").append(c.get("no")).append("「").append(c.get("topic")).append("」")
                    .append(c.get("point")).append("（出处：《").append(c.get("src")).append("》）\n");
        }
        sb.append("\n请把这些理解卡片分类、分层，归纳成章节化书大纲：\n")
                .append("- 每本书 = title + description + chapters（1~6 个一级主题，按学习路径排序）\n")
                .append("- 每章 = title + sections（1~8 个二级主题，章内有序）\n")
                .append("- 每节 = name + point + cards（用卡片编号收编，每张卡片都要归进某一节）\n")
                .append("（只输出 JSON 数组）。\n");
        return sb.toString();
    }

    /** 从模型回复中提取知识点数组（容忍 ```json 包裹 / 前后缀文字）；输出键统一为 no/kind/topic/point/usage/links 供下游复用 */
    private List<Map<String, Object>> parsePoints(String reply) throws Exception {
        if (reply == null || reply.isBlank()) return List.of();
        int l = reply.indexOf('[');
        int r = reply.lastIndexOf(']');
        if (l < 0 || r <= l) return List.of();
        JsonNode arr = objectMapper.readTree(reply.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String name = n.path("name").asText("").strip();
            String summary = n.path("summary").asText("").strip();
            if (name.isBlank() && summary.isBlank()) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("no", n.path("no").asText("").strip());
            p.put("kind", normalizeKind(n.path("kind").asText("")));
            p.put("topic", name.isBlank() ? "未命名知识点" : name);
            p.put("point", summary);
            List<String> usage = new ArrayList<>();
            for (JsonNode u : n.path("usage")) {
                String s = u.asText("").strip();
                if (!s.isBlank()) usage.add(s);
            }
            p.put("usage", usage);
            List<String> links = new ArrayList<>();
            for (JsonNode u : n.path("links")) {
                String s = u.asText("").strip();
                if (!s.isBlank()) links.add(s);
            }
            p.put("links", links);
            /* 出处文章编号（["A1","A3"]）：容忍单个字符串；run() 里再映射为真实文章 id */
            List<String> srcs = new ArrayList<>();
            JsonNode sn = n.path("srcs");
            if (sn.isValueNode() && !sn.asText("").isBlank()) srcs.add(sn.asText("").strip());
            for (JsonNode u : sn) {
                String s = u.asText("").strip();
                if (!s.isBlank()) srcs.add(s);
            }
            p.put("srcs", srcs);
            out.add(p);
        }
        return out;
    }

    /** kind 归一化：容忍中文（关键/重点/难点/知识点）与大小写，未知值兜底 FACT */
    private String normalizeKind(String k) {
        String s = k == null ? "" : k.strip().toUpperCase();
        return switch (s) {
            case "KEY", "关键" -> UnderstandingCard.KIND_KEY;
            case "FOCUS", "重点" -> UnderstandingCard.KIND_FOCUS;
            case "DIFFICULT", "难点" -> UnderstandingCard.KIND_DIFFICULT;
            default -> UnderstandingCard.KIND_FACT;
        };
    }

    /** 从模型回复中提取章节化大纲（容忍 ```json 包裹 / 前后缀文字）；书→chapters→sections→cards，兼容旧式无 chapters 的书（节直接挂书下） */
    private List<Map<String, Object>> parseOutline(String reply) throws Exception {
        if (reply == null || reply.isBlank()) throw new IllegalStateException("模型回复为空");
        int l = reply.indexOf('[');
        int r = reply.lastIndexOf(']');
        if (l < 0 || r <= l) throw new IllegalStateException("模型回复里没找到 JSON 数组");
        JsonNode arr = objectMapper.readTree(reply.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = n.path("title").asText("");
            if (title.isBlank()) continue;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("title", title.strip());
            b.put("description", n.path("description").asText("").strip());
            JsonNode chNode = n.path("chapters");
            if (chNode.isArray() && chNode.size() > 0) {
                List<Map<String, Object>> chapters = new ArrayList<>();
                for (JsonNode cn : chNode) {
                    String chTitle = cn.path("title").asText("").strip();
                    List<Map<String, Object>> secs = parseSections(cn.path("sections"));
                    if (chTitle.isBlank() || secs.isEmpty()) continue;
                    Map<String, Object> ch = new LinkedHashMap<>();
                    ch.put("title", chTitle);
                    ch.put("sections", secs);
                    chapters.add(ch);
                }
                b.put("chapters", chapters);
            } else {
                /* 兼容：无 chapters 时，节的标题充当唯一的章 */
                b.put("chapters", List.of(Map.of("title", "正文", "sections", parseSections(n.path("sections")))));
            }
            out.add(b);
        }
        return out;
    }

    /** 解析节的公共片段：name/point/cards */
    private List<Map<String, Object>> parseSections(JsonNode secArr) {
        List<Map<String, Object>> secs = new ArrayList<>();
        for (JsonNode sn : secArr) {
            String name = sn.path("name").asText("").strip();
            if (name.isBlank()) continue;
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("name", name);
            sec.put("point", sn.path("point").asText("").strip());
            List<String> cardNos = new ArrayList<>();
            for (JsonNode cn : sn.path("cards")) {
                String no = cn.asText("").strip();
                if (!no.isBlank()) cardNos.add(no);
            }
            sec.put("cards", cardNos);
            secs.add(sec);
        }
        return secs;
    }

    // ================= md 落盘 =================

    /** 全量落盘：清 reading/ 旧 md → 从章节化大纲构建书骨架（_outline.json）→ 回填卡表归位 → 渲染全部书 md */
    private List<Map<String, Object>> writeReadingMd(Cat cat, MiaoMiaoAgent agent,
                                                     List<Map<String, Object>> outline,
                                                     List<Map<String, Object>> cards,
                                                     int articleTotal, int articleRead, int chatTurns,
                                                     List<UnderstandingCard> cardRows) throws Exception {
        /* 编号 → 卡片 map / 卡片 row，供归位反查 */
        Map<String, Map<String, Object>> byNo = new LinkedHashMap<>();
        for (Map<String, Object> c : cards) byNo.put(String.valueOf(c.get("no")), c);
        Map<String, UnderstandingCard> rowByNo = new LinkedHashMap<>();
        for (UnderstandingCard r : cardRows) rowByNo.put(r.getNo(), r);

        /* 骨架：书→章→节（真值），卡片归属存在卡表 chapter/section */
        List<Map<String, Object>> books = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> b : outline) {
            String title = String.valueOf(b.get("title"));
            String fname = String.format("%02d-%s.md", i++, sanitize(title));
            Map<String, Object> sk = new LinkedHashMap<>();
            sk.put("file", fname);
            sk.put("title", title);
            sk.put("description", b.get("description"));
            sk.put("chapters", b.get("chapters"));
            books.add(sk);
        }
        Map<String, Object> skeleton = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agentName", agent.name());
        meta.put("articleTotal", articleTotal);
        meta.put("articleRead", articleRead);
        meta.put("chatTurns", chatTurns);
        meta.put("cardCount", cards.size());
        meta.put("stamp", LocalDateTime.now().format(FMT));
        skeleton.put("meta", meta);
        skeleton.put("books", books);

        /* 回填卡表归位（厚薄互链 + 章节定位），随后统一渲染 */
        List<UnderstandingCard> toFill = new ArrayList<>();
        for (Map<String, Object> sk : books) {
            String fname = String.valueOf(sk.get("file"));
            String title = String.valueOf(sk.get("title"));
            for (Map<String, Object> ch : (List<Map<String, Object>>) sk.get("chapters")) {
                for (Map<String, Object> s : (List<Map<String, Object>>) ch.get("sections")) {
                    Object nos = s.get("cards");
                    if (!(nos instanceof List<?> l)) continue;
                    for (Object n : l) {
                        UnderstandingCard row = rowByNo.get(String.valueOf(n));
                        if (row == null) continue;
                        row.setBookTitle(clip(title, 300));
                        row.setBookFile(fname);
                        row.setChapter(clip(String.valueOf(ch.get("title")), 98));
                        row.setSection(clip(String.valueOf(s.get("name")), 98));
                        toFill.add(row);
                    }
                }
            }
        }
        if (!toFill.isEmpty()) understandingRepo.saveAll(toFill);
        renderAllBooks(cat, skeleton);
        /* 全量落盘淘汰了旧书：清理挂在已淘汰书上的孤儿卡（老管线残留，指纹留痕防重读） */
        pruneOrphanCards(cat.getId());
        return books;
    }

    /** 统一渲染：写 _outline.json 骨架 + 依骨架把卡表中的卡片填充渲染成每本书的 md（全量/增量归位/分裂合并共用） */
    private void renderAllBooks(Cat cat, Map<String, Object> skeleton) throws Exception {
        Path dir = readingDir(cat.getWorkspacePath());
        Files.createDirectories(dir);
        /* 重建前清旧 md（_outline.json 保留管理） */
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                Files.deleteIfExists(p);
            }
        }
        librarySync.writeArtifact(cat.getWorkspacePath(), READING_DIR + "/_outline.json",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(skeleton));
        Map<String, Object> meta = (Map<String, Object>) skeleton.get("meta");
        String stamp = String.valueOf(meta.get("stamp"));
        /* 卡表 → bookFile 分组（卡行 id 序即阅读顺序） */
        Map<String, List<UnderstandingCard>> byFile = new LinkedHashMap<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(cat.getId())) {
            if (r.getBookFile() == null) continue;
            byFile.computeIfAbsent(r.getBookFile(), k -> new ArrayList<>()).add(r);
        }
        for (Map<String, Object> sk : (List<Map<String, Object>>) skeleton.get("books")) {
            String fname = String.valueOf(sk.get("file"));
            String md = buildBookMd(sk, byFile.getOrDefault(fname, List.of()), meta, stamp);
            librarySync.writeArtifact(cat.getWorkspacePath(), READING_DIR + "/" + fname, md);
        }
        /* 书落盘后同步重建 points/reading 的 INDEX（卡片归位/书清单都以 DB 卡表为真相） */
        refreshPointsIndexes(cat.getId(), cat.getWorkspacePath());
    }

    /** 书 md 渲染：# 书名 → 元信息 → ## 第N章 → ### N.M 节 → 卡片列表（未归位卡进「待归位」） */
    private String buildBookMd(Map<String, Object> sk, List<UnderstandingCard> rows,
                               Map<String, Object> meta, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 《").append(sk.get("title")).append("》\n\n");
        String description = String.valueOf(sk.get("description"));
        if (description != null && !description.isBlank() && !"null".equals(description)) {
            sb.append("> ").append(description.strip()).append("\n\n");
        }
        sb.append("- 来源：喵的整理 · ").append(meta.get("agentName")).append(" · ").append(stamp).append("\n");
        sb.append("- 精读：").append(meta.get("articleRead")).append("/").append(meta.get("articleTotal"))
                .append(" 篇文章（含链接抓取） · ").append(meta.get("chatTurns")).append(" 轮会话 · 收编 ")
                .append(rows.size()).append(" 张理解卡片\n\n");
        int ci = 1;
        List<UnderstandingCard> orphans = new ArrayList<>(rows);
        for (Map<String, Object> ch : (List<Map<String, Object>>) sk.get("chapters")) {
            String chTitle = String.valueOf(ch.get("title"));
            sb.append("## ").append(ci++).append(". ").append(chTitle).append("\n\n");
            int si = 1;
            for (Map<String, Object> s : (List<Map<String, Object>>) ch.get("sections")) {
                String secName = String.valueOf(s.get("name"));
                sb.append("### ").append(ci - 1).append(".").append(si++).append(" ").append(secName).append("\n\n");
                String point = String.valueOf(s.get("point"));
                if (point != null && !point.isBlank() && !"null".equals(point)) {
                    sb.append(point.strip()).append("\n\n");
                }
                orphans.removeIf(r -> chTitle.equals(r.getChapter()) && secName.equals(r.getSection()));
                appendCards(sb, rows, chTitle, secName);
            }
        }
        if (!orphans.isEmpty()) {
            sb.append("## 待归位\n\n（喵还没给这些卡片找到合适的位置，全量重归纳后会归位）\n\n");
            for (UnderstandingCard r : orphans) {
                sb.append("- 📇 **").append(r.getTopic()).append("**：").append(r.getPoint()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 渲染某章某节下的卡片列表（按编号序） */
    private void appendCards(StringBuilder sb, List<UnderstandingCard> rows, String chapter, String section) {
        boolean any = false;
        for (UnderstandingCard r : rows) {
            if (!chapter.equals(r.getChapter()) || !section.equals(r.getSection())) continue;
            sb.append("- 📇 **").append(r.getTopic()).append("**：").append(r.getPoint())
                    .append("（出处：").append(r.getSrc()).append("）\n");
            any = true;
        }
        if (any) sb.append("\n");
    }

    // ================= 分裂 / 合并：书架结构体检（提示 + 主人确认） =================

    /** 单书卡片数达到该值 → 分裂候选 */
    private static final int SPLIT_CARD_THRESHOLD = 15;

    /** 结构体检系统提示：只发现和建议，不落盘——主人确认后才会执行分裂/合并 */
    private static final String STRUCTURE_PROMPT = """
            你是书架结构编辑。给你一个书架：每本书的书名、简介、章节（章→节）与各书卡片数。
            任务：找出结构问题，只输出 JSON 数组（不要解释、不要 markdown 代码块）：
            [{"type":"split","books":["01-x.md"],"reason":"一句话说清为什么"},\
            {"type":"merge","books":["01-a.md","02-b.md"],"reason":"…"}]
            - split：一本书塞了两个以上不相关主题、或卡片数明显偏多，应拆成两本；books 填那本书的文件名；
            - merge：两本书主题高度重叠（讲的是同一件事），应合并成一本；books 按文件名升序填两本；
            - 没有问题输出 []。最多 3 条建议，按重要程度排序。""";

    private static final String MERGE_NAME_PROMPT = """
            你是图书编辑。两本书主题重叠要合并成一本书。给两本书的书名与简介，\
            给合并后的新书起名并写一句话简介。只输出 JSON（不要解释、不要 markdown 代码块）：
            {"title":"新书名","description":"一句话简介"}
            要求：书名不超过 12 个字，概括两本书共同的主题；简介不超过 40 个字。""";

    private static final String SPLIT_PROMPT = """
            你是图书编辑。一本书主题太杂要分裂成两本更聚焦的书。给你这本书的全部章节（章→节，含各章卡片数）。
            把每章分给两本新书之一，并命名、写一句话简介。只输出 JSON（不要解释、不要 markdown 代码块）：
            {"titleA":"…","descriptionA":"…","chaptersA":["章名",…],\
            "titleB":"…","descriptionB":"…","chaptersB":["章名",…]}
            要求：两本书都要有 2 章以上；章名必须原样照抄不许改写；每章只属于一本书；A 按原文阅读顺序排。""";

    /** 书架结构建议：规则粗筛（书太大→分裂、跨书同名节→合并）+ 模型细判补漏；只提示，主人确认后才执行 */
    public List<Map<String, Object>> suggestions(Long catId) {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        Map<String, Object> skeleton = readSkeleton(cat);
        if (skeleton == null) return List.of();
        List<Map<String, Object>> books = castBooks(skeleton);
        if (books.isEmpty()) return List.of();

        Map<String, Long> cardCount = new LinkedHashMap<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
            if (r.getBookFile() != null) cardCount.merge(r.getBookFile(), 1L, Long::sum);
        }

        /* 规则粗筛 */
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        /* 跨书同名书 → 合并候选（书名一字不差，最强的确定性证据） */
        Map<String, Set<String>> titleOwner = new LinkedHashMap<>();
        for (Map<String, Object> b : books) {
            String t = String.valueOf(b.get("title")).strip();
            if (t.isBlank() || "null".equalsIgnoreCase(t)) continue;
            titleOwner.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(String.valueOf(b.get("file")));
        }
        for (Map.Entry<String, Set<String>> e : titleOwner.entrySet()) {
            if (e.getValue().size() < 2) continue;
            List<String> files = e.getValue().stream().sorted().toList();
            if (seen.add("merge|" + String.join("+", files))) {
                out.add(sug("merge", files, "《" + e.getKey() + "》同名书出现 " + e.getValue().size() + " 本，内容重复，应合并为一本"));
            }
        }
        for (Map<String, Object> b : books) {
            String file = String.valueOf(b.get("file"));
            int chapters = chaptersOf(b).size();
            long n = cardCount.getOrDefault(file, 0L);
            if (n >= SPLIT_CARD_THRESHOLD && chapters >= 4 && seen.add("split|" + file)) {
                out.add(sug("split", List.of(file),
                        "《" + b.get("title") + "》已收编 " + n + " 张卡、分 " + chapters + " 章，体量偏大，可按主题分裂成两本"));
            }
        }
        /* 跨书同名节 → 合并候选（两本书讲到了同一件事）；「null」/空节名是成书残缺，不作为比对依据 */
        Map<String, Set<String>> secOwner = new LinkedHashMap<>();
        for (Map<String, Object> b : books) {
            for (Map<String, Object> ch : chaptersOf(b)) {
                for (Map<String, Object> s : sectionsOf(ch)) {
                    String key = String.valueOf(s.get("name")).strip();
                    if (key.length() < 2 || "null".equalsIgnoreCase(key)) continue;
                    secOwner.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(String.valueOf(b.get("file")));
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : secOwner.entrySet()) {
            if (e.getValue().size() < 2) continue;
            List<String> files = e.getValue().stream().sorted().toList();
            if (seen.add("merge|" + String.join("+", files))) {
                out.add(sug("merge", files,
                        "「" + e.getKey() + "」出现在两本书里，主题重叠，建议合并成一本书"));
            }
        }

        /* 模型细判：整体扫一遍书架补漏（失败静默降级为规则结果） */
        try {
            String reply = miaomiao.agent(catId).chat(STRUCTURE_PROMPT, structurePrompt(books, cardCount));
            for (Map<String, Object> s : parseSuggestions(reply)) {
                if (seen.add(s.get("type") + "|" + String.valueOf(s.get("books")))) out.add(s);
            }
        } catch (Exception e) {
            log.warn("[喵的整理] 结构建议模型细判失败（降级为规则结果）: {}", e.getMessage());
        }
        return out;
    }

    /** 单轮自动整理最多执行的动作数：防一次大拆大建 */
    private static final int AUTO_TIDY_MAX_ACTIONS = 2;

    /**
     * 书架自动整理：循环「体检 → 执行一个动作 → 重新体检」，直到书架健康或达动作上限，不再请主人确认。
     * 每个动作后必须重新体检——合并会重编号书架（文件名变化），旧建议作废；
     * 同名书 N>2 本时由此循环两两串联合并。防线：动作上限、任何一步执行失败立即停止（防连锁错误）；
     * 动作全部走 mergeBooks/splitBook 的既有链路（卡表归属、git 提交、索引刷新由它们兜住）。
     *
     * @return 已执行的动作描述（供任务完成文案展示），空列表 = 书架健康无需整理
     */
    public List<String> autoTidy(Long catId) {
        List<String> actions = new ArrayList<>();
        try {
            while (actions.size() < AUTO_TIDY_MAX_ACTIONS) {
                /* 挑一个可执行的建议：merge 必须恰好两本不同书；split 必须恰好一本书 */
                Map<String, Object> next = null;
                for (Map<String, Object> s : suggestions(catId)) {
                    String type = String.valueOf(s.get("type"));
                    List<String> files = (List<String>) s.get("books");
                    if ("merge".equals(type) && files != null && files.size() == 2
                            && !files.get(0).equals(files.get(1))) { next = s; break; }
                    if ("split".equals(type) && files != null && files.size() == 1) { next = s; break; }
                }
                if (next == null) break; /* 书架健康，无需整理 */
                String type = String.valueOf(next.get("type"));
                List<String> files = (List<String>) next.get("books");
                String reason = String.valueOf(next.get("reason"));
                try {
                    if ("merge".equals(type)) {
                        mergeBooks(catId, files);
                        actions.add("合并《" + files.get(0) + "》+《" + files.get(1) + "》(" + reason + ")");
                    } else {
                        splitBook(catId, files.get(0));
                        actions.add("分裂《" + files.get(0) + "》(" + reason + ")");
                    }
                } catch (Exception e) {
                    log.warn("[喵的整理] 自动整理动作失败，本轮停止: {} - {}", type, e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[喵的整理] 书架自动整理体检失败（跳过）: {}", e.getMessage());
        }
        /* 动作改了书架 md 与卡表归属：显式提交，确保变更进版本库（mergeBooks/splitBook 自身不 commit） */
        if (!actions.isEmpty()) {
            try {
                librarySync.commit("喵的整理: 书架自动整理 · " + String.join("；", actions));
            } catch (Exception e) {
                log.warn("[喵的整理] 自动整理提交 git 失败: {}", e.getMessage());
            }
        }
        return actions;
    }

    private Map<String, Object> sug(String type, List<String> files, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("books", files);
        m.put("reason", reason);
        return m;
    }

    private String structurePrompt(List<Map<String, Object>> books, Map<String, Long> cardCount) {
        StringBuilder sb = new StringBuilder("# 书架现状\n\n");
        for (Map<String, Object> b : books) {
            sb.append("## 《").append(b.get("title")).append("》（").append(b.get("file"))
                    .append("，").append(cardCount.getOrDefault(String.valueOf(b.get("file")), 0L)).append(" 卡）\n");
            String desc = String.valueOf(b.get("description"));
            if (!desc.isBlank() && !"null".equals(desc)) sb.append("简介：").append(desc.strip()).append('\n');
            for (Map<String, Object> ch : chaptersOf(b)) {
                sb.append("- ").append(ch.get("title")).append("：");
                List<String> secs = new ArrayList<>();
                for (Map<String, Object> s : sectionsOf(ch)) secs.add(String.valueOf(s.get("name")));
                sb.append(String.join(" · ", secs)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("请找出结构问题（只输出 JSON 数组，无问题输出 []）。\n");
        return sb.toString();
    }

    private List<Map<String, Object>> parseSuggestions(String reply) throws Exception {
        int l = reply.indexOf('[');
        int r = reply.lastIndexOf(']');
        if (l < 0 || r <= l) return List.of();
        JsonNode arr = objectMapper.readTree(reply.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String type = n.path("type").asText("").strip();
            if (!"split".equals(type) && !"merge".equals(type)) continue;
            List<String> files = new ArrayList<>();
            for (JsonNode x : n.path("books")) {
                String f = x.asText("").strip();
                if (!f.isBlank()) files.add(f);
            }
            if (files.isEmpty()) continue;
            out.add(sug(type, files, n.path("reason").asText("").strip()));
        }
        return out;
    }

    /** 合并两本书：章拼接（同名章并节去重），卡表批量改归属，重编号重渲染。主人已在 UI 确认后才调用 */
    public Map<String, Object> mergeBooks(Long catId, List<String> files) throws Exception {
        if (files == null || files.size() != 2 || files.get(0).equals(files.get(1))) {
            throw new IllegalArgumentException("合并需要两本不同的书");
        }
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        Map<String, Object> skeleton = readSkeleton(cat);
        if (skeleton == null) throw new IllegalArgumentException("书骨架不存在，请先「全量重归纳」");
        List<Map<String, Object>> books = castBooks(skeleton);
        Map<String, Object> a = null;
        Map<String, Object> b = null;
        for (Map<String, Object> bk : books) {
            if (files.get(0).equals(bk.get("file"))) a = bk;
            if (files.get(1).equals(bk.get("file"))) b = bk;
        }
        if (a == null || b == null) throw new IllegalArgumentException("要合并的书不在书架上");

        /* 新书名：模型命名，失败降级「A·B 合编」 */
        String titleA = String.valueOf(a.get("title"));
        String titleB = String.valueOf(b.get("title"));
        String newTitle = null;
        String newDesc = null;
        try {
            JsonNode n = parseJsonObj(miaomiao.agent(catId).chat(MERGE_NAME_PROMPT,
                    "书A：《" + titleA + "》—" + String.valueOf(a.get("description"))
                            + "\n书B：《" + titleB + "》—" + String.valueOf(b.get("description"))));
            String t = n.path("title").asText("").strip();
            if (!t.isBlank()) newTitle = t;
            String d = n.path("description").asText("").strip();
            if (!d.isBlank()) newDesc = d;
        } catch (Exception e) {
            log.warn("[喵的整理] 合并命名模型调用失败（降级拼接命名）: {}", e.getMessage());
        }
        if (newTitle == null) newTitle = titleA + "·" + titleB;
        if (newDesc == null) newDesc = titleA + " 与 " + titleB + " 合编";

        /* 章：A 的章在前，B 的章追加；同名章并节去重 */
        List<Map<String, Object>> aChapters = chaptersOf(a);
        Map<String, Map<String, Object>> aChByTitle = new LinkedHashMap<>();
        for (Map<String, Object> ch : aChapters) aChByTitle.put(String.valueOf(ch.get("title")), ch);
        for (Map<String, Object> ch : chaptersOf(b)) {
            String t = String.valueOf(ch.get("title"));
            Map<String, Object> same = aChByTitle.get(t);
            if (same == null) {
                aChapters.add(ch);
                aChByTitle.put(t, ch);
                continue;
            }
            List<Map<String, Object>> secs = sectionsOf(same);
            Set<String> names = new LinkedHashSet<>();
            for (Map<String, Object> s : secs) names.add(String.valueOf(s.get("name")));
            for (Map<String, Object> s : sectionsOf(ch)) {
                if (names.add(String.valueOf(s.get("name")))) secs.add(s);
            }
        }
        a.put("title", newTitle);
        a.put("description", newDesc);
        books.remove(b);

        /* B 的卡片先归到 A 名下（章/节不变），重编号时统一改书名 */
        String fileA = files.get(0);
        String fileB = files.get(1);
        List<UnderstandingCard> toSave = new ArrayList<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
            if (fileB.equals(r.getBookFile())) {
                r.setBookFile(fileA);
                toSave.add(r);
            }
        }
        if (!toSave.isEmpty()) understandingRepo.saveAll(toSave);
        renumberAndRender(cat, skeleton);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("bookFile", fileA);
        r.put("title", newTitle);
        r.put("message", "已合并《" + titleA + "》+《" + titleB + "》→《" + newTitle + "》");
        return r;
    }

    /** 分裂一本书：模型按章分给两本新书并命名，卡表跟随章节改归属，重编号重渲染 */
    public Map<String, Object> splitBook(Long catId, String file) throws Exception {
        Cat cat = catRepo.findById(catId)
                .orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
        Map<String, Object> skeleton = readSkeleton(cat);
        if (skeleton == null) throw new IllegalArgumentException("书骨架不存在，请先「全量重归纳」");
        List<Map<String, Object>> books = castBooks(skeleton);
        Map<String, Object> sk = null;
        String oldTitle = null;
        for (Map<String, Object> bk : books) {
            if (file != null && file.equals(bk.get("file"))) {
                sk = bk;
                oldTitle = String.valueOf(bk.get("title"));
            }
        }
        if (sk == null) throw new IllegalArgumentException("书不在书架上: " + file);
        List<Map<String, Object>> chapters = chaptersOf(sk);
        if (chapters.size() < 2) throw new IllegalArgumentException("这本书只有 " + chapters.size() + " 章，撑不起分裂");

        /* 模型分章命名 */
        Map<String, Long> cardCount = new LinkedHashMap<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
            if (file.equals(r.getBookFile()) && r.getChapter() != null) {
                cardCount.merge(r.getChapter(), 1L, Long::sum);
            }
        }
        StringBuilder sb = new StringBuilder("# 待分裂的书：《").append(oldTitle).append("》\n\n");
        for (int i = 0; i < chapters.size(); i++) {
            Map<String, Object> ch = chapters.get(i);
            sb.append(i + 1).append(". ").append(ch.get("title"))
                    .append("（").append(cardCount.getOrDefault(String.valueOf(ch.get("title")), 0L)).append(" 卡）：");
            List<String> secs = new ArrayList<>();
            for (Map<String, Object> s : sectionsOf(ch)) secs.add(String.valueOf(s.get("name")));
            sb.append(String.join(" · ", secs)).append('\n');
        }
        JsonNode n;
        try {
            n = parseJsonObj(miaomiao.agent(catId).chat(SPLIT_PROMPT, sb.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("分裂方案生成失败，请重试: " + e.getMessage(), e);
        }
        String titleA = n.path("titleA").asText("").strip();
        String titleB = n.path("titleB").asText("").strip();
        Set<String> toB = new LinkedHashSet<>();
        for (JsonNode x : n.path("chaptersB")) {
            String t = x.asText("").strip();
            if (!t.isBlank()) toB.add(t);
        }
        if (titleA.isBlank() || titleB.isBlank() || toB.isEmpty() || toB.size() >= chapters.size()) {
            throw new IllegalStateException("模型没有给出有效的分裂方案（两本书都要有章），请重试");
        }
        String descA = n.path("descriptionA").asText("").strip();
        String descB = n.path("descriptionB").asText("").strip();

        /* A = 未分出去的章（保持原顺序），B = 分出去的章 */
        List<Map<String, Object>> chA = new ArrayList<>();
        List<Map<String, Object>> chB = new ArrayList<>();
        for (Map<String, Object> ch : chapters) {
            (toB.contains(String.valueOf(ch.get("title"))) ? chB : chA).add(ch);
        }
        sk.put("title", titleA);
        if (!descA.isBlank()) sk.put("description", descA);
        sk.put("chapters", chA);
        Map<String, Object> skB = new LinkedHashMap<>();
        skB.put("file", nextBookFileName(books));
        skB.put("title", titleB);
        skB.put("description", descB.isBlank() ? titleB + " 相关内容" : descB);
        skB.put("chapters", chB);
        books.add(skB);

        /* 分出去的章，卡片改挂新书（重编号时统一改书名/文件名） */
        String fileB = String.valueOf(skB.get("file"));
        List<UnderstandingCard> toSave = new ArrayList<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
            if (!file.equals(r.getBookFile())) continue;
            if (r.getChapter() != null && toB.contains(r.getChapter())) r.setBookFile(fileB);
            toSave.add(r);
        }
        if (!toSave.isEmpty()) understandingRepo.saveAll(toSave);
        renumberAndRender(cat, skeleton);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("bookFile", fileB);
        r.put("title", titleB);
        r.put("message", "已把《" + oldTitle + "》分裂为《" + titleA + "》+《" + titleB + "》");
        return r;
    }

    /** 结构变更后统一重编号（01-x.md 连续）+ 批量改卡表 bookFile/bookTitle + 重渲染全部书 md */
    private void renumberAndRender(Cat cat, Map<String, Object> skeleton) throws Exception {
        List<Map<String, Object>> books = castBooks(skeleton);
        Map<String, String> fileMap = new LinkedHashMap<>();
        Map<String, String> titleMap = new LinkedHashMap<>();
        int i = 1;
        for (Map<String, Object> b : books) {
            String old = String.valueOf(b.get("file"));
            String nw = String.format("%02d-%s.md", i++, sanitize(String.valueOf(b.get("title"))));
            b.put("file", nw);
            fileMap.put(old, nw);
            titleMap.put(nw, String.valueOf(b.get("title")));
        }
        List<UnderstandingCard> toSave = new ArrayList<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(cat.getId())) {
            String nw = r.getBookFile() == null ? null : fileMap.get(r.getBookFile());
            if (nw == null) continue;
            r.setBookFile(nw);
            r.setBookTitle(clip(titleMap.get(nw), 300));
            toSave.add(r);
        }
        if (!toSave.isEmpty()) understandingRepo.saveAll(toSave);
        renderAllBooks(cat, skeleton);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castBooks(Map<String, Object> skeleton) {
        Object v = skeleton.get("books");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chaptersOf(Map<String, Object> book) {
        Object v = book.get("chapters");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sectionsOf(Map<String, Object> chapter) {
        Object v = chapter.get("sections");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    /** 从模型回复里抠出第一个 JSON 对象（容忍 ```json 包裹） */
    private JsonNode parseJsonObj(String reply) throws Exception {
        int l = reply.indexOf('{');
        int r = reply.lastIndexOf('}');
        if (l < 0 || r <= l) throw new IllegalArgumentException("模型未返回 JSON");
        return objectMapper.readTree(reply.substring(l, r + 1));
    }

    // ================= 知识点落库与 points/ 卡片库 =================

    /** 批次号：一次阅读任务的知识点/学习卡归同一批 */
    private String batchId(String taskId) {
        return "read-" + taskId;
    }

    /** 知识点落库（md 给人读，卡片给系统用）；行 id 塞回 map 供书回填与学习卡溯源 */
    private List<UnderstandingCard> saveCards(Cat cat, String batch, List<Map<String, Object>> cards) {
        List<UnderstandingCard> rows = new ArrayList<>();
        for (Map<String, Object> c : cards) {
            UnderstandingCard r = new UnderstandingCard();
            r.setCatId(cat.getId());
            r.setBatchId(batch);
            r.setNo(clip(String.valueOf(c.get("no")), 10));
            r.setKind(String.valueOf(c.getOrDefault("kind", UnderstandingCard.KIND_FACT)));
            Object bid = c.get("bookId");
            if (bid instanceof Number n) r.setBookId(n.longValue());
            Object cid = c.get("contentId");
            if (cid instanceof Number n) r.setContentId(n.longValue());
            r.setSrc(clip(String.valueOf(c.get("src")), 298));
            r.setTopic(clip(String.valueOf(c.get("topic")), 198));
            r.setPoint(String.valueOf(c.get("point")));
            r.setUsage(json(c.get("usage")));
            r.setLinks(json(c.get("links")));
            Object detail = c.get("detail");
            if (detail != null) r.setDetail(String.valueOf(detail));
            rows.add(r);
        }
        understandingRepo.saveAll(rows);
        for (int i = 0; i < rows.size(); i++) {
            cards.get(i).put("rowId", rows.get(i).getId());
        }
        /* 知识点↔文章关联落库：一个知识点可融合多篇文章（md 给人读，关联给系统查） */
        List<UnderstandingCardSource> srcs = new ArrayList<>();
        for (Map<String, Object> c : cards) {
            Object cid = c.get("contentIds");
            if (!(cid instanceof List<?> ids) || ids.isEmpty()) continue;
            String no = String.valueOf(c.get("no"));
            for (Object id : ids) {
                if (id instanceof Number n) {
                    UnderstandingCardSource s = new UnderstandingCardSource();
                    s.setCatId(cat.getId());
                    s.setNo(no);
                    s.setContentId(n.longValue());
                    srcs.add(s);
                }
            }
        }
        if (!srcs.isEmpty()) {
            /* 记录文章指纹：增量归纳据此判定「文章是否有修改需要重读」 */
            java.util.Set<Long> srcIds = new java.util.HashSet<>();
            for (UnderstandingCardSource s : srcs) srcIds.add(s.getContentId());
            Map<Long, String> fps = fingerprintMap(contentRepo.findAllById(srcIds));
            for (UnderstandingCardSource s : srcs) s.setFingerprint(fps.get(s.getContentId()));
            sourceRepo.saveAll(srcs);
        }
        return rows;
    }

    /** List/String 序列化为 JSON 字符串（null/空返回 null），落库 usage/links 用 */
    private String json(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> l && l.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** points/ 目录：知识点卡片库（一点一 md，形态像 skill） */
    private Path pointsDir(String workspacePath) {
        return catWorkspace.workspaceRoot(workspacePath).resolve(POINTS_DIR);
    }

    /** 清空 points/ 旧 md（任务重建时）：含书子文件夹整体重建 */
    private void clearPoints(String workspacePath) {
        Path dir = pointsDir(workspacePath);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.warn("[喵的整理] 清理旧知识点 md 失败: {}", e.getMessage());
        }
    }

    /** 每个知识点一个 md 卡片（skill 同款：名称+简介+什么时候使用+展开+出处），并在 enrich 后重写；
     *  卡片按书分组落盘：points/{书文件夹}/{no}-{topic}.md，一书一夹 */
    private void writePointMds(String workspacePath, List<Map<String, Object>> cards,
                               List<UnderstandingCard> rows) throws Exception {
        Map<Long, UnderstandingCard> rowById = new LinkedHashMap<>();
        for (UnderstandingCard r : rows) rowById.put(r.getId(), r);
        String stamp = LocalDateTime.now().format(FMT);
        for (Map<String, Object> c : cards) {
            Object rid = c.get("rowId");
            UnderstandingCard row = rid instanceof Number n ? rowById.get(n.longValue()) : null;
            String fname = pointFileName(c);
            String folder = bookFolderOf(c, row);
            librarySync.writeArtifact(workspacePath, POINTS_DIR + "/" + folder + "/" + fname, buildPointMd(c, row, stamp));
        }
    }

    /** 卡片所属书文件夹名（points/{书}/ 一书一夹）：书名净化后作夹名；拿不到书名归入「未归组」 */
    private String bookFolderOf(Map<String, Object> card, UnderstandingCard row) {
        Object t = card == null ? null : card.get("bookTitle");
        String title = t == null ? (row == null ? null : row.getBookTitle()) : String.valueOf(t);
        String name = sanitize(title == null ? "" : title);
        return name.isBlank() ? "未归组" : name;
    }

    /**
     * points/reading 三级 INDEX（喵整理落盘后调用）：
     * <ul>
     *   <li>points/INDEX.md —— 书目录表（一书一夹，点进去看卡）</li>
     *   <li>points/{书}/INDEX.md —— 该书的卡片清单（编号/标题/类型/简介）</li>
     *   <li>reading/INDEX.md —— 书架索引（书名 → 书 md → 卡片数）</li>
     * </ul>
     * 数据源 = 卡表全量（DB 为真相，磁盘只是投影），重入安全。
     */
    private void refreshPointsIndexes(Long catId, String workspacePath) {
        try {
            List<UnderstandingCard> rows = understandingRepo.findByCatIdOrderByIdAsc(catId);
            Map<String, List<UnderstandingCard>> byBook = new LinkedHashMap<>();
            for (UnderstandingCard r : rows) {
                byBook.computeIfAbsent(bookFolderOf(null, r), k -> new ArrayList<>()).add(r);
            }
            Path points = pointsDir(workspacePath);
            Files.createDirectories(points);
            StringBuilder pi = new StringBuilder();
            pi.append("# 🃏 理解卡片索引\n\n");
            pi.append("> 由喵藏自动维护 · 共 **").append(rows.size()).append("** 张卡片 · **").append(byBook.size()).append("** 本书\n\n");
            pi.append("| 书 | 卡片数 | 索引 |\n|---|---|---|\n");
            for (Map.Entry<String, List<UnderstandingCard>> e : byBook.entrySet()) {
                String folder = e.getKey();
                StringBuilder bi = new StringBuilder();
                bi.append("# 🃏 ").append(folder.replace("|", "/")).append(" · 卡片清单\n\n");
                bi.append("> 由喵藏自动维护 · 共 **").append(e.getValue().size()).append("** 张卡片\n\n");
                bi.append("| 编号 | 标题 | 类型 | 简介 |\n|---|---|---|---|\n");
                for (UnderstandingCard r : e.getValue()) {
                    String fname = pointFileName(displayNo(r), r.getTopic());
                    String topic = r.getTopic() == null ? "" : r.getTopic().replace("|", "/");
                    String pt = r.getPoint() == null ? "" : r.getPoint().replaceAll("\\s+", " ").replace("|", "/");
                    if (pt.length() > 60) pt = pt.substring(0, 60).strip() + "…";
                    bi.append("| ").append(displayNo(r)).append(" | [").append(topic).append("](").append(fname)
                      .append(") | ").append(kindLabel(r.getKind())).append(" | ").append(pt).append(" |\n");
                }
                bi.append("\n[← 返回 points 索引](../INDEX.md)\n");
                Path bookDir = points.resolve(folder);
                Files.createDirectories(bookDir);
                Files.writeString(bookDir.resolve("INDEX.md"), bi.toString(), StandardCharsets.UTF_8);
                pi.append("| 📁 ").append(folder.replace("|", "/")).append(" | ").append(e.getValue().size())
                  .append(" | [INDEX.md](").append(folder).append("/INDEX.md) |\n");
            }
            pi.append("\n> 每个文件夹 = 喵整理的一本书，点进去看这本书的理解卡片。\n");
            Files.writeString(points.resolve("INDEX.md"), pi.toString(), StandardCharsets.UTF_8);

            /* reading/INDEX.md：书架索引（按 bookFile 聚合） */
            Map<String, List<UnderstandingCard>> byFile = new LinkedHashMap<>();
            for (UnderstandingCard r : rows) {
                if (r.getBookFile() == null || r.getBookFile().isBlank()) continue;
                byFile.computeIfAbsent(r.getBookFile(), k -> new ArrayList<>()).add(r);
            }
            Path reading = catWorkspace.workspaceRoot(workspacePath).resolve(READING_DIR);
            Files.createDirectories(reading);
            StringBuilder ri = new StringBuilder();
            ri.append("# 📖 书架索引\n\n");
            ri.append("> 由喵藏自动维护 · 共 **").append(byFile.size()).append("** 本书\n\n");
            ri.append("| 书 | 文件 | 卡片数 |\n|---|---|---|\n");
            for (Map.Entry<String, List<UnderstandingCard>> e : byFile.entrySet()) {
                UnderstandingCard first = e.getValue().get(0);
                String title = first.getBookTitle() == null ? "" : first.getBookTitle().replace("|", "/");
                ri.append("| 📖 ").append(title).append(" | [").append(e.getKey()).append("](").append(e.getKey())
                  .append(") | ").append(e.getValue().size()).append(" |\n");
            }
            ri.append("\n> 书的正文由喵整理自动渲染；理解卡片在 points/ 对应书文件夹里。\n");
            Files.writeString(reading.resolve("INDEX.md"), ri.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[喵的整理] INDEX 生成失败 ({}): {}", workspacePath, e.toString());
        }
    }

    /** 启动迁移（幂等）：把历史平铺在 points/ 根的卡片 md 按 no → 书归位到 points/{书}/，并重建三级 INDEX */
    @PostConstruct
    public void migratePointsLayout() {
        for (Cat cat : catRepo.findAll()) {
            String ws = cat.getWorkspacePath();
            if (ws == null || ws.isBlank()) continue;
            try {
                Path points = pointsDir(ws);
                if (!Files.isDirectory(points)) continue;
                Map<String, UnderstandingCard> byNo = new HashMap<>();
                for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(cat.getId())) {
                    byNo.put(displayNo(r), r);
                }
                List<Path> flat;
                try (Stream<Path> s = Files.list(points)) {
                    flat = s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".md"))
                            .filter(p -> !"INDEX.md".equals(p.getFileName().toString()))
                            .toList();
                }
                if (flat.isEmpty()) continue;
                int moved = 0;
                for (Path p : flat) {
                    String fname = p.getFileName().toString();
                    int cut = fname.indexOf('-');
                    String no = cut > 0 ? fname.substring(0, cut) : fname.substring(0, fname.length() - 3);
                    UnderstandingCard r = byNo.get(no);
                    String folder = r == null ? "未归组" : bookFolderOf(null, r);
                    Path target = points.resolve(folder).resolve(fname);
                    if (Files.exists(target)) {
                        Files.deleteIfExists(p);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.move(p, target);
                    moved++;
                }
                refreshPointsIndexes(cat.getId(), ws);
                log.info("[喵的整理] 卡片归位 {}: 移入书文件夹 {} 张", ws, moved);
            } catch (Exception e) {
                log.warn("[喵的整理] 卡片归位跳过 ({}): {}", ws, e.toString());
            }
        }
    }

    /** 知识点 md 文件名：编号-名称.md（排序即编号序） */
    private String pointFileName(Map<String, Object> card) {
        String no = String.valueOf(card.get("no"));
        return no + "-" + sanitize(String.valueOf(card.get("topic"))) + ".md";
    }

    /** 知识点 md 文件名（字符串版，供 INDEX 生成复用） */
    private String pointFileName(String no, String topic) {
        return no + "-" + sanitize(topic == null ? "" : topic) + ".md";
    }

    /** 知识点 md 正文：H1 名称 + 徽标行（kind/书/关联）+ 简介 + 什么时候使用 + 展开 + 出处 */
    private String buildPointMd(Map<String, Object> card, UnderstandingCard row, String stamp) {
        String kindLabel = kindLabel(String.valueOf(card.getOrDefault("kind", UnderstandingCard.KIND_FACT)));
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(card.get("topic")).append("\n\n");
        sb.append("> ").append(kindLabel).append(" · 📖 ").append(card.get("bookTitle"));
        Object links = card.get("links");
        if (links instanceof List<?> l && !l.isEmpty()) {
            sb.append(" · ⇄ 关联：").append(String.join("、", l.stream().map(String::valueOf).toList()));
        }
        sb.append("\n\n## 简介\n\n").append(card.get("point")).append("\n\n");
        sb.append("## 什么时候使用\n\n");
        Object usage = card.get("usage");
        if (usage instanceof List<?> ul && !ul.isEmpty()) {
            for (Object u : ul) sb.append("- ").append(u).append("\n");
        } else {
            sb.append("- 待补充\n");
        }
        String detail = card.get("detail") == null ? (row == null ? null : row.getDetail()) : String.valueOf(card.get("detail"));
        if (detail != null && !detail.isBlank()) {
            sb.append("\n## 展开\n\n").append(detail.strip()).append("\n");
        } else {
            sb.append("\n## 展开\n\n（这只喵还在消化这一节……）\n");
        }
        /* 出处：书 + 融合来源文章（关联关系落库，md 里同步列出让文件自含完整出处） */
        sb.append("\n## 出处\n\n- ").append(card.get("src")).append(" · ").append(stamp).append("\n");
        Object srcTitles = card.get("srcTitles");
        if (srcTitles instanceof List<?> tl && !tl.isEmpty()) {
            for (Object t : tl) sb.append("  - 📄 ").append(t).append("\n");
        }
        return sb.toString();
    }

    /** kind 显示标签 */
    private String kindLabel(String kind) {
        return switch (kind) {
            case UnderstandingCard.KIND_KEY -> "🔑 关键";
            case UnderstandingCard.KIND_FOCUS -> "⭐ 重点";
            case UnderstandingCard.KIND_DIFFICULT -> "🔴 难点";
            default -> "💡 知识点";
        };
    }

    // ================= 阶段三 ENRICH：逐点读厚 =================

    /** detail 跨任务缓存：同书同名知识点的旧补全直接复用（重跑不重复烧钱） */
    private Map<String, String> loadDetailCache(Long catId) {
        Map<String, String> cache = new LinkedHashMap<>();
        try {
            for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
                if (r.getDetail() == null || r.getDetail().isBlank()) continue;
                cache.put(r.getBookId() + "|" + r.getTopic(), r.getDetail());
            }
        } catch (Exception e) {
            log.warn("[喵的整理] 读取旧知识点 detail 缓存失败（首次运行可忽略）: {}", e.getMessage());
        }
        return cache;
    }

    /** 知识点的原文素材：出处文章的正文合集（关联表 contentIds → 每篇限量拼接，供读厚引用与防抄校验） */
    private String originBody(Map<String, Object> card, List<ContentItem> items) {
        Object ids = card.get("contentIds");
        if (ids instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object id : list) {
                if (!(id instanceof Number n)) continue;
                for (ContentItem c : items) {
                    if (!c.getId().equals(n.longValue())) continue;
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append("《").append(c.getTitle()).append("》：").append(articleBody(c));
                    break;
                }
            }
            if (sb.length() > 0) return clip(sb.toString(), 2500);
        }
        /* 兜底：无关联时退回主出处/简介 */
        Object cid = card.get("contentId");
        if (cid instanceof Number n) {
            for (ContentItem c : items) {
                if (c.getId().equals(n.longValue())) return clip(articleBody(c), 2500);
            }
        }
        return String.valueOf(card.get("point"));
    }

    /**
     * 阶段三 ENRICH：标准档——只对「重点/难点」逐点读厚（补 detail）。
     * 增量幂等：detail 缓存命中直接复用；单点失败静默跳过不阻断任务；预算耗尽即停。
     */
    private int enrichPhase(ReadingTask task, Cat cat, MiaoMiaoAgent agent, boolean live,
                            List<Map<String, Object>> cards, List<UnderstandingCard> rows,
                            Map<String, String> detailCache, List<ContentItem> items, AtomicInteger budget) {
        Map<Long, UnderstandingCard> rowById = new LinkedHashMap<>();
        for (UnderstandingCard r : rows) rowById.put(r.getId(), r);
        int enriched = 0;
        int plan = 0;
        for (Map<String, Object> c : cards) {
            String kind = String.valueOf(c.getOrDefault("kind", ""));
            if (UnderstandingCard.KIND_FOCUS.equals(kind) || UnderstandingCard.KIND_DIFFICULT.equals(kind)) plan++;
        }
        task.stage = "read";
        task.stageDone = 0;
        task.stageTotal = plan;
        for (Map<String, Object> c : cards) {
            String kind = String.valueOf(c.getOrDefault("kind", ""));
            if (!UnderstandingCard.KIND_FOCUS.equals(kind) && !UnderstandingCard.KIND_DIFFICULT.equals(kind)) continue;
            String topic = String.valueOf(c.get("topic"));
            /* 缓存命中：同书同名旧补全直接复用 */
            String cached = detailCache.get(c.get("bookId") + "|" + topic);
            if (cached != null && !cached.isBlank()) {
                applyDetail(c, rowById, cached);
                enriched++;
                task.stageDone = enriched;
                continue;
            }
            if (budget.get() <= 0) {
                task.current = "读厚预算耗尽，剩余知识点下次重跑时补全";
                break;
            }
            task.current = "正在读厚「" + clip(topic, 18) + "」（重点/难点逐点补全）…";
            if (!live) break;
            try {
                String inputs = "知识点：" + topic + "\n简介：" + c.get("point") + "\n\n相关原文：\n" + originBody(c, items);
                HarnessRunContext ctx = newCtx(cat.getId(), "ENRICH",
                        "为知识点写一段补充展开正文。出参标准：一段 80~800 字连贯中文（不是 JSON），"
                                + "说清为什么重要/难在哪 + 一个例子 + 常见误解，禁止照抄原文。",
                        inputs, budget, task, live);
                String detail = PipelineRunner.runStage(enrichSpec(), ctx,
                        x -> {
                            try {
                                return agent.chat(ENRICH_PROMPT, x.buildPrompt("写出这个知识点的补充展开（只输出一段正文）"));
                            } catch (Exception e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        },
                        x -> null);
                if (detail != null && !detail.isBlank()) {
                    applyDetail(c, rowById, detail.strip());
                    enriched++;
                }
                task.stageDone = enriched;
            } catch (Exception e) {
                log.warn("[喵的整理] 知识点「{}」读厚失败，跳过: {}", topic, e.getMessage());
            }
        }
        if (enriched > 0) {
            /* 更新 md 的「## 展开」段（重写对应文件） */
            try {
                writePointMds(cat.getWorkspacePath(), cards, rows);
                refreshPointsIndexes(cat.getId(), cat.getWorkspacePath());
            } catch (Exception e) {
                log.warn("[喵的整理] 更新知识点 md 失败: {}", e.getMessage());
            }
            task.current = "读厚完成：" + enriched + "/" + plan + " 个重点难点已补全";
        }
        return enriched;
    }

    /** detail 回填：内存 map + 数据库行同步更新 */
    private void applyDetail(Map<String, Object> card, Map<Long, UnderstandingCard> rowById, String detail) {
        card.put("detail", detail);
        Object rid = card.get("rowId");
        if (rid instanceof Number n) {
            UnderstandingCard row = rowById.get(n.longValue());
            if (row != null) {
                row.setDetail(clip(detail, MAX_DETAIL_LEN));
                understandingRepo.save(row);
            }
        }
    }

    /** 门禁重试耗尽后的降级兜底：每 8 张卡按序切一本《知识拾遗 N》，保证流水线总能出书 */
    private List<Map<String, Object>> deterministicOutline(List<Map<String, Object>> cards) {
        List<Map<String, Object>> outline = new ArrayList<>();
        int bookSize = 8;
        int secSize = 4;
        int bi = 1;
        for (int from = 0; from < cards.size(); from += bookSize) {
            int to = Math.min(from + bookSize, cards.size());
            List<Map<String, Object>> secs = new ArrayList<>();
            int si = 1;
            for (int s = from; s < to; s += secSize) {
                int e = Math.min(s + secSize, to);
                List<String> nos = new ArrayList<>();
                for (int k = s; k < e; k++) nos.add(String.valueOf(cards.get(k).get("no")));
                Map<String, Object> sec = new LinkedHashMap<>();
                sec.put("name", "知识点拾遗 " + bi + "-" + si);
                sec.put("point", "按序收拢的一组理解卡片（自动兜底整理）");
                sec.put("cards", nos);
                secs.add(sec);
                si++;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("title", "知识拾遗 " + bi);
            b.put("description", "由 " + (to - from) + " 张理解卡片按序收拢的兜底书（模型归纳未过门禁）");
            b.put("chapters", List.of(Map.of("title", "拾遗", "sections", secs)));
            outline.add(b);
            bi++;
        }
        return outline;
    }

    /**
     * 阶段三 LEARN：对每本书，收编其二级主题下的理解卡片出学习卡（≤5 张，宁少勿多）。
     * live 走 runStage 契约门禁（失败降级确定性出卡），mock 直接确定性出卡；落库 + md 学习卡片段。
     */
    @SuppressWarnings("unchecked")
    private List<LearnCard> learnPhase(ReadingTask task, Cat cat, MiaoMiaoAgent agent, boolean live,
                                       List<Map<String, Object>> saved,
                                       List<Map<String, Object>> cards,
                                       AtomicInteger budget) {
        Map<String, Map<String, Object>> byNo = new LinkedHashMap<>();
        for (Map<String, Object> c : cards) byNo.put(String.valueOf(c.get("no")), c);
        List<LearnCard> all = new ArrayList<>();
        task.stage = "learn";
        task.stageDone = 0;
        task.stageTotal = saved.size();
        int learnDone = 0;
        for (Map<String, Object> b : saved) {
            String file = String.valueOf(b.get("file"));
            String title = String.valueOf(b.get("title"));
            /* 收编本书的理解卡片（按骨架 chapters.sections.cards 反查） */
            List<Map<String, Object>> bookCards = new ArrayList<>();
            for (Map<String, Object> ch : (List<Map<String, Object>>) b.get("chapters")) {
                for (Map<String, Object> s : (List<Map<String, Object>>) ch.get("sections")) {
                    Object nos = s.get("cards");
                    if (nos instanceof List<?> l) {
                        for (Object n : l) {
                            Map<String, Object> c = byNo.get(String.valueOf(n));
                            if (c != null) bookCards.add(c);
                        }
                    }
                }
            }
            if (bookCards.isEmpty()) continue;
            task.stageDone = ++learnDone;
            task.current = "《" + title + "》正在提炼学习卡片…";
            List<Map<String, Object>> learned;
            if (live) {
                try {
                    HarnessRunContext ctx = newCtx(cat.getId(), "LEARN",
                            "为《" + title + "》提炼学习卡片。出参标准：JSON 数组 1~5 张，每张含 kind/front/back。",
                            buildLearnPrompt(title, bookCards), budget, task, live);
                    learned = PipelineRunner.runStage(learnSpec(), ctx,
                            x -> {
                                try {
                                    return parseLearnCards(agent.chat(LEARN_PROMPT, x.buildPrompt("为这本书出学习卡片（只输出 JSON 数组）")));
                                } catch (Exception e) {
                                    throw new RuntimeException(e.getMessage(), e);
                                }
                            },
                            x -> fallbackLearnCards(bookCards));
                } catch (Exception e) {
                    log.warn("[喵的整理] 《{}》学习卡生成失败，降级兜底: {}", title, e.getMessage());
                    learned = fallbackLearnCards(bookCards);
                }
            } else {
                learned = fallbackLearnCards(bookCards);
            }
            /* 学习卡落库：溯源到理解卡片行（模型给的 srcs 编号反查）+ 所属书 */
            List<LearnCard> rows = new ArrayList<>();
            for (Map<String, Object> k : learned) {
                if (k.get("rowId") == null && k.get("srcs") instanceof List<?> srcs) {
                    for (Object s : srcs) {
                        Map<String, Object> src = byNo.get(String.valueOf(s));
                        if (src != null && src.get("rowId") instanceof Number n) { k.put("rowId", n); break; }
                    }
                }
                LearnCard lc = new LearnCard();
                lc.setCatId(cat.getId());
                if (k.get("rowId") instanceof Number n) lc.setUnderstandingCardId(n.longValue());
                lc.setBookFile(file);
                lc.setBookTitle(clip(title, 300));
                lc.setKind(String.valueOf(k.getOrDefault("kind", LearnCard.KIND_RECALL)));
                lc.setFront(String.valueOf(k.get("front")));
                lc.setBack(String.valueOf(k.get("back")));
                rows.add(lc);
            }
            learnRepo.saveAll(rows);
            all.addAll(rows);
            /* md 末尾追加「## 学习卡片」段落（人可读） */
            appendLearnSection(cat, file, learned);
        }
        return all;
    }

    /** 门禁重试耗尽 / mock 模式下的确定性学习卡：取前 2 张理解卡转 recall 问答 */
    private List<Map<String, Object>> fallbackLearnCards(List<Map<String, Object>> bookCards) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : bookCards) {
            if (out.size() >= 2) break;
            String topic = String.valueOf(c.get("topic"));
            String point = String.valueOf(c.get("point"));
            if (point.isBlank()) continue;
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", LearnCard.KIND_RECALL);
            k.put("front", "关于「" + topic + "」，你还记得什么？");
            k.put("back", clip(point, 300));
            k.put("rowId", c.get("rowId"));
            out.add(k);
        }
        return out;
    }

    /** 学习卡精读输入：书的主题结构 + 收编的理解卡片清单 */
    private String buildLearnPrompt(String title, List<Map<String, Object>> bookCards) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 待出学习卡的书：《").append(title).append("》\n\n");
        sb.append("这本书收编的理解卡片（共 ").append(bookCards.size()).append(" 张）：\n\n");
        for (Map<String, Object> c : bookCards) {
            sb.append("- ").append(c.get("no")).append("「").append(c.get("topic")).append("」")
                    .append(c.get("point")).append("\n");
        }
        sb.append("\n请提炼出最值得记住的学习卡片（只输出 JSON 数组）。\n");
        return sb.toString();
    }

    /** 从模型回复中提取学习卡数组（容忍 ```json 包裹 / 前后缀文字） */
    private List<Map<String, Object>> parseLearnCards(String reply) throws Exception {
        if (reply == null || reply.isBlank()) return List.of();
        int l = reply.indexOf('[');
        int r = reply.lastIndexOf(']');
        if (l < 0 || r <= l) return List.of();
        JsonNode arr = objectMapper.readTree(reply.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String front = n.path("front").asText("").strip();
            String back = n.path("back").asText("").strip();
            if (front.isBlank() && back.isBlank()) continue;
            Map<String, Object> k = new LinkedHashMap<>();
            String kind = n.path("kind").asText(LearnCard.KIND_RECALL).strip();
            k.put("kind", kind.isBlank() ? LearnCard.KIND_RECALL : kind);
            k.put("front", front.isBlank() ? "（空问题）" : front);
            k.put("back", back);
            if (n.has("srcs") && n.get("srcs").isArray()) {
                List<String> srcs = new ArrayList<>();
                n.get("srcs").forEach(x -> srcs.add(x.asText("").strip()));
                k.put("srcs", srcs);
            }
            out.add(k);
        }
        return out;
    }

    /** 在书 md 末尾追加「## 学习卡片」段落（人可读） */
    private void appendLearnSection(Cat cat, String file, List<Map<String, Object>> learned) {
        if (learned.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n## 学习卡片\n\n");
            int i = 1;
            for (Map<String, Object> k : learned) {
                sb.append(i++).append(". 🎓 **").append(k.get("front")).append("**\n");
                sb.append("   ").append(k.get("back")).append("\n\n");
            }
            librarySync.appendArtifact(cat.getWorkspacePath(), READING_DIR + "/" + file, sb.toString());
        } catch (Exception e) {
            log.warn("[喵的整理] 追加学习卡片段失败 {}: {}", file, e.getMessage());
        }
    }

    /** 解析 reading/ 里的一本书 md：标题 / 描述 / 二级主题列表 */
    private Map<String, Object> parseBookMd(Path p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", p.getFileName().toString());
        m.put("title", "");
        m.put("description", "");
        List<Map<String, Object>> secs = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("# 《") && t.endsWith("》")) {
                    m.put("title", t.substring(3, t.length() - 1));
                } else if (t.startsWith("> ") && String.valueOf(m.get("description")).isEmpty()) {
                    m.put("description", t.substring(2).strip());
                } else if (t.startsWith("### ")) {
                    /* 兼容两级编号：旧式「N. 节名」与章节化「N.M 节名」都剥掉序号前缀 */
                    String name = t.substring(4).replaceFirst("^\\d+(\\.\\d+)*\\.?\\s*", "").strip();
                    Map<String, Object> sec = new LinkedHashMap<>();
                    sec.put("name", name);
                    sec.put("point", "");
                    sec.put("cardCount", 0);
                    secs.add(sec);
                } else if (t.startsWith("- 📇") && !secs.isEmpty()) {
                    /* 理解卡片行：归入最近的二级主题，只数数量 */
                    Map<String, Object> last = secs.get(secs.size() - 1);
                    last.put("cardCount", (int) last.get("cardCount") + 1);
                }
            }
        } catch (Exception ignored) {
            // 单个文件解析失败不阻断书架
        }
        m.put("sections", secs);
        return m;
    }

    /** 学习中心「🗂 理解卡片」页签：读理解卡片表（读厚产物可独立浏览，含出处/所属书回链） */
    /** 知识点卡片列表（理解卡片墙）：含编号/四类/简介/使用场景/关联/补全正文与书籍回链 + 出处文章（关联表） */
    public List<Map<String, Object>> listCards(Long catId) {
        /* 关联表 → no → 文章标题列表（一次查出，标题批量回填） */
        Map<String, List<Map<String, Object>>> srcMap = new LinkedHashMap<>();
        List<Long> contentIds = new ArrayList<>();
        for (UnderstandingCardSource s : sourceRepo.findByCatId(catId)) {
            contentIds.add(s.getContentId());
        }
        Map<Long, String> titleById = new LinkedHashMap<>();
        if (!contentIds.isEmpty()) {
            for (ContentItem it : contentRepo.findAllById(contentIds)) {
                titleById.put(it.getId(), it.getTitle());
            }
        }
        for (UnderstandingCardSource s : sourceRepo.findByCatId(catId)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", s.getContentId());
            t.put("title", titleById.getOrDefault(s.getContentId(), "未命名内容"));
            srcMap.computeIfAbsent(s.getNo(), k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (UnderstandingCard r : understandingRepo.findByCatIdOrderByIdAsc(catId)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", r.getId());
            /* no 兜底：历史批次落库时未分配编号（文件名另编），按行 id 回填 Pxxxx 保证详情弹窗可定位 */
            c.put("no", r.getNo() == null || r.getNo().isBlank() || "null".equals(r.getNo())
                    ? String.format("P%04d", r.getId()) : r.getNo());
            c.put("kind", r.getKind() == null ? UnderstandingCard.KIND_FACT : r.getKind());
            c.put("contentId", r.getContentId());
            c.put("sources", srcMap.getOrDefault(r.getNo(), List.of()));
            c.put("topic", r.getTopic());
            c.put("point", r.getPoint());
            c.put("usage", parseJsonArray(r.getUsage()));
            c.put("links", parseJsonArray(r.getLinks()));
            c.put("enriched", r.getDetail() != null && !r.getDetail().isBlank());
            c.put("detail", r.getDetail());
            c.put("src", r.getSrc());
            c.put("bookId", r.getBookId());
            c.put("bookTitle", r.getBookTitle());
            c.put("file", r.getBookFile());
            c.put("chapter", r.getChapter());
            c.put("section", r.getSection());
            out.add(c);
        }
        return out;
    }

    /** JSON 字符串 → 数组（容忍脏数据） */
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = objectMapper.readTree(json);
            List<String> out = new ArrayList<>();
            if (n.isArray()) for (JsonNode x : n) out.add(x.asText(""));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================= 工具 =================

    /** 猫工作区的整理目录：<library.dir>/<workspacePath>/reading/ */
    private Path readingDir(String workspacePath) {
        return catWorkspace.workspaceRoot(workspacePath).resolve(READING_DIR);
    }

    private String clip(String s, int len) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= len ? t : t.substring(0, len) + "…";
    }

    private String sanitize(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|\\s#]+", "-");
        if (s.startsWith("-")) s = s.substring(1);
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isBlank() ? "untitled" : s;
    }

    // ================= 任务对象 =================

    public static class ReadingTask {
        public String id;
        public Long catId;
        public String catName;
        public String status; // running / done / error
        public String mode;   // auto（增量） / full（全量）
        public String current;
        /** 当前流水线阶段：read（读厚）/ thin（读薄）/ learn（学透）——前端点亮对应节点 */
        public String stage;
        public int stageDone;
        public int stageTotal;
        public String error;
        public int bookCount;
        public int cardCount;
        public int enrichCount;
        public int learnCount;
        public List<Map<String, Object>> books;
        public String createdAt = LocalDateTime.now().format(FMT);

        public ReadingTask() { }
        public String getId() { return id; }
        public String getStatus() { return status; }
        public String getStage() { return stage; }
        public int getStageDone() { return stageDone; }
        public int getStageTotal() { return stageTotal; }
        public int getBookCount() { return bookCount; }
        public int getCardCount() { return cardCount; }
        public int getEnrichCount() { return enrichCount; }
        public int getLearnCount() { return learnCount; }
    }
}
