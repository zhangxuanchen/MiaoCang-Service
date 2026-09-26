package com.miaocang.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.Book;
import com.miaocang.entity.BookType;
import com.miaocang.entity.Cat;
import com.miaocang.entity.AgentSkill;
import com.miaocang.repository.AgentSkillRepository;
import com.miaocang.repository.BookRepository;
import com.miaocang.repository.BookTypeRepository;
import com.miaocang.repository.CatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动种子任务：
 * 1. 播种默认猫（小肥、喵喵）——侧栏展示的宠物名单；
 * 2. 播种 22 个预设知识分类并挂到第一只猫下（幂等：已存在同名则跳过），
 *    为每个类型创建一本默认的书；
 * 3. 老库兜底：没有归属猫的分类/技能/日记统一移交第一只猫。
 */
@Component
public class PresetSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PresetSeeder.class);

    private final CatRepository catRepo;
    private final BookTypeRepository typeRepo;
    private final BookRepository bookRepo;
    private final AgentSkillRepository skillRepo;
    private final com.miaocang.repository.ContentItemRepository contentRepo;
    private final ExtractPipeline pipeline;
    private final ObjectMapper objectMapper;

    public PresetSeeder(CatRepository catRepo, BookTypeRepository typeRepo, BookRepository bookRepo,
                        AgentSkillRepository skillRepo,
                        com.miaocang.repository.ContentItemRepository contentRepo,
                        ExtractPipeline pipeline, ObjectMapper objectMapper) {
        this.catRepo = catRepo;
        this.typeRepo = typeRepo;
        this.bookRepo = bookRepo;
        this.skillRepo = skillRepo;
        this.contentRepo = contentRepo;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PresetType {
        public String name;
        public String icon;
        public String color;
        public String description;
        public List<String> gravityKeywords;
        public Object catalogRules;
    }

    @Override
    public void run(String... args) throws Exception {
        backfillWorkspacePaths();
        Cat first = seedCatsIfEmpty();

        if (typeRepo.count() > 0) {
            // 老库兜底：catId 为空的分类挂到第一只猫
            List<BookType> orphans = typeRepo.findByCatIdIsNullOrderByIdAsc();
            for (BookType t : orphans) {
                t.setCatId(first.getId());
                typeRepo.save(t);
            }
            // 老库兜底：catId 为空的技能挂到第一只猫
            List<AgentSkill> orphanSkills = skillRepo.findByCatIdIsNullOrderByIdAsc();
            for (AgentSkill s : orphanSkills) {
                s.setCatId(first.getId());
                skillRepo.save(s);
            }
            dedupeSkills();
            backfillContentCatId();
            seedAllCats();
            log.info("书籍类型已存在（{} 个），为 {} 个无主分类 / {} 条无主技能指定了归属猫",
                    typeRepo.count(), orphans.size(), orphanSkills.size());
            return;
        }

        PresetType[] presets = objectMapper.readValue(
                new ClassPathResource("presets.json").getInputStream(), PresetType[].class);
        for (PresetType p : presets) {
            if (!typeRepo.findByName(p.name).isEmpty()) continue;

            BookType type = new BookType();
            type.setName(p.name);
            type.setIcon(p.icon);
            type.setColor(p.color);
            type.setDescription(p.description);
            type.setGravityKeywords(p.gravityKeywords == null ? "" : String.join(",", p.gravityKeywords));
            type.setCatalogRules(objectMapper.writeValueAsString(p.catalogRules));
            type.setPreset(true);
            type.setCatId(first.getId());
            typeRepo.save(type);

            Book book = new Book();
            book.setTitle("《" + p.name + "》");
            book.setTypeId(type.getId());
            book.setDescription(p.description);
            book.setDefaultBook(true);
            bookRepo.save(book);
        }
        log.info("已初始化 {} 个预设知识分类，全部由「{}」照看", presets.length, first.getName());
        seedAllCats();
    }

    /** 给每只猫补播种内置技能（幂等；覆盖新库首启/新领养的猫） */
    private void seedAllCats() {
        for (var c : catRepo.findAll()) pipeline.seedPresetsForCat(c.getId());
    }

    /** 老库迁移：为 workspacePath 为空的存量猫回填 "cat-" + id（与 CatController.create 一致） */
    private void backfillWorkspacePaths() {
        int n = 0;
        for (Cat c : catRepo.findAll()) {
            if (c.getWorkspacePath() == null || c.getWorkspacePath().isBlank()) {
                c.setWorkspacePath("cat-" + c.getId());
                catRepo.save(c);
                n++;
            }
        }
        if (n > 0) log.info("[工作区] 为 {} 只老猫回填了 workspacePath", n);
    }

    /** 同一只猫下同名技能去重（老库迁移与内置播种可能撞名）：保留最早一条 */
    private void dedupeSkills() {
        Set<String> seen = new HashSet<>();
        for (AgentSkill s : skillRepo.findAll()) {
            if (!seen.add(s.getCatId() + "|" + s.getName())) skillRepo.delete(s);
        }
    }

    /** 老库回填：已归档但缺归属喵的内容，按 书→分类→猫 链补齐（预设分类共享后内容直接挂归属喵） */
    private void backfillContentCatId() {
        int n = 0;
        for (com.miaocang.entity.ContentItem c : contentRepo.findByBookIdIsNotNullAndCatIdIsNull()) {
            Long catId = bookRepo.findById(c.getBookId())
                    .map(b -> typeRepo.findById(b.getTypeId()).map(com.miaocang.entity.BookType::getCatId).orElse(null))
                    .orElse(null);
            if (catId != null) {
                c.setCatId(catId);
                contentRepo.save(c);
                n++;
            }
        }
        if (n > 0) log.info("[归属回填] 为 {} 条已归档内容补齐了归属喵", n);
    }

    /** 播种默认猫：小肥（橘猫）与喵喵（灰猫）。已有猫则跳过。 */
    private Cat seedCatsIfEmpty() {
        if (catRepo.count() > 0) {
            return catRepo.findAll().stream()
                    .min((a, b) -> {
                        int x = a.getOrderIndex() == null ? 0 : a.getOrderIndex();
                        int y = b.getOrderIndex() == null ? 0 : b.getOrderIndex();
                        return x != y ? Integer.compare(x, y) : Long.compare(a.getId(), b.getId());
                    })
                    .orElseThrow();
        }
        Cat xiaofei = new Cat();
        xiaofei.setName("小肥");
        xiaofei.setIcon("🐱");
        xiaofei.setColor("#F59E0B");
        xiaofei.setDescription("一只爱囤知识的橘猫，负责照看第一批知识分类。");
        xiaofei.setOrderIndex(0);
        catRepo.save(xiaofei);

        Cat miaomiao = new Cat();
        miaomiao.setName("喵喵");
        miaomiao.setIcon("😸");
        miaomiao.setColor("#7C6FF0");
        miaomiao.setDescription("好奇心旺盛的灰猫，随时准备接管新的知识领域。");
        miaomiao.setOrderIndex(1);
        catRepo.save(miaomiao);

        log.info("已领养默认猫：小肥、喵喵");
        return xiaofei;
    }
}
