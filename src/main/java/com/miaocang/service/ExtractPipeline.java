package com.miaocang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.AgentFeedback;
import com.miaocang.entity.AgentSkill;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.ContentItem;
import com.miaocang.repository.AgentFeedbackRepository;
import com.miaocang.repository.AgentSkillRepository;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatalogNodeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.service.agent.MiaoMiaoAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 喵喵内容提取工作流：
 * 采集入库 → 全自动提取（摘要/要点/金句/标签/归类建议/关联）→ 主人反馈累积
 * → 反馈攒够后喵喵自动总结生成新的 PREFERENCE 技能 → 技能反过来影响下次提取。
 *
 * 提取结果 JSON 存在 ContentItem.extract；技能指令（EXTRACT + PREFERENCE）拼进 Agent 提示词。
 */
@Service
public class ExtractPipeline {

    private static final Logger log = LoggerFactory.getLogger(ExtractPipeline.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 未消化反馈达到该数量，喵喵就总结生成新技能 */
    private static final int SKILL_SUMMARY_THRESHOLD = 6;
    private static final int MAX_BOOK_OPTIONS = 30;
    private static final int MAX_CANDIDATES = 20;

    private final ContentItemRepository contentRepo;
    private final BookRepository bookRepo;
    private final BookTypeRepository typeRepo;
    private final CatalogNodeRepository catalogRepo;
    private final CatRepository catRepo;
    private final AgentSkillRepository skillRepo;
    private final AgentFeedbackRepository feedbackRepo;
    private final MiaomiaoService miaomiao;
    private final LibrarySyncService librarySync;
    private final ExtractService extractService;
    private final ObjectMapper mapper;

    /** 正在提取中的内容 id，防止重复触发 */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor((r) -> {
        Thread t = new Thread(r, "miaomiao-extract");
        t.setDaemon(true);
        return t;
    });

    public ExtractPipeline(ContentItemRepository contentRepo, BookRepository bookRepo,
                           BookTypeRepository typeRepo, CatalogNodeRepository catalogRepo,
                           CatRepository catRepo, AgentSkillRepository skillRepo,
                           AgentFeedbackRepository feedbackRepo, MiaomiaoService miaomiao,
                           LibrarySyncService librarySync, ExtractService extractService, ObjectMapper mapper) {
        this.contentRepo = contentRepo;
        this.bookRepo = bookRepo;
        this.typeRepo = typeRepo;
        this.catalogRepo = catalogRepo;
        this.catRepo = catRepo;
        this.skillRepo = skillRepo;
        this.feedbackRepo = feedbackRepo;
        this.miaomiao = miaomiao;
        this.librarySync = librarySync;
        this.extractService = extractService;
        this.mapper = mapper;
    }

    /** 内置技能：启动时给每只猫各播种一份（每只猫的预设技能集互相独立） */
    @PostConstruct
    public void seedPresets() {
        for (var c : catRepo.findAll()) seedPresetsForCat(c.getId());
    }

    /** 给指定猫播种内置技能（幂等；领养新猫时也会调用） */
    public void seedPresetsForCat(Long catId) {
        preset(catId, "extract.basics", AgentSkill.KIND_EXTRACT, "基础提取约定",
                "喵喵提取内容时的默认输出约定",
                "摘要 100 字以内、说人话；要点 3~4 条、每条一句话、提炼观点而非复述；金句最多 1 条且必须是原文；标签 3~6 个、优先用内容里出现过的词；归类建议必须给理由。");
        preset(catId, "prefer.plain", AgentSkill.KIND_PREFERENCE, "主人口味：简洁直接",
                "从历史偏好中沉淀的表达风格",
                "主人喜欢简洁直接的表达，不要八股腔和空洞的形容词；标题党词汇不要出现在摘要里。");
    }

    private void preset(Long catId, String baseCode, String kind, String name, String desc, String content) {
        /* 该猫已有同名技能（含老库迁移过来的）就不重复播种；同猫同名多条时也视为已存在 */
        if (!skillRepo.findByCatIdAndName(catId, name).isEmpty()) return;
        String code = "cat" + catId + "." + baseCode;
        if (skillRepo.findByCode(code).isPresent()) return;
        AgentSkill s = new AgentSkill();
        s.setCode(code);
        s.setCatId(catId);
        s.setKind(kind);
        s.setName(name);
        s.setDescription(desc);
        s.setContent(content);
        s.setSource(AgentSkill.SOURCE_PRESET);
        skillRepo.save(s);
        log.info("[喵喵] 给猫 {} 播种内置技能 {}", catId, code);
    }

    // ================= 提取 =================

    /** 采集后全自动触发（异步） */
    public void extractAsync(Long contentId) {
        if (running.contains(contentId)) return;
        running.add(contentId);
        executor.submit(() -> {
            try {
                extractNow(contentId);
            } catch (Exception e) {
                log.error("[喵喵] 自动提取内容 {} 失败: {}", contentId, e.getMessage(), e);
            } finally {
                running.remove(contentId);
            }
        });
    }

    public boolean isRunning(Long contentId) {
        return running.contains(contentId);
    }

    /** 同步提取（手动"重新提取"入口），返回提取结果 */
    public Map<String, Object> extractNow(Long contentId) throws Exception {
        ContentItem item = contentRepo.findById(contentId)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + contentId));
        if (item.getRawText() == null || item.getRawText().isBlank()) {
            /* 网页链接类内容：打开网页重新抓文章正文再提取（采集时抓取失败的条目靠这里补课） */
            if (ContentItem.TYPE_URL.equals(item.getContentType())
                    && item.getSource() != null && !item.getSource().isBlank()) {
                refetchUrl(item);
            } else {
                throw new IllegalArgumentException("该内容没有正文，无法提取");
            }
        }

        List<AgentSkill> skills = skillsFor(item);
        List<String> directives = skills.stream().map(s -> "【" + skillLabel(s) + "】" + s.getContent()).toList();
        Long catId = catOf(item);

        running.add(contentId);
        try {
            MiaoMiaoAgent.ExtractResult r = miaomiao.agent(catId).extract(buildTask(item, directives));
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("summary", r.summary());
            json.put("keyPoints", r.keyPoints());
            json.put("quotes", r.quotes());
            json.put("tags", r.tags());
            if (r.suggest() != null) {
                Map<String, Object> sg = new LinkedHashMap<>();
                sg.put("bookId", r.suggest().bookId());
                sg.put("bookTitle", r.suggest().bookTitle() == null ? "" : r.suggest().bookTitle());
                sg.put("reason", r.suggest().reason() == null ? "" : r.suggest().reason());
                json.put("suggest", sg);
            }
            json.put("related", r.related());
            json.put("applied", false);
            json.put("skillCodes", skills.stream().map(AgentSkill::getCode).toList());
            json.put("extractedAt", LocalDateTime.now().format(FMT));
            json.put("mode", miaomiao.liveMode(catId) ? "live" : "mock");
            item.setExtract(mapper.writeValueAsString(json));
            contentRepo.save(item);

            // 技能使用计数
            for (AgentSkill s : skills) {
                s.setUsageCount(s.getUsageCount() + 1);
                skillRepo.save(s);
            }
            log.info("[喵喵] 内容「{}」提取完成（{}）", item.getTitle(), json.get("mode"));
            return json;
        } finally {
            running.remove(contentId);
        }
    }

    /** 网页链接内容提取前补课：重新打开 URL 抓文章正文，成功则回写条目（下次提取不用再抓） */
    private void refetchUrl(ContentItem item) {
        String url = item.getSource();
        log.info("[喵喵] 内容「{}」没有正文，打开网页重新抓取: {}", item.getTitle(), url);
        ExtractService.ExtractResult r = extractService.extractUrl(url);
        if (!r.ok() || r.rawText() == null || r.rawText().isBlank()) {
            throw new IllegalArgumentException("打开网页抓取文章失败：" + (r.error() == null ? "未取到正文" : r.error())
                    + "。可稍后重试，或手动补充正文后再提取");
        }
        item.setRawText(r.rawText());
        if (r.summary() != null && !r.summary().isBlank()) item.setSummary(r.summary());
        /* 原 title 是抓取失败时落下的 host 占位（如 mp.weixin.qq.com），用网页真实标题覆盖 */
        if ((item.getTitle() == null || item.getTitle().isBlank()
                || item.getTitle().equalsIgnoreCase(extractService.hostOf(url)))
                && r.title() != null && !r.title().isBlank()) {
            item.setTitle(r.title());
        }
        contentRepo.save(item);
        log.info("[喵喵] 网页正文抓取成功（{} 字），继续提取", r.rawText().length());
    }

    /** 内容归属的猫：内容 → 书 → 分类 → 猫；无归属（如还在收集箱）时由第一只猫代管 */
    public Long catOf(ContentItem item) {
        if (item.getBookId() != null) {
            Long catId = bookRepo.findById(item.getBookId())
                    .map(Book::getTypeId)
                    .flatMap(typeRepo::findById)
                    .map(BookType::getCatId)
                    .orElse(null);
            if (catId != null) return catId;
        }
        return firstCatId();
    }

    public Long catOfContent(Long contentId) {
        return contentRepo.findById(contentId).map(this::catOf).orElseGet(this::firstCatId);
    }

    public Long firstCatId() {
        return catRepo.findAll().stream()
                .min((a, b) -> {
                    int x = a.getOrderIndex() == null ? 0 : a.getOrderIndex();
                    int y = b.getOrderIndex() == null ? 0 : b.getOrderIndex();
                    return x != y ? Integer.compare(x, y) : Long.compare(a.getId(), b.getId());
                })
                .map(com.miaocang.entity.Cat::getId)
                .orElse(null);
    }

    /** 该猫启用中的技能（提取提示词的「预设配置」来源） */
    public List<AgentSkill> skillsFor(ContentItem item) {
        Long catId = catOf(item);
        return catId == null
                ? List.of()
                : skillRepo.findByCatIdAndEnabledTrueOrderByKindAscIdAsc(catId);
    }

    private MiaoMiaoAgent.ExtractTask buildTask(ContentItem item, List<String> directives) {
        String currentBook = null;
        if (item.getBookId() != null) {
            currentBook = bookRepo.findById(item.getBookId()).map(Book::getTitle).orElse(null);
        }
        List<String> tags = new ArrayList<>();
        if (item.getMatchedTags() != null && !item.getMatchedTags().isBlank()) {
            for (String t : item.getMatchedTags().split(",")) {
                if (!t.isBlank()) tags.add(t.strip());
            }
        }
        List<MiaoMiaoAgent.BookOption> books = new ArrayList<>();
        for (Book b : bookRepo.findAll()) {
            if (books.size() >= MAX_BOOK_OPTIONS) break;
            books.add(new MiaoMiaoAgent.BookOption(b.getId(), b.getTitle(), b.getDescription()));
        }
        List<MiaoMiaoAgent.RelatedCandidate> candidates = new ArrayList<>();
        for (ContentItem c : contentRepo.findTop20ByOrderByIdDesc()) {
            if (c.getId().equals(item.getId())) continue;
            if (candidates.size() >= MAX_CANDIDATES) break;
            candidates.add(new MiaoMiaoAgent.RelatedCandidate(c.getId(), c.getTitle(), c.getSummary()));
        }
        return new MiaoMiaoAgent.ExtractTask(item.getTitle(), item.getContentType(), item.getRawText(),
                item.getSummary(), currentBook, tags, books, candidates, directives);
    }

    // ================= 主人反馈 / 采纳动作 =================

    /** 采纳归类建议：移动到建议的书（书根），并把建议标签一并合入 */
    public Map<String, Object> applySuggestion(Long contentId) {
        ContentItem item = contentRepo.findById(contentId)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + contentId));
        Map<String, Object> ex = parseExtract(item);
        if (ex.isEmpty()) throw new IllegalArgumentException("该内容还没有提取结果");

        Object bookIdObj = prop(ex, "suggest", "bookId");
        Long bookId = bookIdObj instanceof Number n ? n.longValue() : null;
        String reason = String.valueOf(propOr(ex, "suggest", "reason", ""));
        if (bookId == null) {
            throw new IllegalArgumentException("喵喵这条建议没有给出具体落点（" + reason + "），先接入真实模型或手动移动吧");
        }
        Book book = bookRepo.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("建议的书不存在: " + bookId));

        item.setBookId(book.getId());
        item.setCatalogNodeId(null);
        item.setStatus(ContentItem.STATUS_CLASSIFIED);
        item.setAutoClassified(false);
        librarySync.relocateContent(item);
        librarySync.commit("喵喵建议归档: " + item.getTitle());
        contentRepo.save(item);

        markApplied(item);
        record(contentId, AgentFeedback.ACTION_ACCEPT_APPLY, null,
                book.getTitle() + (reason.isBlank() ? "" : "（" + reason + "）"));
        return Map.of("bookId", book.getId(), "bookTitle", book.getTitle());
    }

    /** 采纳标签：把建议标签合入 matchedTags */
    public ContentItem acceptTags(Long contentId) {
        ContentItem item = contentRepo.findById(contentId)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + contentId));
        Map<String, Object> ex = parseExtract(item);
        if (ex.isEmpty()) throw new IllegalArgumentException("该内容还没有提取结果");

        Object tagsObj = ex.get("tags");
        if (!(tagsObj instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("喵喵这条没有建议标签");
        }
        var merged = new java.util.LinkedHashSet<String>();
        if (item.getMatchedTags() != null) {
            for (String t : item.getMatchedTags().split(",")) if (!t.isBlank()) merged.add(t.strip());
        }
        for (Object t : list) if (t != null && !String.valueOf(t).isBlank()) merged.add(String.valueOf(t).strip());
        item.setMatchedTags(String.join(",", merged));
        ContentItem saved = contentRepo.save(item);

        record(contentId, AgentFeedback.ACTION_ACCEPT_TAG, null, String.join(",", list.stream().map(String::valueOf).toList()));
        return saved;
    }

    /** 清空提取结果 */
    public void clearExtract(Long contentId) {
        ContentItem item = contentRepo.findById(contentId)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + contentId));
        if (item.getExtract() == null) return;
        item.setExtract(null);
        contentRepo.save(item);
        record(contentId, AgentFeedback.ACTION_DELETE_EXTRACT, null, null);
    }

    /** 记录一条主人反馈（忽略 / 手改摘要等），并检查是否该总结技能了 */
    public AgentFeedback record(Long contentId, String action, String skillCodes, String finalValue) {
        AgentFeedback f = new AgentFeedback();
        f.setContentId(contentId);
        f.setCatId(catOfContent(contentId));
        f.setAction(action);
        f.setSkillCodes(skillCodes != null ? skillCodes : skillCodesOf(contentId));
        f.setFinalValue(finalValue);
        AgentFeedback saved = feedbackRepo.save(f);
        log.info("[喵喵] 记录反馈: 内容 {} 动作 {}", contentId, action);
        maybeSummarizeSkills();
        return saved;
    }

    public List<AgentFeedback> recentFeedback() {
        return feedbackRepo.findTop50ByOrderByCreatedAtDesc();
    }

    // ================= 喵喵自动生成技能 =================

    private void maybeSummarizeSkills() {
        /* 反馈按猫分组，各自攒够阈值后由这只猫消化总结 */
        Map<Long, List<AgentFeedback>> byCat = new HashMap<>();
        for (AgentFeedback f : feedbackRepo.findByConsumedFalseOrderByCreatedAtAsc()) {
            byCat.computeIfAbsent(f.getCatId() == null ? 0L : f.getCatId(), k -> new ArrayList<>()).add(f);
        }
        for (List<AgentFeedback> pending : byCat.values()) {
            if (pending.size() < SKILL_SUMMARY_THRESHOLD) continue;
            executor.submit(() -> {
                try {
                    summarizeSkills(pending, pending.get(0).getCatId());
                } catch (Exception e) {
                    log.error("[喵喵] 总结技能失败: {}", e.getMessage(), e);
                }
            });
        }
    }

    /** 喵喵消化反馈，总结出一条新的 PREFERENCE 技能（归属到这只猫） */
    public Map<String, Object> summarizeSkills(List<AgentFeedback> pending, Long catId) throws Exception {
        Map<String, Integer> byAction = new LinkedHashMap<>();
        for (AgentFeedback f : pending) byAction.merge(f.getAction(), 1, Integer::sum);

        String content;
        String name;
        if (miaomiao.liveMode(catId)) {
            StringBuilder sb = new StringBuilder("主人最近的处理反馈（每行一条：动作 | 喵喵当时的建议 | 主人最终采用）：\n");
            for (AgentFeedback f : pending) {
                sb.append("- ").append(f.getAction())
                        .append(" | ").append(f.getSuggestion() == null ? "无" : f.getSuggestion())
                        .append(" | ").append(f.getFinalValue() == null ? "无" : f.getFinalValue())
                        .append("\n");
            }
            sb.append("\n动作说明：accept_apply=采纳归类建议、accept_tag=采纳标签、edit_summary=手改摘要、ignore=忽略、delete_extract=清空结果。\n");
            sb.append("请总结出一条可执行的「主人喜好」技能，严格输出 JSON（无其它文字）：{\"name\":\"技能名（10字内）\",\"description\":\"一句话描述\",\"content\":\"具体可执行的喜好规则，让下次提取时直接照做\"}");
            String raw = miaomiao.agent(catId).chat("你是喵喵，帮主人整理知识体系的猫。", sb.toString());
            String json = raw.strip();
            int b = json.indexOf('{'), e = json.lastIndexOf('}');
            if (b >= 0 && e > b) json = json.substring(b, e + 1);
            JsonNode n = mapper.readTree(json);
            name = n.path("name").asText("主人口味·喵喵观察");
            content = n.path("content").asText("");
            if (content.isBlank()) throw new IllegalStateException("模型没有总结出技能内容");
        } else {
            // 模拟版：按规则从反馈里归纳
            int accepted = byAction.getOrDefault(AgentFeedback.ACTION_ACCEPT_APPLY, 0)
                    + byAction.getOrDefault(AgentFeedback.ACTION_ACCEPT_TAG, 0);
            int ignored = byAction.getOrDefault(AgentFeedback.ACTION_IGNORE, 0)
                    + byAction.getOrDefault(AgentFeedback.ACTION_DELETE_EXTRACT, 0);
            var tagCount = new HashMap<String, Integer>();
            for (AgentFeedback f : pending) {
                if (f.getFinalValue() == null) continue;
                for (String p : f.getFinalValue().split("[,，、]")) {
                    String t = p.strip();
                    if (t.length() >= 2 && t.length() <= 8) tagCount.merge(t, 1, Integer::sum);
                }
            }
            String topTags = tagCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .reduce((a, b) -> a + "、" + b).orElse("");
            name = "主人口味·喵喵观察 No." + (skillRepo.findBySourceOrderByUpdatedAtDesc(AgentSkill.SOURCE_MIAOMIAO).stream()
                    .filter(s -> catId.equals(s.getCatId())).count() + 1);
            content = "（喵喵从 " + pending.size() + " 条反馈中学到的）主人对归类/标签建议的采纳 " + accepted
                    + " 次、忽略 " + ignored + " 次。"
                    + (topTags.isEmpty() ? "" : "主人更常采纳涉及「" + topTags + "」的建议，遇到相关内容时标签和归类优先往这些方向靠。")
                    + "提取时保持简洁直接的表达。";
        }

        AgentSkill s = new AgentSkill();
        s.setCode("miaomiao.pref." + System.currentTimeMillis());
        s.setCatId(catId);
        s.setKind(AgentSkill.KIND_PREFERENCE);
        s.setName(name);
        s.setDescription("喵喵从 " + pending.size() + " 条反馈中自动总结");
        s.setContent(content);
        s.setSource(AgentSkill.SOURCE_MIAOMIAO);
        AgentSkill saved = skillRepo.save(s);
        pending.forEach(f -> {
            f.setConsumed(true);
            feedbackRepo.save(f);
        });
        log.info("[喵喵] 反馈消化完毕，自动生成技能「{}」（{} 条反馈）", saved.getName(), pending.size());
        return Map.of("skillId", saved.getId(), "name", saved.getName(), "feedback", pending.size());
    }

    // ================= 技能管理 =================

    public List<AgentSkill> skills(Long catId) {
        return skillRepo.findByCatIdOrderByKindAscIdAsc(catId);
    }

    public AgentSkill saveSkill(AgentSkill in) {
        if (in.getCode() == null || in.getCode().isBlank()) {
            in.setCode("user." + System.currentTimeMillis());
        }
        if (in.getKind() == null) in.setKind(AgentSkill.KIND_PREFERENCE);
        if (in.getSource() == null) in.setSource(AgentSkill.SOURCE_USER);
        if (in.getCatId() == null) in.setCatId(firstCatId());
        in.setUpdatedAt(LocalDateTime.now());
        return skillRepo.save(in);
    }

    public void deleteSkill(Long id) {
        skillRepo.deleteById(id);
    }

    public AgentSkill toggleSkill(Long id) {
        AgentSkill s = skillRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在: " + id));
        s.setEnabled(!s.isEnabled());
        s.setUpdatedAt(LocalDateTime.now());
        return skillRepo.save(s);
    }

    // ================= 工具 =================

    public Map<String, Object> parseExtract(ContentItem item) {
        if (item.getExtract() == null || item.getExtract().isBlank()) return Map.of();
        try {
            return mapper.readValue(item.getExtract(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String skillCodesOf(Long contentId) {
        return contentRepo.findById(contentId)
                .map(this::parseExtract)
                .map(ex -> String.valueOf(ex.getOrDefault("skillCodes", "")))
                .orElse(null);
    }

    private void markApplied(ContentItem item) {
        Map<String, Object> ex = new LinkedHashMap<>(parseExtract(item));
        if (!ex.isEmpty()) {
            ex.put("applied", true);
            try {
                item.setExtract(mapper.writeValueAsString(ex));
                contentRepo.save(item);
            } catch (Exception ignored) { }
        }
    }

    private Object prop(Map<String, Object> ex, String key, String prop) {
        Object v = propOr(ex, key, prop, null);
        return v instanceof Map<?, ?> m && m.isEmpty() ? null : v;
    }

    @SuppressWarnings("unchecked")
    private Object propOr(Map<String, Object> ex, String key, String prop, Object dft) {
        Object sub = ex.get(key);
        if (!(sub instanceof Map)) return dft;
        Object v = ((Map<String, Object>) sub).get(prop);
        return v == null ? dft : v;
    }

    private String skillLabel(AgentSkill s) {
        return AgentSkill.KIND_EXTRACT.equals(s.getKind()) ? "规矩" : "喜好";
    }
}
