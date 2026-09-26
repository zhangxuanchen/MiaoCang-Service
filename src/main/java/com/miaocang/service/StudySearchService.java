package com.miaocang.service;

import com.miaocang.entity.Cat;
import com.miaocang.entity.LearnCard;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.LearnCardRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 全库全文检索（我的学习/全局搜索框的引擎）：Lucene + SmartChinese 中文分词。
 * 索引三类文档（均可再生，索引目录不进 git）：
 *   point — 知识点卡片 cat-N/points/P0001-*.md（key=P0001）
 *   book  — 书 md cat-N/reading/01-xxx.md（key=书文件名）
 *   learn — 学习卡 learn_card 表（key=learn-<id>）
 * 归纳完成后 reindexCat 增量刷新；启动时索引为空则自动全量重建。
 */
@Service
public class StudySearchService {

    private static final Logger log = LoggerFactory.getLogger(StudySearchService.class);

    private final LibrarySyncService librarySync;
    private final CatRepository catRepo;
    private final LearnCardRepository learnRepo;

    @Value("${miaocang.index.dir:data/index/study}")
    private String indexDir;

    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private IndexWriter writer;
    private volatile DirectoryReader reader;
    private volatile boolean ready = false;

    public StudySearchService(LibrarySyncService librarySync, CatRepository catRepo, LearnCardRepository learnRepo) {
        this.librarySync = librarySync;
        this.catRepo = catRepo;
        this.learnRepo = learnRepo;
    }

    @PostConstruct
    void open() throws IOException {
        Path dir = Path.of(indexDir);
        Files.createDirectories(dir);
        Directory directory = FSDirectory.open(dir);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
        log.info("[检索] Lucene 索引就绪: {}", dir.toAbsolutePath());
    }

    @PreDestroy
    void close() {
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) { /* 关闭失败无碍 */ }
    }

    @EventListener(ApplicationReadyEvent.class)
    void warmup() {
        try {
            if (countDocs() == 0) {
                reindexAll();
            } else {
                reopen();
                ready = true;
                log.info("[检索] 索引已有 {} 个文档，跳过重建", countDocs());
            }
        } catch (Exception e) {
            log.warn("[检索] 启动建索引失败: {}", e.getMessage());
        }
    }

    private long countDocs() throws IOException {
        DirectoryReader r = DirectoryReader.open(writer);
        long n = r.numDocs();
        r.close();
        return n;
    }

    // ================= 索引构建 =================

    /** 全量重建：清空后索引所有猫（POST /api/search/reindex 也可手动触发） */
    public synchronized Map<String, Object> reindexAll() throws IOException {
        writer.deleteAll();
        int n = 0;
        for (Cat cat : catRepo.findAll()) n += indexCatInternal(cat);
        writer.commit();
        reopen();
        ready = true;
        log.info("[检索] 全量重建完成: {} 个文档", n);
        return Map.of("indexed", n);
    }

    /** 重建某只猫的索引（归纳完成后调用，覆盖该猫全部 point/book/learn 文档） */
    public synchronized Map<String, Object> reindexCat(Long catId) throws IOException {
        Cat cat = catRepo.findById(catId).orElse(null);
        if (cat == null) return Map.of("indexed", 0);
        writer.deleteDocuments(new Term("catId", String.valueOf(catId)));
        int n = indexCatInternal(cat);
        writer.commit();
        reopen();
        return Map.of("indexed", n);
    }

    /** 一只猫的索引维度：points 递归（按书分组的卡片）+ reading 书 md + 学习卡；path = 工作区相对路径，供 read_file 定位 */
    private int indexCatInternal(Cat cat) throws IOException {
        Long catId = cat.getId();
        int n = 0;
        Path root = librarySync.resolveWorkspace(cat.getWorkspacePath());
        /* 知识点卡片：points/{书}/{no}-名称.md（按书分组，递归扫），key 仍取文件名前缀 no */
        Path points = root.resolve("points");
        if (Files.isDirectory(points)) {
            try (Stream<Path> s = Files.walk(points)) {
                for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                    String fname = p.getFileName().toString();
                    int cut = fname.indexOf('-');
                    if (cut <= 0) continue;
                    addDoc(catId, "point", fname.substring(0, cut),
                            fname.substring(cut + 1, fname.length() - 3),
                            Files.readString(p, StandardCharsets.UTF_8),
                            root.relativize(p).toString().replace('\\', '/'));
                    n++;
                }
            }
        }
        /* 书 md：首行 # 《书名》 */
        Path reading = root.resolve("reading");
        if (Files.isDirectory(reading)) {
            try (Stream<Path> s = Files.list(reading)) {
                for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                    String fname = p.getFileName().toString();
                    String md = Files.readString(p, StandardCharsets.UTF_8);
                    String title = fname;
                    if (md.startsWith("# ")) {
                        int nl = md.indexOf('\n');
                        title = md.substring(2, nl > 2 ? nl : md.length()).replace("《", "").replace("》", "");
                    }
                    addDoc(catId, "book", fname, title, md, "reading/" + fname);
                    n++;
                }
            }
        }
        /* 学习卡 */
        for (LearnCard c : learnRepo.findByCatId(catId)) {
            addDoc(catId, "learn", "learn-" + c.getId(), c.getFront(), c.getBack() == null ? "" : c.getBack(), null);
            n++;
        }
        return n;
    }

    private void addDoc(Long catId, String type, String key, String title, String body, String path) throws IOException {
        Document d = new Document();
        d.add(new StringField("catId", String.valueOf(catId), Field.Store.YES));
        d.add(new StringField("type", type, Field.Store.YES));
        d.add(new StringField("key", type + ":" + key, Field.Store.YES));
        d.add(new TextField("title", title == null ? "" : title, Field.Store.YES));
        /* body 存储化：搜索命中可直接回读 md 正文（point 弹窗展示用，避免卡片表编号错位） */
        d.add(new TextField("body", body == null ? "" : body, Field.Store.YES));
        /* path：工作区相对路径（point/book 有，learn 无），RAG 提示喵用 read_file 读全文用 */
        if (path != null) d.add(new StringField("path", path, Field.Store.YES));
        writer.updateDocument(new Term("key", type + ":" + key), d);
    }

    private void reopen() throws IOException {
        DirectoryReader old = reader;
        reader = old == null ? DirectoryReader.open(writer) : DirectoryReader.openIfChanged(old, writer);
        if (old != null && reader != old) old.close();
    }

    // ================= 查询 =================

    /** 全库检索：q 关键词（SmartChinese 分词、title 加权 2 倍），catId 过滤，按相关度排序 */
    public List<Map<String, Object>> search(Long catId, String q, int limit) {
        return search(catId == null ? null : java.util.Set.of(catId), q, limit);
    }

    /** 跨喵全库检索（专属会话 / 搜索）：catIds = 可见喵集合，null = 不过滤（全库），空集 = 无权可见返回空 */
    public List<Map<String, Object>> search(java.util.Set<Long> catIds, String q, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ready || q == null || q.isBlank()) return out;
        if (catIds != null && catIds.isEmpty()) return out;
        try {
            DirectoryReader r = reader;
            if (r == null) return out;
            IndexSearcher searcher = new IndexSearcher(r);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[]{"title", "body"}, analyzer,
                    Map.of("title", 2.0f));
            Query q1 = parser.parse(MultiFieldQueryParser.escape(q.trim()));
            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            bq.add(q1, BooleanClause.Occur.MUST);
            if (catIds != null) {
                BooleanQuery.Builder ids = new BooleanQuery.Builder();
                catIds.forEach(id -> ids.add(new TermQuery(new Term("catId", String.valueOf(id))), BooleanClause.Occur.SHOULD));
                bq.add(ids.build(), BooleanClause.Occur.FILTER);
            }
            TopDocs td = searcher.search(bq.build(), Math.min(Math.max(limit, 1), 50));
            for (ScoreDoc sd : td.scoreDocs) {
                Document d = searcher.storedFields().document(sd.doc);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", d.get("type"));
                row.put("key", d.get("key"));
                row.put("title", d.get("title"));
                row.put("body", d.get("body"));
                row.put("path", d.get("path"));
                row.put("score", Math.round(sd.score * 100) / 100.0);
                row.put("catId", Long.valueOf(d.get("catId")));
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("[检索] 查询失败 q={}: {}", q, e.getMessage());
        }
        return out;
    }

    public boolean isReady() {
        return ready;
    }
}
