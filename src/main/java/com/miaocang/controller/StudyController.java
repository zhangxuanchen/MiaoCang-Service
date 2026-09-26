package com.miaocang.controller;

import com.miaocang.entity.Cat;
import com.miaocang.entity.StudyTopic;
import com.miaocang.entity.StudyTopicItem;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.StudyTopicItemRepository;
import com.miaocang.repository.StudyTopicRepository;
import com.miaocang.service.LibrarySyncService;
import com.miaocang.service.StudySearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 我的学习：学习主题 CRUD + 主题页聚合（收录 + 关键词检索） + 全库检索/重建索引；主题落 md 进 git（类似 skill） */
@RestController
public class StudyController {

    private static final Logger log = LoggerFactory.getLogger(StudyController.class);

    private final StudyTopicRepository topicRepo;
    private final StudyTopicItemRepository itemRepo;
    private final CatRepository catRepo;
    private final StudySearchService searchService;
    private final LibrarySyncService librarySync;

    public StudyController(StudyTopicRepository topicRepo, StudyTopicItemRepository itemRepo,
                           CatRepository catRepo, StudySearchService searchService,
                           LibrarySyncService librarySync) {
        this.topicRepo = topicRepo;
        this.itemRepo = itemRepo;
        this.catRepo = catRepo;
        this.searchService = searchService;
        this.librarySync = librarySync;
    }

    // ================= 主题 CRUD =================

    /** 侧栏主题列表（带收录数） */
    @GetMapping("/api/cats/{catId}/study/topics")
    public List<Map<String, Object>> topics(@PathVariable Long catId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudyTopic t : topicRepo.findByCatIdOrderBySortAscIdAsc(catId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("icon", t.getIcon());
            m.put("description", t.getDescription());
            m.put("keywords", t.getKeywords());
            m.put("itemCount", itemRepo.findByTopicIdOrderByAddedAtDescIdDesc(t.getId()).size());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api/cats/{catId}/study/topics")
    public Map<String, Object> create(@PathVariable Long catId, @RequestBody Map<String, String> body) {
        if (!catRepo.existsById(catId)) throw new IllegalArgumentException("猫不存在");
        String name = (body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("主题名不能为空");
        StudyTopic t = new StudyTopic();
        t.setCatId(catId);
        t.setName(name);
        t.setIcon(body.getOrDefault("icon", "🧠"));
        t.setDescription(body.getOrDefault("description", ""));
        t.setKeywords(body.getOrDefault("keywords", ""));
        t.setSort((int) topicRepo.findByCatIdOrderBySortAscIdAsc(catId).size());
        topicRepo.save(t);
        writeTopicMd(t, "新建");
        return Map.of("id", t.getId());
    }

    /** 编辑主题（名称/图标/简介/关键词），用于主题页胶囊增删后保存 */
    @PutMapping("/api/study/topics/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        StudyTopic t = topicRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("主题不存在"));
        String oldFile = topicFile(t);
        String ws = catRepo.findById(t.getCatId()).map(Cat::getWorkspacePath).orElse(null);
        if (body.containsKey("name") && !body.get("name").isBlank()) t.setName(body.get("name").trim());
        if (body.containsKey("icon") && !body.get("icon").isBlank()) t.setIcon(body.get("icon"));
        if (body.containsKey("description")) t.setDescription(body.get("description"));
        if (body.containsKey("keywords")) t.setKeywords(body.get("keywords"));
        topicRepo.save(t);
        /* 改名后文件名变化：删旧写新 */
        try {
            String newFile = topicFile(t);
            if (ws != null && !newFile.equals(oldFile)) librarySync.deleteArtifact(ws, oldFile);
        } catch (IOException ignored) { }
        writeTopicMd(t, "更新");
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/study/topics/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        StudyTopic t = topicRepo.findById(id).orElse(null);
        itemRepo.deleteByTopicId(id);
        topicRepo.deleteById(id);
        if (t != null) {
            String ws = catRepo.findById(t.getCatId()).map(Cat::getWorkspacePath).orElse(null);
            try {
                if (ws != null) {
                    librarySync.deleteArtifact(ws, topicFile(t));
                    librarySync.commit("学习主题: 删除 " + t.getName());
                } else {
                    log.warn("删除主题 md 失败: 找不到猫工作区 catId={}", t.getCatId());
                }
            } catch (IOException e) {
                log.warn("删除主题 md 失败: {}", e.toString());
            }
        }
        return Map.of("ok", true);
    }

    // ================= 主题页 =================

    /** 主题页聚合：主题信息 + 已收录条目（钉选） + 关键词检索命中（去重合并） */
    @GetMapping("/api/study/topics/{id}/page")
    public Map<String, Object> page(@PathVariable Long id,
                                    @RequestParam(defaultValue = "20") int limit) {
        StudyTopic t = topicRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("主题不存在"));
        List<StudyTopicItem> pinned = itemRepo.findByTopicIdOrderByAddedAtDescIdDesc(id);
        /* 关键词逐个检索，按 key 去重合并（相关度最高的先出现） */
        List<Map<String, Object>> hits = new ArrayList<>();
        if (t.getKeywords() != null && !t.getKeywords().isBlank()) {
            Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
            for (String kw : t.getKeywords().split("[,，]")) {
                if (kw.isBlank()) continue;
                for (Map<String, Object> h : searchService.search(t.getCatId(), kw.trim(), limit)) {
                    seen.putIfAbsent(String.valueOf(h.get("key")), h);
                    if (seen.size() >= limit) break;
                }
                if (seen.size() >= limit) break;
            }
            hits.addAll(seen.values());
        }
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", t.getId());
        topic.put("catId", t.getCatId());
        topic.put("name", t.getName());
        topic.put("icon", t.getIcon());
        topic.put("description", t.getDescription());
        topic.put("keywords", t.getKeywords());
        return Map.of("topic", topic, "pinned", pinned, "hits", hits, "indexed", searchService.isReady());
    }

    /** 收录条目（知识点详情/检索命中的「加入学习主题」） */
    @PostMapping("/api/study/topics/{id}/items")
    public Map<String, Object> addItem(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (!topicRepo.existsById(id)) throw new IllegalArgumentException("主题不存在");
        String type = body.getOrDefault("itemType", "point");
        String key = body.getOrDefault("itemKey", "");
        if (key.isBlank()) throw new IllegalArgumentException("条目 key 不能为空");
        if (!itemRepo.existsByTopicIdAndItemTypeAndItemKey(id, type, key)) {
            StudyTopicItem it = new StudyTopicItem();
            it.setTopicId(id);
            it.setItemType(type);
            it.setItemKey(key);
            it.setTitle(body.getOrDefault("title", key));
            it.setPinnedNote(body.get("note"));
            itemRepo.save(it);
            topicRepo.findById(id).ifPresent(t -> writeTopicMd(t, "收录"));
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/study/topics/{id}/items/{itemType}/{itemKey}")
    public Map<String, Object> removeItem(@PathVariable Long id, @PathVariable String itemType,
                                          @PathVariable String itemKey) {
        itemRepo.deleteOne(id, itemType, itemKey);
        topicRepo.findById(id).ifPresent(t -> writeTopicMd(t, "移除收录"));
        return Map.of("ok", true);
    }

    // ================= 主题 md（类似 skill：frontmatter 简介 + 已收录清单，落 git） =================

    /** 主题文件名：study/T<id>-<名称>.md（id 稳定防重名冲突；改名后删旧写新） */
    private String topicFile(StudyTopic t) {
        String safe = t.getName().replaceAll("[\\\\/:*?\"<>|]", "").strip();
        return "study/T" + t.getId() + "-" + safe + ".md";
    }

    /** 主题 md 全量重写：frontmatter(name/icon/description/keywords) + 简介 + 已收录清单，并提交 git */
    private void writeTopicMd(StudyTopic t, String action) {
        try {
            Cat cat = catRepo.findById(t.getCatId()).orElse(null);
            if (cat == null) return;
            List<StudyTopicItem> items = itemRepo.findByTopicIdOrderByAddedAtDescIdDesc(t.getId());
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(t.getName()).append('\n');
            sb.append("icon: ").append(t.getIcon() == null ? "🧠" : t.getIcon()).append('\n');
            sb.append("description: ").append(t.getDescription() == null ? "" : t.getDescription().replace("\n", " ")).append('\n');
            sb.append("keywords: ").append(t.getKeywords() == null ? "" : t.getKeywords()).append('\n');
            sb.append("---\n\n");
            sb.append("# ").append(t.getIcon() == null ? "🧠" : t.getIcon()).append(' ').append(t.getName()).append("\n\n");
            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                sb.append("> ").append(t.getDescription().strip()).append("\n\n");
            }
            sb.append("**检索关键词**：");
            if (t.getKeywords() != null && !t.getKeywords().isBlank()) {
                for (String kw : t.getKeywords().split("[,，]")) {
                    if (!kw.isBlank()) sb.append("`").append(kw.trim()).append("` ");
                }
            } else {
                sb.append("（未设置）");
            }
            sb.append("\n\n## 已收录（").append(items.size()).append("）\n\n");
            if (items.isEmpty()) {
                sb.append("*还没有收录条目——从知识点详情或检索结果点「＋ 收录」加入。*\n");
            } else {
                for (StudyTopicItem it : items) {
                    sb.append("- `[").append(it.getItemType()).append("]` ").append(it.getItemKey())
                      .append(" · ").append(it.getTitle()).append('\n');
                }
            }
            librarySync.writeArtifact(cat.getWorkspacePath(), topicFile(t), sb.toString());
            librarySync.commit("学习主题: " + action + " " + t.getName());
        } catch (IOException ignored) { }
    }

    // ================= 全库检索 =================

    /** 顶栏全局搜索框 + 主题页 + 专属会话区共用；all=true 时跨喵搜当前用户全部领地（全库搜索） */
    @GetMapping("/api/cats/{catId}/study/search")
    public List<Map<String, Object>> search(@PathVariable Long catId,
                                            @RequestParam String q,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(defaultValue = "false") boolean all) {
        if (all) {
            java.util.Set<Long> mine = catRepo.findByUserIdOrderByOrderIndexAscIdAsc(com.miaocang.auth.CurrentUser.get().getId())
                    .stream().map(Cat::getId).collect(java.util.stream.Collectors.toSet());
            return searchService.search(mine, q, limit);
        }
        return searchService.search(catId, q, limit);
    }

    /** 手动全量重建索引（归纳完成后服务端会自动增量刷新，一般无需手动） */
    @PostMapping("/api/search/reindex")
    public Map<String, Object> reindex() throws Exception {
        return searchService.reindexAll();
    }
}
