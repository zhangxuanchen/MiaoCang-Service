package com.miaocang.auth;

import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.User;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.UserRepository;
import com.miaocang.service.LibrarySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * 启动迁移：多用户改造的一次性收编。
 * 1) 系统无任何用户 → 播种默认账号 admin / admin123；
 * 2) 无主猫挂到第一个用户（admin）；
 * 3) 旧工作区 data/library/cat-N/ → data/library/<username>/cat-N/
 *    （每用户专属根目录，子目录是养的喵；物理目录 Files.move 保留全部内容，
 *     library 根仍是同一个 git 仓库，迁移用 git mv 语义记录 rename）；
 * 4) 旧库 book_type.name 带全局唯一约束（多用户下跨主人同名分类合法）→ 幂等 drop；
 * 5) 旧库内容 md 落在书库根级（<分类>/<文件>.md，未按喵隔离）→ 按条目归属喵
 *    Files.move 归位到 <username>/cat-N/<分类>/<文件>.md 并更新 libraryPath。
 */
@Component
public class BootstrapUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapUsers.class);

    private final UserRepository userRepo;
    private final CatRepository catRepo;
    private final LibrarySyncService librarySync;
    private final DataSource dataSource;
    private final BookRepository bookRepo;
    private final BookTypeRepository typeRepo;
    private final ContentItemRepository contentRepo;
    private final com.miaocang.service.ReadingService readingService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public BootstrapUsers(UserRepository userRepo, CatRepository catRepo,
                          LibrarySyncService librarySync, DataSource dataSource,
                          BookRepository bookRepo, BookTypeRepository typeRepo,
                          ContentItemRepository contentRepo,
                          com.miaocang.service.ReadingService readingService) {
        this.userRepo = userRepo;
        this.catRepo = catRepo;
        this.librarySync = librarySync;
        this.dataSource = dataSource;
        this.bookRepo = bookRepo;
        this.typeRepo = typeRepo;
        this.contentRepo = contentRepo;
        this.readingService = readingService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        dropLegacyUniqueOnTypeName();
        /* 卡片编号修复：no 缺失/撞号的卡顺延重编（两批整理各自从 P0001 编起会撞号，反追踪会张冠李戴） */
        for (Cat c : catRepo.findAll()) {
            try {
                readingService.fixCardNos(c.getId());
                readingService.pruneOrphanCards(c.getId());
            } catch (Exception e) {
                log.warn("[卡片修复] 喵 {} 处理失败: {}", c.getId(), e.getMessage());
            }
        }
        User first = userRepo.findAll().stream().findFirst().orElse(null);
        if (first == null) {
            first = new User();
            first.setUsername("admin");
            first.setDisplayName("admin");
            first.setPasswordHash(encoder.encode("admin123"));
            userRepo.save(first);
            log.info("[多用户] 已播种默认账号 admin / admin123（请尽快登录后修改密码）");
        }

        List<Cat> orphans = catRepo.findAll().stream().filter(c -> c.getUserId() == null).toList();
        for (Cat cat : orphans) {
            cat.setUserId(first.getId());
            String old = cat.getWorkspacePath();
            if (old != null && !old.contains("/") && old.matches("cat-\\d+")) {
                String renamed = first.getUsername() + "/" + old;
                Path src = librarySync.root().resolve(old);
                Path dst = librarySync.root().resolve(renamed);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst.getParent());
                    if (!Files.exists(dst)) {
                        Files.move(src, dst);
                        log.info("[多用户] 工作区迁移: {} -> {}", src, dst);
                    }
                }
                cat.setWorkspacePath(renamed);
            }
            catRepo.save(cat);
        }
        if (!orphans.isEmpty()) {
            librarySync.commit("多用户改造: 猫工作区收编到各自主人目录下");
        }
        relocateRootContents();
    }

    /**
     * 5) 旧库内容 md 直接落在书库根级（<分类>/<文件>.md，两层路径 = 无 workspace 前缀）：
     *    按条目归属喵（catId → 书 → 分类 → 猫）Files.move 归位到 <workspacePath>/<分类>/<文件>.md
     *    并更新 libraryPath。库里没有记录 / 推导不出归属的文件留原地不动（不猜归属）。
     *    幂等：带前缀的路径（四层）天然跳过；目标已存在时同内容删源、异内容留原地记警。
     */
    private void relocateRootContents() {
        List<ContentItem> misplaced = contentRepo.findByLibraryPathIsNotNull().stream()
                .filter(it -> it.getLibraryPath() != null && it.getLibraryPath().split("/").length == 2)
                .toList();
        if (misplaced.isEmpty()) return;
        int moved = 0;
        for (ContentItem it : misplaced) {
            Long catId = it.getCatId();
            if (catId == null && it.getBookId() != null) {
                catId = bookRepo.findById(it.getBookId())
                        .map(b -> typeRepo.findById(b.getTypeId()).map(BookType::getCatId).orElse(null))
                        .orElse(null);
            }
            if (catId == null) continue;
            Cat cat = catRepo.findById(catId).orElse(null);
            if (cat == null || cat.getWorkspacePath() == null || cat.getWorkspacePath().isBlank()) continue;
            Path src = librarySync.root().resolve(it.getLibraryPath());
            if (!Files.exists(src)) continue;
            Path dst = librarySync.root().resolve(cat.getWorkspacePath()).resolve(it.getLibraryPath());
            try {
                if (Files.exists(dst)) {
                    // 幂等重跑：目标已在 → 同内容清掉源文件即可；内容不同（异常碰撞）留原地人工处理
                    if (LibrarySyncService.sha256Hex(src).equals(LibrarySyncService.sha256Hex(dst))) {
                        Files.deleteIfExists(src);
                    } else {
                        log.warn("[多用户] 归位目标已存在且内容不同，跳过 {} -> {}", src, dst);
                        continue;
                    }
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst);
                }
                it.setLibraryPath(cat.getWorkspacePath() + "/" + it.getLibraryPath());
                contentRepo.save(it);
                moved++;
            } catch (IOException e) {
                log.warn("[多用户] 内容归位失败 {}: {}", it.getLibraryPath(), e.getMessage());
            }
        }
        if (moved > 0) {
            librarySync.commit("多用户改造: 内容文件归位到各喵工作区");
            log.info("[多用户] 已把 {} 个根级内容文件归位到各喵工作区", moved);
        }
    }

    /**
     * 旧库 book_type.name 上有全局唯一约束（单用户时代遗留）：多用户下跨主人同名分类合法，幂等 drop。
     * 用独立 JDBC 连接执行——SQL 异常会把 JPA 事务标记 rollback-only，导致整个启动事务提交失败。
     */
    private void dropLegacyUniqueOnTypeName() {
        try (Connection cn = dataSource.getConnection(); Statement st = cn.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE UPPER(TABLE_NAME)='BOOK_TYPE' AND CONSTRAINT_TYPE='UNIQUE'")) {
                while (rs.next()) names.add(rs.getString(1));
            }
            for (String n : names) {
                st.execute("ALTER TABLE book_type DROP CONSTRAINT \"" + n + "\"");
                log.info("[多用户] 已移除 book_type.name 旧唯一约束: {}", n);
            }
        } catch (Exception e) {
            log.warn("[多用户] book_type.name 约束检查跳过: {}", e.toString());
        }
    }
}
