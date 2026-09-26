package com.miaocang.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 书库同步服务：服务端与喵藏客户端（macOS 桌面猫）通过同一套书库文件格式联动。
 *
 * 文件结构与客户端 fetcher.py 完全一致：
 * - <书库>/<分类>/<yyyy-MM-dd>_<safe_filename>.md（一级分类目录 = 书籍类型名）
 * - front-matter: title(json串)/url/saved/category/kind/summary[/source]
 * - .catalog.json：{"items":[...]} 按 saved 倒序
 * - INDEX.md：总目录表格
 * - git 仓库维护同步（.gitignore 排除再生文件，内容 md 才是唯一真相）
 *
 * 同步协议：
 * - 导出：已归档条目落盘（客户端拉取即可收编，rescan 不移动文件）
 * - 导入：rescan 式扫描 —— 磁盘有、库没有 → 补录入库；库有、磁盘无 → 剔除
 * - 客户端可直接 push 到本仓库（receive.denyCurrentBranch=updateInstead 自动更新工作区）
 */
@Service
public class LibrarySyncService {

    private static final Logger log = LoggerFactory.getLogger(LibrarySyncService.class);

    private static final DateTimeFormatter SAVED_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String GITIGNORE = "INDEX.md\n.catalog.json\n.DS_Store\n*.tmp\nThumbs.db\n";

    private final ContentItemRepository contentRepo;
    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final ObjectMapper objectMapper;
    private final ClassifyService classifyService;
    private final CatRepository catRepo;

    @Value("${miaocang.library.dir:./data/library}")
    private String libraryDir;

    @Value("${miaocang.library.git-remote:}")
    private String gitRemote;

    /** 与引力引擎同一阈值：导入复核时判断"引力波是否有明确意见" */
    @Value("${miaocang.gravity.min-score:2.0}")
    private double minScore;

    public LibrarySyncService(ContentItemRepository contentRepo, BookTypeRepository typeRepo,
                              BookRepository bookRepo, ObjectMapper objectMapper,
                              ClassifyService classifyService, CatRepository catRepo) {
        this.contentRepo = contentRepo;
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.objectMapper = objectMapper;
        this.classifyService = classifyService;
        this.catRepo = catRepo;
    }

    // ================= 路径与基础 =================

    public Path root() {
        return Paths.get(libraryDir).toAbsolutePath().normalize();
    }

    /** 客户端 safe_filename 的 Java 移植：去非法字符、去首尾点与空白、截断 50、空则 untitled */
    public static String safeFilename(String name, int maxLen) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "");
        s = stripBoth(s, '.').strip();
        if (s.isEmpty()) return "untitled";
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        s = stripBoth(s, '.').strip();
        return s.isEmpty() ? "untitled" : s;
    }

    private static String stripBoth(String s, char c) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (end > start && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }

    /** 条目在文件树中所属的一级分类目录名（= 书籍类型名） */
    public String resolveCategoryName(ContentItem item) {
        if (item.getBookId() != null) {
            Book book = bookRepo.findById(item.getBookId()).orElse(null);
            if (book != null) {
                BookType type = typeRepo.findById(book.getTypeId()).orElse(null);
                if (type != null) return type.getName();
            }
        }
        return "收集箱";
    }

    /**
     * 条目在书库中的落盘路径：/<workspacePath>/origin/<safe(类型)>/<yyyy-MM-dd>_<safe(标题)>.md，冲突加 _n 后缀。
     * 原始文档统一归入工作区的 origin/ 专属文件夹（与 points/、reading/ 平级），分类目录是其子结构。
     * 能定位到归属喵（catId → 书 → 分类 → 猫）则带工作区前缀（多用户各喵隔离）；
     * 定位不到保持根级旧路径兜底（历史数据 / 未归属条目）。
     */
    private String computeRelPath(String category, ContentItem item) {
        String prefix = "";
        String ws = resolveOwnerWorkspace(item);
        if (ws != null && !ws.isBlank()) prefix = ws + "/";
        String dir = safeFilename(category, 50);
        String date = item.getCreatedAt().format(DATE_FMT);
        String base = safeFilename(item.getTitle(), 50);
        String rel = prefix + "origin/" + dir + "/" + date + "_" + base + ".md";
        int n = 1;
        while (isTaken(rel, item.getId())) {
            n++;
            rel = prefix + "origin/" + dir + "/" + date + "_" + base + "_" + n + ".md";
        }
        return rel;
    }

    /** 解析条目的归属喵工作区：item.catId 优先，缺则按 书 → 分类 → 猫 链推导；均空返回 null */
    private String resolveOwnerWorkspace(ContentItem item) {
        Long catId = item.getCatId();
        if (catId == null && item.getBookId() != null) {
            catId = bookRepo.findById(item.getBookId())
                    .map(b -> typeRepo.findById(b.getTypeId()).map(BookType::getCatId).orElse(null))
                    .orElse(null);
        }
        if (catId == null) return null;
        return catRepo.findById(catId)
                .map(c -> c.getWorkspacePath() == null || c.getWorkspacePath().isBlank() ? null : c.getWorkspacePath())
                .orElse(null);
    }

    private boolean isTaken(String rel, Long selfId) {
        if (Files.exists(root().resolve(rel))) return true;
        return contentRepo.findByLibraryPath(rel).filter(it -> !it.getId().equals(selfId)).isPresent();
    }

    // ================= 条目落盘 / 搬移 / 删除 =================

    /** 解析猫工作区相对路径（书库内路径拼接的唯一入口） */
    public Path resolveWorkspace(String workspacePath) {
        return root().resolve(workspacePath).normalize();
    }

    /** 书库内任意文件写入的唯一通道：解析 workspace 相对路径，建父目录后 UTF-8 落盘（越界即拒） */
    public Path writeArtifact(String workspacePath, String relativePath, String content) throws IOException {
        Path p = resolveWorkspace(workspacePath).resolve(relativePath).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("路径越界: " + relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /** 二进制产物写入（剪藏图片等）：与 {@link #writeArtifact} 同一套越界防护，按字节落盘 */
    public Path writeArtifactBinary(String workspacePath, String relativePath, byte[] data) throws IOException {
        Path p = resolveWorkspace(workspacePath).resolve(relativePath).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("路径越界: " + relativePath);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        return p;
    }

    /** 剪藏图片资产的合法扩展名（与前端 file-b64 预览的 MIME 映射一致） */
    private static final Set<String> ASSET_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");

    /**
     * 客户端互通的图片资产路径：必须含 _images 段（剪藏工具的图片目录约定），如
     * {@code _images/<文章目录>/x.png}、{@code <分类>/_images/x.png}、{@code origin/<分类>/_images/x.png}。
     * 只放行 _images 这一种 _ 前缀段，扩展名限图片——md 正文路径仍由 {@link #isLegalContentPath} 把关。
     */
    public boolean isLegalAssetPath(String rel) {
        if (rel == null || rel.isBlank() || rel.contains("..") || rel.contains("\\")) return false;
        String[] parts = rel.split("/");
        if (parts.length < 2) return false;
        boolean seenImages = false;
        for (String p : parts) {
            if (p.isBlank() || p.startsWith(".")) return false;
            if ("_images".equals(p)) {
                if (seenImages) return false;
                seenImages = true;
            } else if (p.startsWith("_")) {
                return false;
            }
        }
        if (!seenImages) return false;
        String name = parts[parts.length - 1];
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ASSET_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    /** 书库内任意文件删除（存在才删），路径同样越界即拒 */
    public void deleteArtifact(String workspacePath, String relativePath) throws IOException {
        Path p = resolveWorkspace(workspacePath).resolve(relativePath).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("路径越界: " + relativePath);
        Files.deleteIfExists(p);
    }

    /** 书库内任意文件追加（读旧拼新），文件不存在则创建 */
    public void appendArtifact(String workspacePath, String relativePath, String content) throws IOException {
        Path p = resolveWorkspace(workspacePath).resolve(relativePath).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("路径越界: " + relativePath);
        Files.createDirectories(p.getParent());
        String old = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        Files.writeString(p, old + content, StandardCharsets.UTF_8);
    }

    /** 把已归档条目写进书库（已有文件且在库中的条目保持原位不动 —— 尊重客户端摆放） */
    public void writeContent(ContentItem item) {
        if (ContentItem.STATUS_PENDING.equals(item.getStatus())) {
            removeContent(item);
            return;
        }
        if (item.getLibraryPath() != null && Files.exists(root().resolve(item.getLibraryPath()))) {
            return;
        }
        String category = resolveCategoryName(item);
        String rel = computeRelPath(category, item);
        try {
            Path p = root().resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, buildMarkdown(item, category), StandardCharsets.UTF_8);
            item.setLibraryPath(rel);
            contentRepo.save(item);
            log.info("[书库] 落盘 {} ({})", rel, item.getTitle());
        } catch (IOException e) {
            log.error("[书库] 写入失败 {}: {}", rel, e.getMessage());
        }
    }

    /** 条目归属变化：删旧文件、重算路径写新文件 */
    public void relocateContent(ContentItem item) {
        if (item.getLibraryPath() != null) {
            deleteQuietly(root().resolve(item.getLibraryPath()));
            item.setLibraryPath(null);
        }
        writeContent(item);
    }

    /** 条目移出书库（回收集箱 / 删除前调用）：删文件并清空路径 */
    public void removeContent(ContentItem item) {
        if (item.getLibraryPath() != null) {
            deleteQuietly(root().resolve(item.getLibraryPath()));
            item.setLibraryPath(null);
            contentRepo.save(item);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[书库] 删除失败 {}: {}", p, e.getMessage());
        }
    }

    /** 生成与客户端格式一致的 markdown（front-matter + 正文） */
    private String buildMarkdown(ContentItem item, String category) {
        boolean url = ContentItem.TYPE_URL.equals(item.getContentType());
        String link = url && item.getSource() != null ? item.getSource() : "";

        List<String> front = new ArrayList<>();
        front.add("---");
        front.add("title: " + jsonString(item.getTitle()));
        front.add("url: " + link);
        front.add("saved: " + item.getCreatedAt().format(SAVED_FMT));
        front.add("category: " + category);
        front.add(url ? "kind: full" : "kind: note");
        front.add("summary: " + jsonString(item.getSummary() == null ? "" : item.getSummary()));
        if (!url) front.add("source: manual");
        front.add("---");
        front.add("");
        front.add("");

        StringBuilder sb = new StringBuilder(String.join("\n", front));
        sb.append(item.getRawText() == null ? "" : item.getRawText().strip()).append("\n");
        if (url && !link.isBlank()) {
            sb.append("\n---\n\n原文：<").append(link).append(">\n");
        }
        return sb.toString();
    }

    private String jsonString(String s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
        }
    }

    // ================= 索引再生（.catalog.json + INDEX.md） =================

    /** 按客户端格式全量重建 .catalog.json 与 INDEX.md */
    public void rewriteCatalogAndIndex() {
        List<Map<String, Object>> items = new ArrayList<>();
        // 收集箱（PENDING）条目即使已落盘也不入总目录——未整理的东西不假装已整理
        for (ContentItem it : contentRepo.findByLibraryPathIsNotNullAndStatus(ContentItem.STATUS_CLASSIFIED)) {
            items.add(toCatalogItem(it));
        }
        items.sort(Comparator.comparing(m -> (String) m.get("saved"), Comparator.reverseOrder()));

        try {
            Files.createDirectories(root());
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("items", items);
            Files.writeString(root().resolve(".catalog.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(catalog), StandardCharsets.UTF_8);
            Files.writeString(root().resolve("INDEX.md"), buildIndex(items), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[书库] 索引重建失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> toCatalogItem(ContentItem it) {
        boolean url = ContentItem.TYPE_URL.equals(it.getContentType());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url ? it.getSource() : "");
        m.put("title", it.getTitle());
        m.put("category", resolveCategoryName(it));
        m.put("file", it.getLibraryPath());
        m.put("saved", it.getCreatedAt().format(SAVED_FMT));
        m.put("kind", url ? "full" : "note");
        m.put("summary", it.getSummary() == null ? "" : it.getSummary());
        if (!url) m.put("source", "manual");
        return m;
    }

    /** 客户端 update_index 的 Java 复刻 */
    private String buildIndex(List<Map<String, Object>> items) {
        List<String> lines = new ArrayList<>();
        lines.add("# 喵藏 · 总目录");
        lines.add("");
        lines.add("> 由喵藏 自动维护，共收录 **" + items.size() + "** 篇 · 最近更新 "
                + LocalDateTime.now().format(SAVED_FMT));
        lines.add("");
        lines.add("| 时间 | 标题 | 分类 | 摘要 | 原文 |");
        lines.add("|---|---|---|---|---|");
        for (Map<String, Object> it : items) {
            String stamp = String.valueOf(it.get("saved"));
            String title = str(it.get("title")).replace("|", "/");
            String cat = str(it.get("category"));
            String local = str(it.get("file"));
            String link = str(it.get("url"));
            boolean note = "note".equals(it.get("kind"));
            String summ = str(it.get("summary")).replaceAll("\\s+", " ").replace("|", "/");
            if (summ.isEmpty()) summ = note ? "—" : "（全文）";
            if (summ.length() > 80) summ = summ.substring(0, 80).strip() + "…";
            String icon = note ? "✏️" : "🔗";
            String urlCell = link.isBlank() ? "—" : "[原文](" + link + ")";
            lines.add("| " + stamp + " | [" + title + "](" + local + ") | " + cat + " | " + icon + " " + summ
                    + " | " + urlCell + " |");
        }
        lines.add("");
        return String.join("\n", lines);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ================= 导入（rescan 式反向同步） =================

    /** 与客户端 rescan 同语义：磁盘有、库没有 → 补录；库有、磁盘无 → 剔除。返回统计。 */
    @Transactional
    public Map<String, Object> importFromLibrary() {
        Map<String, Object> result = doImport();
        rewriteCatalogAndIndex();
        return result;
    }

    private Map<String, Object> doImport() {
        ensureRepo();
        Map<String, Path> onDisk = scanDisk();
        log.info("[书库] 磁盘扫描到 {} 个 md 文件", onDisk.size());

        // 1) 库里有记录、文件已不在 → 剔除（与客户端 rescan 一致；磁盘仍在的旧根级文件兜底不误删）
        List<String> removed = new ArrayList<>();
        for (ContentItem it : contentRepo.findByLibraryPathIsNotNull()) {
            if (!onDisk.containsKey(it.getLibraryPath())
                    && !Files.exists(root().resolve(it.getLibraryPath()))) {
                removed.add(it.getTitle());
                log.info("[书库] 文件已消失，剔除条目 {} ({})", it.getLibraryPath(), it.getTitle());
                contentRepo.delete(it);
            }
        }

        // 2) 磁盘上有、库里没有 → 补录
        Set<String> known = new HashSet<>();
        for (ContentItem it : contentRepo.findByLibraryPathIsNotNull()) {
            known.add(it.getLibraryPath());
        }
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Path> e : onDisk.entrySet()) {
            if (known.contains(e.getKey())) continue;
            try {
                ContentItem item = importOne(e.getKey(), e.getValue());
                added.add(item.getTitle());
            } catch (Exception ex) {
                log.warn("[书库] 导入失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }

        String note = "共 " + onDisk.size() + " 篇";
        if (!added.isEmpty()) note += " · 补录 " + added.size();
        if (!removed.isEmpty()) note += " · 剔除 " + removed.size();
        if (added.isEmpty() && removed.isEmpty()) note += " · 没有变化";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", onDisk.size());
        m.put("added", added);
        m.put("removed", removed);
        m.put("note", note);
        return m;
    }

    /** 扫描磁盘 md（跳过隐藏目录与 INDEX.md，与客户端 rescan 一致） */
    private Map<String, Path> scanDisk() {
        Map<String, Path> onDisk = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(root())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                    .forEach(p -> {
                        String rel = root().relativize(p).toString().replace('\\', '/');
                        boolean hidden = false;
                        for (String part : rel.split("/")) {
                            if (part.startsWith(".")) {
                                hidden = true;
                                break;
                            }
                        }
                        if (!hidden) onDisk.put(rel, p);
                    });
        } catch (IOException e) {
            log.warn("[书库] 磁盘扫描失败: {}", e.getMessage());
        }
        return onDisk;
    }

    /** 把一个客户端 md 补录进数据库（全库版，无归属喵；单喵版见 importOne(Cat,...)） */
    private ContentItem importOne(String rel, Path path) throws IOException {
        return importOne(null, rel, path);
    }

    /**
     * 把一个客户端 md 补录进数据库：category 建类型（缺则新建），挂默认书根。
     * cat 非空 = 单喵收编：libraryPath 带 workspace 前缀、类型在该喵视角内匹配、条目直接归属该喵。
     */
    private ContentItem importOne(Cat cat, String wsRel, Path path) throws IOException {
        String fullRel = cat == null ? wsRel : cat.getWorkspacePath() + "/" + wsRel;
        String text = Files.readString(path, StandardCharsets.UTF_8);
        ParsedMd parsed = parseMd(text);
        Map<String, String> fm = parsed.fm();
        String body = parsed.body();

        String title = nonBlank(fm.get("title"));
        if (title.isEmpty()) title = deriveTitleFromStem(path.getFileName().toString());
        if (title.isEmpty()) title = "未命名内容";
        // title 列 VARCHAR(255)：超长 insert 会 22001 失败并污染整个事务（后续 flush 全报 null identifier），落库前必须截断
        title = abbreviate(title, 200);

        String url = nonBlank(fm.get("url"));
        String category = nonBlank(fm.get("category"));
        if (category.isEmpty()) {
            int slash = wsRel.indexOf('/');
            category = slash > 0 ? wsRel.substring(0, slash) : "未分类";
        }
        category = abbreviate(category, 100);

        ContentItem item = new ContentItem();
        item.setTitle(title);
        item.setContentType(url.isEmpty() ? ContentItem.TYPE_TEXT : ContentItem.TYPE_URL);
        item.setSource(url.isEmpty() ? null : abbreviate(url, 1000));
        item.setRawText(abbreviate(body, 20000));
        String summ = nonBlank(fm.get("summary"));
        item.setSummary(summ.isEmpty() ? abbreviate(body, 200) : abbreviate(summ, 1000));
        item.setCreatedAt(parseSaved(fm.get("saved"), path));
        item.setLibraryPath(fullRel);

        // ── 两层分类：Client 第一层（front-matter / 一级目录）+ Service 引力波第二层复核 ──
        // "收件箱"是客户端兜底目录，视为第一层未分类 → 进服务端收集箱等建议
        if ("收件箱".equals(category)) {
            classifyService.scoreOnly(item);
            item.setStatus(ContentItem.STATUS_PENDING);
            item.setAutoClassified(false);
            if (cat != null) item.setCatId(cat.getId());
            log.info("[书库] 补录 {} -> 收集箱（客户端兜底，等第二层建议）· {}", fullRel, title);
            return contentRepo.save(item);
        }

        BookType clientType = ensureType(category, cat);
        List<GravityEngine.TypeScore> scores = classifyService.scoreOnly(item);
        boolean confident = !scores.isEmpty() && scores.get(0).score() >= minScore;

        if (confident && scores.get(0).typeId().equals(clientType.getId())) {
            // 两层一致：直接归档，挂书 + 目录规则匹配二级章节
            classifyService.assignToType(item, clientType, scores.get(0));
            item.setStatus(ContentItem.STATUS_CLASSIFIED);
            item.setAutoClassified(true);
            log.info("[书库] 补录 {} -> 「{}」（两层分类一致）· {}", fullRel, category, title);
        } else if (confident) {
            // 分歧：进收集箱待确认，建议列表附加"客户端放置"选项供一键裁决
            item.setStatus(ContentItem.STATUS_PENDING);
            item.setAutoClassified(false);
            appendClientChoice(item, clientType);
            log.info("[书库] 补录 {} -> 收集箱（分类分歧：客户端「{}」 vs 引力「{}」）· {}",
                    fullRel, category, scores.get(0).name(), title);
        } else {
            // 引力波没有明确意见：尊重客户端第一层分类
            classifyService.assignToType(item, clientType, null);
            item.setStatus(ContentItem.STATUS_CLASSIFIED);
            item.setAutoClassified(false);
            log.info("[书库] 补录 {} -> 「{}」（尊重客户端分类）· {}", fullRel, category, title);
        }
        if (cat != null) item.setCatId(cat.getId());
        return contentRepo.save(item);
    }

    /**
     * 确保类型存在（不存在则创建自定义类型；不建默认书——挂书交给 assignToType）。
     * cat 非空 = 在「共享 preset + 该喵自有」范围内按名匹配（多用户隔离，不撞别人的同名专属分类），
     * 缺失则新建归属该喵的类型；cat 为空走旧的全局逻辑（历史全库导入兜底）。
     */
    private BookType ensureType(String name, Cat cat) {
        if (cat != null) {
            for (BookType t : typeRepo.findByName(name)) {
                if (t.isPreset()) return t;
                if (cat.getId().equals(t.getCatId())) return t;
            }
            BookType n = new BookType();
            n.setName(name);
            n.setIcon("📦");
            n.setColor("#8b8fa3");
            n.setDescription("喵藏客户端同步生成");
            n.setPreset(false);
            n.setCatId(cat.getId());
            log.info("[书库] 客户端同步新建分类「{}」· 喵 {}", name, cat.getName());
            return typeRepo.save(n);
        }
        BookType t = typeRepo.findByName(name).stream().findFirst().orElse(null);
        if (t != null) return t;
        BookType n = new BookType();
        n.setName(name);
        n.setIcon("📦");
        n.setColor("#8b8fa3");
        n.setDescription("喵藏客户端同步生成");
        n.setPreset(false);
        return typeRepo.save(n);
    }

    /** 在 typeScores 里追加"客户端放置"选项（score 为 null，确认时按兜底分处理） */
    private void appendClientChoice(ContentItem item, BookType type) {
        try {
            List<Map<String, Object>> scores = new ArrayList<>();
            if (item.getTypeScores() != null) {
                scores.addAll(objectMapper.readValue(item.getTypeScores(),
                        new TypeReference<List<Map<String, Object>>>() {}));
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("typeId", type.getId());
            choice.put("name", type.getName());
            choice.put("icon", type.getIcon());
            choice.put("color", type.getColor());
            choice.put("score", null);
            choice.put("matched", List.of());
            choice.put("client", true);
            scores.add(choice);
            item.setTypeScores(objectMapper.writeValueAsString(scores));
        } catch (Exception e) {
            log.warn("[书库] 附加客户端选项失败: {}", e.getMessage());
        }
    }

    // ================= 重建 =================

    /** 全量对齐：导入客户端文件 → 补写库里有但磁盘缺的条目 → 重建索引 */
    @Transactional
    public Map<String, Object> rebuildAll() {
        Map<String, Object> imported = doImport();

        List<String> rewritten = new ArrayList<>();
        for (ContentItem it : contentRepo.findByStatusOrderByCreatedAtDesc(ContentItem.STATUS_CLASSIFIED)) {
            if (it.getLibraryPath() == null || !Files.exists(root().resolve(it.getLibraryPath()))) {
                writeContent(it);
                rewritten.add(it.getTitle());
            }
        }
        rewriteCatalogAndIndex();

        Map<String, Object> m = new LinkedHashMap<>(imported);
        m.put("rewritten", rewritten);
        return m;
    }

    // ================= 客户端单喵文件互通（manifest / 单喵收编） =================

    /** 文件 sha256（小文件一次读入；书库 md 均为 KB 级） */
    public static String sha256Hex(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest(Files.readAllBytes(p))) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 服务端内部产物目录（知识点/读厚/学习卡）：不是客户端互通的内容文章，扫描与读写一律排除 */
    private static final Set<String> INTERNAL_TOP_DIRS = Set.of("points", "reading", "study");

    /** 工作区顶层保留目录：迁移原始文档到 origin/ 时跳过（服务端产物 + 记忆 + 状态存储） */
    private static final Set<String> WS_RESERVED_TOP_DIRS =
            Set.of("origin", "points", "reading", "study", "miaomiao", ".context", ".archive", ".workbench");

    /**
     * 客户端 path 归一化：两级 <分类>/<文件>.md → origin/<分类>/<文件>.md。
     * 客户端同步的原始文档统一住工作区 origin/（与 points/、reading/ 平级，运行期即归位，
     * 不再依赖重启迁移兜底）；已是 origin/ 三级或其他形式原样返回。
     */
    public static String toOriginPath(String path) {
        if (path == null) return null;
        int slash = path.indexOf('/');
        if (slash > 0 && path.indexOf('/', slash + 1) < 0 && !"origin".equals(path.substring(0, slash))) {
            return "origin/" + path;
        }
        return path;
    }

    /**
     * 启动迁移（幂等）：把工作区顶层的分类目录（原始文档，如 收件箱/、技术研发/）整体移入 origin/，
     * 同步 ContentItem.libraryPath 前缀并重建该喵索引。新结构下原始文档统一住 origin/（与 points、reading 平级）。
     */
    @PostConstruct
    public void migrateOriginFolders() {
        try {
            for (Cat cat : catRepo.findAll()) {
                String ws = cat.getWorkspacePath();
                if (ws == null || ws.isBlank()) continue;
                Path wsRoot = resolveWorkspace(ws);
                if (!Files.isDirectory(wsRoot)) continue;
                Path origin = wsRoot.resolve("origin");
                List<String> movedDirs = new ArrayList<>();
                try (Stream<Path> s = Files.list(wsRoot)) {
                    for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                        String name = p.getFileName().toString();
                        if (name.startsWith(".") || WS_RESERVED_TOP_DIRS.contains(name)) continue;
                        Path target = origin.resolve(name);
                        if (Files.exists(target)) continue; /* 同名冲突跳过，等人工处理 */
                        Files.createDirectories(origin);
                        Files.move(p, target);
                        movedDirs.add(name);
                    }
                }
                if (movedDirs.isEmpty()) continue;
                for (ContentItem it : contentRepo.findByLibraryPathIsNotNull()) {
                    String lp = it.getLibraryPath();
                    for (String dir : movedDirs) {
                        String oldPrefix = ws + "/" + dir + "/";
                        if (lp.startsWith(oldPrefix)) {
                            it.setLibraryPath(ws + "/origin/" + dir + "/" + lp.substring(oldPrefix.length()));
                            contentRepo.save(it);
                            break;
                        }
                    }
                }
                rewriteCatalogAndIndex(cat);
                log.info("[书库] 原始文档归位 {}: 移入 origin/ 的分类目录 {}", ws, movedDirs);
            }
        } catch (Exception e) {
            log.warn("[书库] 原始文档归位 origin/ 跳过: {}", e.toString());
        }
    }

    /**
     * 客户端互通的合法内容路径：两级 <分类>/<文件>.md，或原始文档三级 origin/<分类>/<文件>.md（相对喵工作区）。
     * 拒绝 ..、反斜杠、隐藏段、_ 前缀段、服务端内部目录（points/reading/study）、INDEX.md 与非 md
     * —— 和 writeArtifact 的越界防护形成双保险。
     */
    public boolean isLegalContentPath(String rel) {
        if (rel == null || rel.isBlank() || rel.contains("..") || rel.contains("\\")) return false;
        String[] parts = rel.split("/");
        boolean originThree = parts.length == 3 && "origin".equals(parts[0]);
        if (parts.length != 2 && !originThree) return false;
        for (String p : parts) {
            if (p.isBlank() || p.startsWith(".") || p.startsWith("_")) return false;
        }
        if (INTERNAL_TOP_DIRS.contains(parts[0])) return false;
        if ("INDEX.md".equals(parts[parts.length - 1])) return false;
        return parts[parts.length - 1].toLowerCase().endsWith(".md");
    }

    /** 去掉 libraryPath 上的 workspace 前缀（没有前缀的历史根级路径原样返回） */
    /** 去掉 libraryPath 的工作区前缀（public 供客户端接口输出工作区相对路径） */
    public static String stripWsPrefix(String libraryPath, String ws) {
        if (ws != null && libraryPath != null && libraryPath.startsWith(ws + "/")) {
            return libraryPath.substring(ws.length() + 1);
        }
        return libraryPath;
    }

    /**
     * 该喵工作区的内容 md 清单（客户端 diff 本地清单挑差异用）：
     * 两级 <分类>/*.md 与三级 origin/<分类>/*.md；排除隐藏目录、INDEX.md 与深层服务端产物（points/ 等）。
     */
    public List<Map<String, Object>> manifest(Cat cat) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path wsRoot = resolveWorkspace(cat.getWorkspacePath());
        if (!Files.isDirectory(wsRoot)) return out;
        try (Stream<Path> walk = Files.walk(wsRoot, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> !"INDEX.md".equals(p.getFileName().toString()))
                    .forEach(p -> {
                        String rel = wsRoot.relativize(p).toString().replace('\\', '/');
                        if (!isLegalContentPath(rel)) return;
                        try {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("path", rel);
                            m.put("sha256", sha256Hex(p));
                            m.put("bytes", Files.size(p));
                            m.put("modifiedAt", Files.getLastModifiedTime(p).toInstant().toString());
                            out.add(m);
                        } catch (IOException e) {
                            log.warn("[书库] manifest 读取失败 {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[书库] manifest 扫描失败 {}: {}", wsRoot, e.getMessage());
        }
        out.sort(Comparator.comparing(m -> (String) m.get("path")));
        return out;
    }

    /**
     * 单喵收编（客户端 apply）：磁盘有、库没有 → 补录；库有、磁盘无 → 剔除。
     * 只扫该喵工作区、只动该喵名下条目 —— 多用户互不波及。收编后重建该喵的工作区索引。
     */
    @Transactional
    public Map<String, Object> applyForCat(Cat cat) {
        Map<String, Object> result = doImportForCat(cat);
        rewriteCatalogAndIndex(cat);
        return result;
    }

    // ================= 客户端聚合接口：pull / push =================

    /**
     * 客户端内容拉取：全量内容文件（含正文）+ 与客户端基准（base: path→sha256，上次同步时记下的）的 diff。
     * changed = 两边都有但内容不同 → 冲突候选，客户端让用户修正后走 push 再提交；
     * base 缺省视为全新拉取（全部算 added，无冲突候选）。
     */
    public Map<String, Object> pullForCat(Cat cat, Map<String, String> base) {
        List<Map<String, Object>> contents = new ArrayList<>();
        List<String> added = new ArrayList<>(), changed = new ArrayList<>(), removed = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        boolean hasBase = base != null;
        for (Map<String, Object> m : manifest(cat)) {
            String path = (String) m.get("path");
            String sha = (String) m.get("sha256");
            Path p = resolveWorkspace(cat.getWorkspacePath()).resolve(path).normalize();
            String content;
            try {
                content = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("[书库] pull 读取失败 {}: {}", path, e.getMessage());
                continue;
            }
            contents.add(m);
            m.put("content", content);
            if (!hasBase) { added.add(path); continue; }
            String baseSha = base.get(path);
            if (baseSha == null) added.add(path);
            else if (!baseSha.equals(sha)) {
                /* 冲突：服务端与客户端基准不同 —— 客户端拿 serverContent 比对修正后再 push */
                changed.add(path);
                conflicts.add(Map.of("path", path, "clientBaseSha", baseSha, "serverSha256", sha, "serverContent", content));
            }
            base.remove(path);
        }
        if (base != null) removed.addAll(base.keySet()); /* base 有、服务端无 → 服务端已删除 */
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cat", Map.of("id", cat.getId(), "name", cat.getName(), "workspacePath", cat.getWorkspacePath()));
        out.put("contents", contents);
        out.put("added", added);
        out.put("changed", changed);
        out.put("removed", removed);
        out.put("conflicts", conflicts);
        out.put("note", "changed=服务端版本与你的 base 不同（服务端在同步窗口外改过）；冲突判定是三向的："
                + "本地文件 sha ≠ base 且 服务端 sha ≠ base = 双边都改过（真冲突，人工合并）；"
                + "本地 == base 且服务端 != base（changed 列表）→ 可用服务端版本覆盖本地；"
                + "本地 != base 且服务端 == base → 只有本地改过，直接 push（带本地原 baseSha）即可。"
                + "removed=服务端已删除，客户端可自行清理；push 支持 delete:true 同步删除");
        return out;
    }

    /** push 的单文件处理结果。delete=true = 客户端请求删除该文件（同样受冲突闸约束） */
    private Map<String, Object> pushOne(Cat cat, String path, String content, String baseSha, boolean delete) {
        path = toOriginPath(path); /* 两级 <分类>/<文件> 归位到 origin/ 下；冲突检测/落盘/响应统一用归一化路径 */
        Path p = resolveWorkspace(cat.getWorkspacePath()).resolve(path).normalize();
        String serverSha = null;
        if (Files.exists(p)) {
            try { serverSha = sha256Hex(p); } catch (IOException e) { serverSha = null; }
        }
        if (delete) {
            if (serverSha == null) return Map.of("path", path, "accepted", true, "deleted", true, "note", "服务端本无此文件（幂等）");
            /* 删除也受冲突闸：服务端版本与客户端基准不符时不删，防基于旧认知误删服务端新内容 */
            if (baseSha == null || !baseSha.equals(serverSha)) {
                String serverContent = "";
                try { serverContent = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException ignored) { }
                return Map.of("path", path, "conflict", true, "serverSha256", serverSha, "serverContent", serverContent);
            }
            try {
                deleteArtifact(cat.getWorkspacePath(), path);
                return Map.of("path", path, "accepted", true, "deleted", true);
            } catch (Exception e) {
                return Map.of("path", path, "rejected", true, "reason", String.valueOf(e.getMessage()));
            }
        }
        /* 冲突闸：服务端已有该文件、且客户端基准对不上当前服务端版本（含未带基准）→ 不覆盖，返回服务端内容让客户端修正 */
        if (serverSha != null && (baseSha == null || !baseSha.equals(serverSha))) {
            String serverContent = "";
            try { serverContent = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException ignored) { }
            return Map.of("path", path, "conflict", true, "serverSha256", serverSha, "serverContent", serverContent);
        }
        try {
            writeArtifact(cat.getWorkspacePath(), path, content);
            return Map.of("path", path, "accepted", true, "sha256", sha256Hex(p));
        } catch (Exception e) {
            return Map.of("path", path, "rejected", true, "reason", String.valueOf(e.getMessage()));
        }
    }

    /** 单文件内容上限（防异常客户端把接口当网盘）：2MB */
    private static final int PUSH_MAX_FILE_BYTES = 2 * 1024 * 1024;
    /** 单次 push 批量上限 */
    private static final int PUSH_MAX_BATCH = 100;

    /**
     * 客户端内容提交：逐文件冲突检测（baseSha 对不上服务端当前版本 → conflict，返回服务端内容，
     * 客户端修正后重新提交）→ 接受的文件落盘/删除。收编入库（apply）由 controller 在此之后调用。
     * 支持 delete:true 同步删除（apply 会自动剔除库记录并重建索引）。
     */
    public Map<String, Object> pushForCat(Cat cat, List<Map<String, Object>> files) {
        List<Map<String, Object>> accepted = new ArrayList<>(), conflicts = new ArrayList<>(), rejected = new ArrayList<>();
        for (Map<String, Object> f : files) {
            String path = f.get("path") == null ? "" : String.valueOf(f.get("path"));
            String content = f.get("content") == null ? "" : String.valueOf(f.get("content"));
            String baseSha = f.get("baseSha") == null ? null : String.valueOf(f.get("baseSha"));
            boolean delete = Boolean.parseBoolean(String.valueOf(f.getOrDefault("delete", "false")));
            if (!isLegalContentPath(path)) {
                rejected.add(Map.of("path", path, "rejected", true, "reason", "非法路径（需 <分类>/<文件>.md 两级或 origin/<分类>/<文件>.md 三级，排除隐藏/内部目录）"));
                continue;
            }
            if (files.size() > PUSH_MAX_BATCH) {
                rejected.add(Map.of("path", path, "rejected", true, "reason", "单批超过 " + PUSH_MAX_BATCH + " 个文件，请分批提交"));
                continue;
            }
            if (!delete && content.getBytes(StandardCharsets.UTF_8).length > PUSH_MAX_FILE_BYTES) {
                rejected.add(Map.of("path", path, "rejected", true, "reason", "单文件超过 " + (PUSH_MAX_FILE_BYTES / 1024 / 1024) + "MB 上限"));
                continue;
            }
            Map<String, Object> r = pushOne(cat, path, content, baseSha, delete);
            if (r.containsKey("conflict")) conflicts.add(r);
            else if (r.containsKey("rejected")) rejected.add(r);
            else accepted.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("conflicts", conflicts);
        out.put("rejected", rejected);
        out.put("note", conflicts.isEmpty()
                ? "全部接受；收编结果见 apply"
                : "存在冲突：服务端版本与客户端基准不同，已保留服务端内容未覆盖；客户端修正（合并/取舍）后把修正稿随新 baseSha 重新 push");
        return out;
    }

    private Map<String, Object> doImportForCat(Cat cat) {
        ensureRepo();
        String ws = cat.getWorkspacePath();
        Path wsRoot = resolveWorkspace(ws);
        Map<String, Path> onDisk = new TreeMap<>();
        if (Files.isDirectory(wsRoot)) {
            /* 深度 3：两级 <分类>/<文件>.md 与三级 origin/<分类>/<文件>.md 都在收编范围（isLegalContentPath 过滤非法） */
            try (Stream<Path> stream = Files.walk(wsRoot, 3)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .filter(p -> !"INDEX.md".equals(p.getFileName().toString()))
                        .forEach(p -> {
                            String rel = wsRoot.relativize(p).toString().replace('\\', '/');
                            if (isLegalContentPath(rel)) onDisk.put(rel, p);
                        });
            } catch (IOException e) {
                log.warn("[书库] 工作区扫描失败 {}: {}", wsRoot, e.getMessage());
            }
        }
        log.info("[书库] 喵 {} 工作区扫描到 {} 个内容 md", cat.getName(), onDisk.size());

        // 1) 该喵名下有 libraryPath 的条目：不在扫描集 且 磁盘确实没有 → 剔除（历史根级文件兜底不误删）
        List<ContentItem> mine = new ArrayList<>();
        for (ContentItem it : contentRepo.findByCatId(cat.getId())) {
            if (it.getLibraryPath() != null) mine.add(it);
        }
        List<String> removed = new ArrayList<>();
        for (ContentItem it : mine) {
            String wsRel = stripWsPrefix(it.getLibraryPath(), ws);
            boolean onDiskNow = Files.exists(root().resolve(it.getLibraryPath()));
            if (!onDisk.containsKey(wsRel) && !onDiskNow) {
                removed.add(it.getTitle());
                log.info("[书库] 文件已消失，剔除条目 {} ({})", it.getLibraryPath(), it.getTitle());
                contentRepo.delete(it);
            }
        }

        // 2) 工作区有、库里没有 → 补录（libraryPath 带 workspace 前缀，直接归属该喵）
        Set<String> known = new HashSet<>();
        for (ContentItem it : mine) known.add(it.getLibraryPath());
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Path> e : onDisk.entrySet()) {
            String fullRel = ws + "/" + e.getKey();
            if (known.contains(fullRel)) continue;
            try {
                added.add(importOne(cat, e.getKey(), e.getValue()).getTitle());
            } catch (Exception ex) {
                log.warn("[书库] 导入失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }

        String note = "共 " + onDisk.size() + " 篇";
        if (!added.isEmpty()) note += " · 补录 " + added.size();
        if (!removed.isEmpty()) note += " · 剔除 " + removed.size();
        if (added.isEmpty() && removed.isEmpty()) note += " · 没有变化";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", onDisk.size());
        m.put("added", added);
        m.put("removed", removed);
        m.put("note", note);
        return m;
    }

    /** 单喵版索引再生：.catalog.json / INDEX.md 写进该喵工作区，file 字段用相对工作区路径（客户端格式一致） */
    public void rewriteCatalogAndIndex(Cat cat) {
        String ws = cat.getWorkspacePath();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContentItem it : contentRepo.findByCatId(cat.getId())) {
            if (it.getLibraryPath() == null || !ContentItem.STATUS_CLASSIFIED.equals(it.getStatus())) continue;
            Map<String, Object> m = toCatalogItem(it);
            m.put("file", stripWsPrefix(it.getLibraryPath(), ws));
            items.add(m);
        }
        items.sort(Comparator.comparing(m -> (String) m.get("saved"), Comparator.reverseOrder()));
        try {
            Path wsRoot = resolveWorkspace(ws);
            Files.createDirectories(wsRoot);
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("items", items);
            Files.writeString(wsRoot.resolve(".catalog.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(catalog), StandardCharsets.UTF_8);
            /* 工作区根 INDEX.md：分区导航（origin/reading/points）+ 总目录表 */
            Files.writeString(wsRoot.resolve("INDEX.md"), buildWsIndex(items), StandardCharsets.UTF_8);
            /* origin/ 两级索引：分类总表 + 每个分类的文件清单 */
            writeOriginIndexes(wsRoot, items);
        } catch (IOException e) {
            log.error("[书库] 喵 {} 索引重建失败: {}", cat.getName(), e.getMessage());
        }
    }

    /** 工作区根 INDEX.md：分区导航 + 总目录（表格沿用客户端格式） */
    private String buildWsIndex(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 喵藏 · 工作区索引\n\n");
        sb.append("> 由喵藏自动维护 · 最近更新 ").append(LocalDateTime.now().format(SAVED_FMT)).append("\n\n");
        sb.append("## 分区导航\n\n");
        sb.append("| 分区 | 说明 | 索引 |");
        sb.append("\n|---|---|---|\n");
        sb.append("| 📥 origin | 原始文档（按分类存放） | [origin/INDEX.md](origin/INDEX.md) |\n");
        sb.append("| 📖 reading | 喵整理的书 | [reading/INDEX.md](reading/INDEX.md) |\n");
        sb.append("| 🃏 points | 理解卡片（按书分组） | [points/INDEX.md](points/INDEX.md) |\n");
        sb.append("\n## 总目录\n\n");
        sb.append(buildIndex(items));
        return sb.toString();
    }

    /** origin/ 索引：分类总表（origin/INDEX.md）+ 每分类文件清单（origin/<分类>/INDEX.md） */
    private void writeOriginIndexes(Path wsRoot, List<Map<String, Object>> items) throws IOException {
        /* 按分类聚合（条目 file 形如 origin/<分类>/<文件>.md；历史无 origin 前缀的按 category 字段兜底） */
        Map<String, List<Map<String, Object>>> byCat = new TreeMap<>();
        for (Map<String, Object> it : items) {
            String file = str(it.get("file"));
            String cat = file.startsWith("origin/") ? file.substring(7, file.lastIndexOf('/')) : str(it.get("category"));
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(it);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 📥 原始文档索引\n\n");
        sb.append("> 由喵藏自动维护 · 共 **").append(items.size()).append("** 篇\n\n");
        sb.append("| 分类 | 篇数 | 索引 |\n|---|---|---|\n");
        for (Map.Entry<String, List<Map<String, Object>>> e : byCat.entrySet()) {
            String cat = e.getKey();
            sb.append("| 📁 ").append(cat.replace("|", "/")).append(" | ").append(e.getValue().size())
              .append(" | [").append(safeFilename(cat, 30)).append("/INDEX.md](")
              .append(safeFilename(cat, 30)).append("/INDEX.md) |\n");
        }
        sb.append("\n> 新文档落盘后由喵藏自动更新本索引。\n");
        Path origin = wsRoot.resolve("origin");
        Files.createDirectories(origin);
        Files.writeString(origin.resolve("INDEX.md"), sb.toString(), StandardCharsets.UTF_8);
        /* 每个分类一个 INDEX.md：文件清单（链接相对 origin/ 目录） */
        for (Map.Entry<String, List<Map<String, Object>>> e : byCat.entrySet()) {
            String cat = e.getKey();
            Path dir = origin.resolve(safeFilename(cat, 50));
            Files.createDirectories(dir);
            StringBuilder cb = new StringBuilder();
            cb.append("# 📥 ").append(cat.replace("|", "/")).append(" · 文件清单\n\n");
            cb.append("> 由喵藏自动维护 · 共 **").append(e.getValue().size()).append("** 篇\n\n");
            cb.append("| 时间 | 标题 | 摘要 | 原文 |\n|---|---|---|---|\n");
            for (Map<String, Object> it : e.getValue()) {
                String stamp = String.valueOf(it.get("saved"));
                String title = str(it.get("title")).replace("|", "/");
                String file = str(it.get("file"));
                String rel = file.startsWith("origin/") ? file.substring("origin/".length()) : file;
                boolean note = "note".equals(it.get("kind"));
                String summ = str(it.get("summary")).replaceAll("\\s+", " ").replace("|", "/");
                if (summ.isEmpty()) summ = note ? "—" : "（全文）";
                if (summ.length() > 80) summ = summ.substring(0, 80).strip() + "…";
                String icon = note ? "✏️" : "🔗";
                String link = str(it.get("url"));
                String urlCell = link.isBlank() ? "—" : "[原文](" + link + ")";
                cb.append("| ").append(stamp).append(" | ").append(icon).append(" [").append(title).append("](")
                  .append(rel).append(") | ").append(summ).append(" | ").append(urlCell).append(" |\n");
            }
            cb.append("\n[← 返回 origin 索引](../INDEX.md)\n");
            Files.writeString(dir.resolve("INDEX.md"), cb.toString(), StandardCharsets.UTF_8);
        }
    }

    // ================= git 集成 =================

    /** 确保 git 仓库就绪：init -b main、gitignore、允许客户端直接 push（updateInstead） */
    public void ensureRepo() {
        try {
            Files.createDirectories(root());
        } catch (IOException e) {
            throw new IllegalStateException("书库目录创建失败: " + e.getMessage(), e);
        }
        if (!Files.exists(root().resolve(".git"))) {
            git(true, "init", "-b", "main");
            log.info("[书库] 已初始化 git 仓库: {}", root());
        }
        try {
            Path gi = root().resolve(".gitignore");
            if (!Files.exists(gi)) Files.writeString(gi, GITIGNORE, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        // 客户端 fetch→pull→push 的 push 目标：允许推到本仓库并自动更新工作区
        git(true, "config", "receive.denyCurrentBranch", "updateInstead");
        // 仓库级提交身份（不动全局配置）
        git(true, "config", "user.name", "MiaoCang-Service");
        git(true, "config", "user.email", "service@miaocang.local");
    }

    /** 提交当前书库变更（无变更返回 false） */
    public boolean commit(String message) {
        ensureRepo();
        if (git(true, "status", "--porcelain").out().isBlank()) return false;
        git(true, "add", "-A");
        git(false, "commit", "-m", message);
        log.info("[书库] git commit: {}", message);
        return true;
    }

    /** 可选：推送到配置的远端 */
    public String push() {
        if (gitRemote == null || gitRemote.isBlank()) {
            return "未配置 push 远端（miaocang.library.git-remote），客户端可直接 pull 本书库";
        }
        ensureRepo();
        GitResult r = git(true, "push", gitRemote, "HEAD");
        return r.exitCode() == 0 ? "已推送到 " + gitRemote : "推送失败: " + r.out().strip();
    }

    /** 书库状态总览 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean repo = Files.exists(root().resolve(".git"));
        m.put("dir", root().toString());
        m.put("repo", repo);
        m.put("totalItems", contentRepo.count());
        long synced = contentRepo.findByLibraryPathIsNotNull().size();
        m.put("syncedItems", synced);
        m.put("inboxItems", contentRepo.countByStatus(ContentItem.STATUS_PENDING));
        m.put("diskFiles", scanDisk().size());
        if (repo) {
            m.put("branch", git(true, "rev-parse", "--abbrev-ref", "HEAD").out().strip());
            m.put("dirty", !git(true, "status", "--porcelain").out().isBlank());
            GitResult last = git(true, "log", "-1", "--format=%h %s (%ci)");
            m.put("lastCommit", last.exitCode() == 0 && !last.out().isBlank() ? last.out().strip() : null);
        } else {
            m.put("branch", null);
            m.put("dirty", false);
            m.put("lastCommit", null);
        }
        m.put("remote", gitRemote == null ? "" : gitRemote);
        return m;
    }

    // ================= markdown / front-matter 解析 =================

    private record ParsedMd(Map<String, String> fm, String body) {
    }

    /** 客户端 _parse_front_matter 的 Java 对应：行式 key: value，值尝试 JSON 解析 */
    private ParsedMd parseMd(String text) {
        Map<String, String> fm = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n", -1);
        int bodyStart = 0;
        if (lines.length > 0 && lines[0].trim().equals("---")) {
            int i = 1;
            while (i < lines.length && !lines[i].trim().equals("---")) {
                String line = lines[i];
                int idx = line.indexOf(':');
                if (idx > 0) {
                    fm.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
                i++;
            }
            bodyStart = (i < lines.length) ? i + 1 : 0;
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length)).strip();
        return new ParsedMd(fm, body);
    }

    /** json.dumps(ensure_ascii=False) 产生的值 → 还原为普通字符串 */
    private String nonBlank(String raw) {
        String v = raw == null ? "" : raw.trim();
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) {
            try {
                return objectMapper.readValue(v, String.class);
            } catch (Exception ignored) {
            }
        }
        return v;
    }

    private String deriveTitleFromStem(String fileName) {
        String stem = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        if (stem.length() >= 11 && stem.charAt(4) == '-' && stem.charAt(7) == '-' && stem.charAt(10) == '_') {
            stem = stem.substring(11);
        }
        return stem.strip();
    }

    private LocalDateTime parseSaved(String saved, Path path) {
        if (saved != null && !saved.isBlank()) {
            try {
                return LocalDateTime.parse(saved.trim().replace('T', ' '), SAVED_FMT);
            } catch (Exception ignored) {
            }
        }
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String abbreviate(String s, int len) {
        if (s == null) return "";
        String stripped = s.strip();
        return stripped.length() <= len ? stripped : stripped.substring(0, len);
    }

    // ================= git 执行器 =================

    private record GitResult(int exitCode, String out, String err) {
    }

    private GitResult git(boolean tolerate, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root().toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("LC_ALL", "C");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GitResult(-1, out, "timeout");
            }
            int code = p.exitValue();
            if (code != 0 && !tolerate) {
                throw new IllegalStateException("git " + args[0] + " 失败: " + out.strip());
            }
            return new GitResult(code, out, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (tolerate) return new GitResult(-1, "", e.getMessage());
            throw new IllegalStateException("git 执行被中断", e);
        } catch (IOException e) {
            if (tolerate) return new GitResult(-1, "", e.getMessage());
            throw new IllegalStateException("git 执行失败: " + e.getMessage(), e);
        }
    }
}
