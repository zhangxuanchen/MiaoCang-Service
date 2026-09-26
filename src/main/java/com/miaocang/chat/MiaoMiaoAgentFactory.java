package com.miaocang.chat;

import com.miaocang.entity.AgentConfig;
import com.miaocang.entity.Cat;
import com.miaocang.repository.CatRepository;
import com.miaocang.service.MiaomiaoService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 每猫 Agent 装配工厂（每轮新建实例，规避有状态不可并发复用陷阱）。
 *
 * <p>装配链（参照 Memory-Observatory AgentFactory）：
 * <ol>
 *   <li>{@link MiaomiaoService#config(Long)} 取该猫生效的 LLM 配置（猫行优先，全局兜底）
 *       → {@link OpenAIChatModel}（OpenAI 兼容端点，DeepSeek/GLM/Qwen 均可）</li>
 *   <li>{@link ReActAgent#builder()}：猫 persona 系统提示 + 工作区范围说明 + 会话记忆注入块
 *       + {@link Toolkit}（read_file / write_file / list_files / memory_recall）</li>
 *   <li>{@link HarnessAgent.Builder#fromAgent}：workspace 绑定该猫沙箱目录
 *       {@code workspaceRoot/.workbench/}，agentId 固定 "miaomiao"</li>
 * </ol>
 *
 * <p>工具的操作范围 = 该猫的存储文档位置（{@code workspaceRoot}），文件改动直接落书库目录。
 * mock 模式（未配 AK）返回 null，由 {@link ChatSessionService} 走模拟回复短路。
 */
@Service
public class MiaoMiaoAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MiaoMiaoAgentFactory.class);

    private static final int MAX_ITERS = 25;

    private final MiaomiaoService miaomiao;
    private final CatWorkspaceService catWorkspace;
    private final CatRepository catRepo;
    private final ConversationMemory conversationMemory;
    private final ChatProperties props;
    private final com.miaocang.repository.ContentItemRepository contents;

    public MiaoMiaoAgentFactory(MiaomiaoService miaomiao, CatWorkspaceService catWorkspace,
                                CatRepository catRepo, ConversationMemory conversationMemory,
                                ChatProperties props,
                                com.miaocang.repository.ContentItemRepository contents) {
        this.miaomiao = miaomiao;
        this.catWorkspace = catWorkspace;
        this.catRepo = catRepo;
        this.conversationMemory = conversationMemory;
        this.props = props;
        this.contents = contents;
    }

    /**
     * 装配该猫本轮的 Agent 实例。
     *
     * @param catId         猫 id（决定 LLM 配置与 persona）
     * @param workspacePath 猫工作区相对路径（如 "cat-1"）
     * @param sessionId     会话 id（日志追踪用）
     * @param memoryContext 会话历史注入块（可 null = 新会话）
     * @param traceMw 喵日记 trace 中间件（可为 null）：挂在框架 middleware 链上补记 MSG_RECV / 工具参数
     * @return 装配好的 HarnessAgent；mock 模式返回 null
     */
    public HarnessAgent create(Long catId, String workspacePath, String sessionId, String memoryContext,
                               io.agentscope.core.middleware.MiddlewareBase traceMw) {
        boolean master = catId != null && catId == ChatSessionService.MASTER_CAT_ID;
        // 管理员用全局 LLM 配置（config(null) 走全局兜底）
        AgentConfig cfg = miaomiao.config(master ? null : catId);
        if (!miaomiao.liveMode(master ? null : catId)) {
            log.info("[会话区] 猫 {} 未配置真实模型（provider={}），走模拟回复", catId, cfg.getProvider());
            return null;
        }

        Path workspaceRoot = catWorkspace.workspaceRoot(workspacePath);
        Path sandbox = catWorkspace.sandboxRoot(workspacePath);
        try {
            Files.createDirectories(workspaceRoot);
            Files.createDirectories(sandbox);
        } catch (Exception e) {
            throw new IllegalStateException("猫工作区目录创建失败: " + e.getMessage(), e);
        }

        // 1. 模型：该猫生效的 OpenAI 兼容配置
        Model model = OpenAIChatModel.builder()
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModel())
                .baseUrl(cfg.getBaseUrl())
                .stream(true)
                .build();

        // 2. 工具集：文件三件套 + 记忆召回 + 详情页指挥（open_doc/rename_doc/open_file）
        String memKey = ConversationMemory.key(workspacePath, "miaomiao", sessionId);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkspaceFileTools(workspaceRoot, workspacePath, conversationMemory, memKey));
        toolkit.registerTool(new UIControlTools(master, contents, master ? null : catId));

        // 3. 系统提示：persona + 工作区范围 + 记忆注入（管理员另有全库版图）
        Cat cat = master ? null : catRepo.findById(catId).orElse(null);
        String sysPrompt = master
                ? composeMasterSystemPrompt(memoryContext, workspacePath)
                : composeSystemPrompt(cat, workspacePath, memoryContext);

        // 4. ReActAgent 主体 → HarnessAgent 包装（workspace + agentId）
        ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .name(master ? "wikimaster" : "miaomiao")
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(MAX_ITERS);
        if (traceMw != null) {
            agentBuilder.middleware(traceMw); /* 普通中间件会被 HarnessAgent.fromAgent 原样复制 */
        }
        ReActAgent built = agentBuilder.build();
        /* harness 自带一套 read_file / write_file / list_files，注册在同一个 toolkit 且晚于我们，
           会把上面那套按工作区范围实现的同名工具覆盖掉 —— 结果是喵的 write_file 落到 sandbox
           （.workbench/miaomiao/）而不是工作区、read_file 看不见真实文档，而且它的写入不带
           {"type":"file","action":"write"} 协议串，前端文件树与中栏预览收不到任何信号。
           关掉 harness 的文件系统工具，让提示词里「只能操作这个工作区」「写入后前端自动刷新」成立。 */
        return HarnessAgent.builder()
                .fromAgent(built)
                .workspace(sandbox)
                .agentId("miaomiao")
                .disableFilesystemTools()
                /* 长期记忆抽取（flush）默认每轮都跑（FlushTrigger.always），而 harness 把它串在事件流尾部
                   （MemoryFlushMiddleware: next.concatWith(doFlush)），done 必须等这整趟 LLM 记忆抽取跑完——
                   实测正文吐完后还要多等 30~140s，前端就一直挂着「思考中…」。
                   改成限流：同一隔离键 10 分钟内最多抽一次，把收尾耗时从「每轮必付」降为「偶发」。 */
                .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                        .build())
                .build();
    }

    /** 组装系统提示：猫 persona + 工作区操作范围说明 + 会话记忆注入块。 */
    private String composeSystemPrompt(Cat cat, String workspacePath, String memoryContext) {
        String base = baseSystemPrompt(cat, workspacePath);
        if (memoryContext == null || memoryContext.isBlank()) {
            return base;
        }
        return base + "\n\n【会话记忆】\n以下是本会话此前的对话记忆（更早的已折叠成摘要）：\n"
                + memoryContext
                + "\n（如需被压缩轮次的完整原文，可按【记忆归档 #id】标记用 memory_recall 工具找回。）";
    }

    /** 基础系统提示：猫 persona + 工作区说明（不含会话记忆注入），SYSTEM 区占用的计量口径。 */
    public String baseSystemPrompt(Cat cat, String workspacePath) {
        StringBuilder sb = new StringBuilder();
        String name = cat != null ? cat.getName() : "喵喵";
        String desc = cat != null && cat.getDescription() != null ? cat.getDescription() : "";
        sb.append("你是「").append(name).append("」，一只照看知识库的猫助理。");
        if (!desc.isBlank()) {
            sb.append("你的简介：").append(desc);
        }
        sb.append("\n\n【工作区】\n")
                .append("你有自己的专属工作区（").append(workspacePath).append("/），")
                .append("里面存放着这只猫的全部存储文档。你的 read_file / write_file / list_files 工具")
                .append("只能操作这个工作区内的文件，路径一律用相对路径。")
                .append("速览已经给你，不必再花轮次 list_files 摸底；拿不准个别目录时才确认。")
                .append("用户请你整理、总结、续写文档时，直接把结果写回工作区内的文件。")
                .append("\n\n【工作区速览 · SYSTEM 固化】\n")
                .append(workspaceBrief(catWorkspace.workspaceRoot(workspacePath)))
                .append("\nINDEX.md 是你亲手维护的工作区索引：新建 / 移动 / 删除文件后要同步更新它，主人和你自己都靠它找东西；")
                .append("速览是本轮会话开始时的快照，写入后以你的记忆与 list_files 为准。")
                .append("\n\n【屏幕指挥】你可以直接操作主人的屏幕：\n")
                .append("- 主人说「打开xxx」：书库内容用 open_doc（详情页会弹出）；工作区文件用 open_file（中栏会打开预览）。\n")
                .append("  open_file 的 path 是工作区相对路径（如 origin/技术研发/xx.md），不要带 data/library/… 这类书库前缀。\n")
                .append("- 主人说「把xxx改名为yyy / 改标题」：用 rename_doc，详情页会实时刷新出新标题。\n")
                .append("- 你 write_file / edit_file 改过工作区文件后，主人的文件树与中栏预览会自动打开并刷新，不用口头描述文件变化。\n")
                .append("  所以改工作区文件一律走 write_file / edit_file；别用 execute 里的 shell 重定向去改，那样主人屏幕上看不到效果。\n")
                .append("- 只改一小段、追加一节、修个错字，用 edit_file（局部替换，不必重写全文，也不会把没写到的原文弄丢）。\n")
                .append("- 找「哪篇文档提到过某个词」用 grep_files，找「某目录下有哪些文档」用 glob_files，比逐个 read_file 快。\n")
                .append("- 执行了屏幕动作后不要再复述「已打开/已刷新」，直接说结论。\n")
                .append("\n\n【知识引用】\n")
                .append("主人消息前面若带【领地检索命中】块，说明系统已替你翻过档案：")
                .append("回答知识类问题优先引用其中的内容并标注《标题》出处；")
                .append("命中块没覆盖的部分再用文件工具深入查证，或如实说不知道，不要编造。");
        return sb.toString();
    }

    /* ---- Wiki 管理员（catId=0）---- */

    /** 管理员系统提示计量口径（记忆面板 SYSTEM 区展示用）。 */
    public int masterSystemChars(String masterWorkspacePath) {
        return baseMasterSystemPrompt(masterWorkspacePath).length();
    }

    /** 组装管理员系统提示：persona + 全库版图 + 会话记忆注入块。 */
    private String composeMasterSystemPrompt(String memoryContext, String masterWs) {
        String base = baseMasterSystemPrompt(masterWs);
        if (memoryContext == null || memoryContext.isBlank()) {
            return base;
        }
        return base + "\n\n【会话记忆】\n以下是本会话此前的对话记忆（更早的已折叠成摘要）：\n"
                + memoryContext
                + "\n（如需被压缩轮次的完整原文，可按【记忆归档 #id】标记用 memory_recall 工具找回。）";
    }

    /**
     * 管理员基础系统提示：Wiki 管理员 persona + 馆内版图（猫名单 + 顶层结构）。
     * 多用户隔离：管理员的「馆」= 当前登录用户的专属根目录（masterWs），
     * 名册只列工作区位于该根目录之下的喵，版图只看该根目录的顶层。
     */
    private String baseMasterSystemPrompt(String masterWs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「Wiki管理员」，喵藏个人知识馆的总管家，也是主人养的一群整理猫的馆长。\n")
                .append("每只猫只看得到自己的工作区，而你持有全部工作区权限，可以替主人查询、盘点、整理、撰写整个书库的内容。\n");

        // 馆员名册：猫名单（含工作区路径），只列当前用户名下的喵
        sb.append("\n【馆员名册】\n");
        for (Cat c : catRepo.findAll()) {
            String ws = c.getWorkspacePath() == null || c.getWorkspacePath().isBlank()
                    ? "cat-" + c.getId() : c.getWorkspacePath();
            if (!ws.startsWith(masterWs + "/")) continue;
            sb.append("- ").append(c.getName())
                    .append("（工作区 ").append(ws).append("/）");
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                sb.append("：").append(c.getDescription());
            }
            sb.append("\n");
        }

        // 馆内版图：根 INDEX.md 摘要 + 两级目录结构（与猫的工作区速览同一口径）
        sb.append("\n【书库版图 · SYSTEM 固化】\n")
                .append(workspaceBrief(catWorkspace.workspaceRoot(masterWs)))
                .append("版图是本轮会话开始时的快照，动手前拿不准可用 list_files 确认。");

        sb.append("\n【权限与行为准则】\n")
                .append("你的 read_file / write_file / edit_file / list_files / grep_files / glob_files 工具作用于整个书库（所有猫的工作区 + 公共区），路径一律用相对路径。\n")
                .append("- 查询类请求（找资料 / 统计 / 对比 / 摘录）：先用 list_files 摸清结构，grep_files 定位关键词、glob_files 按模式列文件，再 read_file 读取，直接给出答案并注明出处文件。\n")
                .append("- 操作类请求（整理 / 改写 / 新建 / 归档）：全新文件用 write_file、局部改动用 edit_file 写回对应位置；跨猫协作就是读 A 猫的文件写到 B 猫的工作区。\n")
                .append("- 书库有 git 版本管理，改动可追溯，放心操作；但主人数据无价，整文件覆盖或删除前先复述你的计划征得同意。\n")
                .append("- 拿不准某只猫的领地习惯时，按文件名与现有结构保持一致，不要越权替猫做风格决定。\n")
                .append("- 【屏幕指挥】open_doc 可打开书库内容详情、rename_doc 改内容标题（详情页实时刷新）、open_file 打开工作区文件预览；主人说「打开/改名」时直接用，动作完成后不复述「已打开」。");
        return sb.toString();
    }

    /**
     * 工作区速览（固化进 SYSTEM 提示）：根 INDEX.md 摘要 + 两级目录结构（目录 + 文件/子目录计数，
     * 标注哪些目录有 INDEX.md 细账）。让喵开局就知道版图，省去每轮 list_files 摸底的轮次与 token。
     */
    private String workspaceBrief(Path root) {
        StringBuilder sb = new StringBuilder();
        // 1. 根 INDEX.md：喵自己维护的工作区索引（存在则注入摘要，不存在则布置维护义务）
        try {
            Path index = root.resolve("INDEX.md");
            if (Files.exists(index)) {
                String s = Files.readString(index, StandardCharsets.UTF_8).strip();
                if (!s.isEmpty()) {
                    if (s.length() > 1500) {
                        s = s.substring(0, 1500) + "\n…（INDEX.md 较长已截断，全文可 read_file INDEX.md）";
                    }
                    sb.append("《INDEX.md》当前内容：\n").append(s).append('\n');
                }
            } else {
                sb.append("（工作区还没有 INDEX.md——它是你维护的索引，请尽早用 write_file 建立。）\n");
            }
        } catch (Exception ignored) {
            // 索引读取失败不阻断装配
        }
        // 2. 两级目录结构：[文件数, 子目录数]，有 INDEX.md 的目录标注「细账在此」
        sb.append("目录结构（[文件数/子目录数]，标 ◈ 的目录有自己的 INDEX.md）：\n");
        try (Stream<Path> top = Files.list(root)) {
            List<Path> dirs = top.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.toString()))
                    .limit(40)
                    .toList();
            for (Path d : dirs) {
                long files = 0;
                int subDirs = 0;
                try (Stream<Path> s = Files.list(d)) {
                    List<Path> kids = s.toList();
                    files = kids.stream().filter(Files::isRegularFile).count();
                    subDirs = (int) kids.stream().filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith(".")).count();
                } catch (Exception ignored) {
                    // 单个目录读失败继续下一个
                }
                sb.append("- ").append(d.getFileName().toString()).append("/ [").append(files);
                if (subDirs > 0) sb.append("/").append(subDirs);
                sb.append("]").append(Files.exists(d.resolve("INDEX.md")) ? " ◈" : "").append('\n');
            }
        } catch (Exception e) {
            sb.append("（目录读取失败：").append(e.getMessage()).append("）\n");
        }
        return sb.toString();
    }
}
