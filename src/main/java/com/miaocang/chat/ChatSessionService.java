package com.miaocang.chat;

import com.miaocang.entity.Cat;
import com.miaocang.repository.CatRepository;
import com.miaocang.service.MiaomiaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 每猫会话区编排：把 SSE 连接、单轮执行器、会话记忆、工作区目录串起来。
 *
 * <p>一轮对话的生命周期：
 * <ol>
 *   <li>解析猫 → workspacePath（"cat-{id}"，缺则回填）→ ensure 四个子目录就绪</li>
 *   <li>memKey = {workspacePath}/miaomiao/{sessionId}，记忆按猫 + 会话隔离</li>
 *   <li>真实模型 → {@link SingleTurnExecutor#runTurn}（事件流经 {@link SseEventSink} 转 SSE）；
 *       mock 模式 → 直接流式吐模拟回复并照常落盘记忆，保证四层压缩链路可演示</li>
 *   <li>收尾发 done / error 事件并 complete</li>
 * </ol>
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** 管理员模式约定：catId=0 = 「Wiki 管理员」，不对应任何一只猫。 */
    public static final long MASTER_CAT_ID = 0L;

    private final CatRepository catRepo;
    private final CatWorkspaceService catWorkspace;
    private final ConversationMemory memory;
    private final SingleTurnExecutor executor;
    private final MiaomiaoService miaomiao;
    private final ChatProperties props;
    private final MiaoMiaoAgentFactory agentFactory;
    private final com.miaocang.service.StudySearchService searchService;
    /** 喵事件落库（喵的日记数据源）：live 走 AgentEventRecorder，mock 在这里直报。 */
    private final com.miaocang.service.CatEventService eventService;
    /** laya「System 1」意图快路由（侧车不可用时全部方法返回 null / false，自动走原路径）。 */
    private final com.miaocang.laya.LayaIntentRouter layaIntent;

    public ChatSessionService(CatRepository catRepo, CatWorkspaceService catWorkspace,
                              ConversationMemory memory, SingleTurnExecutor executor,
                              MiaomiaoService miaomiao, ChatProperties props,
                              MiaoMiaoAgentFactory agentFactory,
                              com.miaocang.service.StudySearchService searchService,
                              com.miaocang.service.CatEventService eventService,
                              com.miaocang.laya.LayaIntentRouter layaIntent) {
        this.catRepo = catRepo;
        this.catWorkspace = catWorkspace;
        this.memory = memory;
        this.executor = executor;
        this.miaomiao = miaomiao;
        this.props = props;
        this.agentFactory = agentFactory;
        this.searchService = searchService;
        this.eventService = eventService;
        this.layaIntent = layaIntent;
    }

    /** 发起一轮对话，返回绑定了超时的 SseEmitter（Controller 直接回给前端）。catId=0 时为 Wiki 管理员（全库权限）。
     *  pageContext = 主人当前所在页面的描述（前端传），让喵结合用户正在看的页面回答。 */
    public SseEmitter stream(Long catId, String sessionId, String message, String pageContext) {
        boolean master = isMaster(catId);
        Cat cat = master ? null : cat(catId);
        String workspacePath = master ? masterWorkspace() : workspacePathOf(cat);
        catWorkspace.ensure(workspacePath);
        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 12) : sessionId;
        String memKey = ConversationMemory.key(workspacePath, "miaomiao", sid);
        String page = pageContext == null || pageContext.isBlank() ? "书房内" : pageContext.strip();
        if (master) {
            log.info("[会话区] Wiki管理员（{}）会话 {} 收到消息（页面: {}），{} 字", workspacePath, sid, page, message.length());
        } else {
            log.info("[会话区] 猫 {}（{}）会话 {} 收到消息（页面: {}），{} 字", cat.getName(), workspacePath, sid, page, message.length());
        }

        SseEmitter emitter = new SseEmitter(props.getTimeoutMs());
        boolean live = miaomiao.liveMode(master ? null : catId);

        /* System 1 意图快路由：与下面的领地检索**并发**发起，稍后有界等待（≤ intent.timeout-ms）。
           超时/失败一律返回 null，这一轮照原路径走 —— 快路由是加速器，不是必经之路。 */
        java.util.concurrent.CompletableFuture<com.miaocang.laya.LayaIntentRouter.IntentResult> intentFuture =
                live ? layaIntent.classifyAsync(message, page) : null;

        /* RAG：请求线程里先检索领地（子线程取不到请求上下文），把命中知识片段随本轮消息注入。
           单喵 = 搜该喵；Wiki管理员 = 搜当前用户全部喵的领地。空命中不注入。 */
        String ragContext = buildRagContext(master ? null : catId, message);

        if (live) {
            com.miaocang.laya.LayaIntentRouter.IntentResult intent = layaIntent.join(intentFuture);
            /* 短路：确定要打开某个真实存在的文件 → 直接发协议串让前端开文件，不构建 Agent。
               只有「只读 open_file + 确定度达标 + Java 侧能唯一定位到文件」三条同时成立才走。 */
            if (layaIntent.shortCircuitEligible(intent)) {
                String rel = com.miaocang.laya.LayaIntentRouter.matchFile(message, filePaths(catId));
                if (rel != null) {
                    log.info("[会话区] System 1 短路：直接打开 {}（certain={}），未构建 Agent",
                            rel, intent.certain());
                    openFileShortCircuit(emitter, sid, memKey, rel, message);
                    return emitter;
                }
            }
            SingleTurnExecutor.TurnSpec spec = new SingleTurnExecutor.TurnSpec(
                    catId, workspacePath, sid, message, page, ragContext,
                    layaIntent.hintBlock(intent),
                    Duration.ofMillis(props.getTimeoutMs()),
                    () -> send(emitter, "ping", String.valueOf(System.currentTimeMillis())));
            try {
                executor.runTurn(spec, new SseEventSink(emitter), new SingleTurnExecutor.TurnListener() {
                    @Override
                    public void onDone(SingleTurnExecutor.TurnResult result) {
                        send(emitter, "done", Map.of("sessionId", sid,
                                "assistant", result.assistantText(), "mock", false));
                        complete(emitter);
                    }

                    @Override
                    public void onError(Throwable t) {
                        send(emitter, "error", String.valueOf(t.getMessage() == null ? t : t.getMessage()));
                        complete(emitter);
                    }

                    @Override
                    public void onTimeout() {
                        send(emitter, "error", "本轮响应超时（" + (props.getTimeoutMs() / 1000) + " 秒），已中断");
                        complete(emitter);
                    }
                });
            } catch (Exception e) {
                send(emitter, "error", "会话区启动失败: " + e.getMessage());
                complete(emitter);
            }
            return emitter;
        }

        // mock 模式：不走 agentscope，直接流式吐模拟回复（记忆照常落盘，可演示压缩链路）
        runMockTurn(emitter, master, cat, workspacePath, sid, memKey, message, page);
        return emitter;
    }

    /** 工作区里的文件相对路径清单（不含目录）：System 1 短路时用来唯一定位主人提到的那份文件。 */
    private List<String> filePaths(Long catId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : listFiles(catId)) {
            if (Boolean.TRUE.equals(f.get("dir"))) continue;
            out.add(String.valueOf(f.get("path")));
        }
        return out;
    }

    /**
     * System 1 短路：主人明确要打开某份工作区文件时，直接按既有 tool/toolresult 协议告诉前端开文件
     * （前端 parseWsAction → openWsFile 链路零改动，文件内容仍由前端另行 GET /file 取），
     * 再落一次会话记忆并收尾。全程不构建 Agent，省下数秒 ReAct 启动。
     */
    private void openFileShortCircuit(SseEmitter emitter, String sid, String memKey,
                                      String relPath, String message) {
        String reply = "喵～已经替你翻开《" + relPath + "》了（这一步是快速路由，没惊动大脑）";
        try {
            send(emitter, "tool", Map.of("name", "read_file",
                    "args", "{\"path\":\"" + relPath + "\"}"));
            send(emitter, "toolresult", com.miaocang.laya.LayaIntentRouter.openFileToolResult(relPath));
            send(emitter, "token", reply);
            memory.recordTool(memKey, reply.length());
            memory.append(memKey, message, reply, null);
            send(emitter, "done", Map.of("sessionId", sid, "assistant", reply, "mock", false));
        } catch (Exception e) {
            log.warn("[会话区] System 1 短路失败（已降级为普通回复）: {}", e.toString());
            send(emitter, "error", "打开文件失败: " + e.getMessage());
        } finally {
            complete(emitter);
        }
    }

    /**
     * mock 演示「操作工作区文件目录」：把本轮问答追加进工作区会话笔记并按协议发 tool/toolresult 事件，
     * 前端文件树实时刷新（新文件出现/预览重载），反馈链路与 live 模式一致。失败静默不阻断。
     */
    private void mockDemoFileAction(SseEmitter emitter, String workspacePath, String message, String reply) {
        try {
            Path root = catWorkspace.workspaceRoot(workspacePath);
            Path note = root.resolve("miaomiao").resolve("会话笔记.md");
            Files.createDirectories(note.getParent());
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            String digest = (reply.length() > 30 ? reply.substring(0, 30) + "…" : reply).replace("\n", " ");
            String line = "- **" + stamp + "** 主人问：" + message.strip() + "\n  喵答：" + digest + "\n";
            Files.write(note, line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            send(emitter, "tool", Map.of("name", "write_file", "args", "{\"path\":\"miaomiao/会话笔记.md\"}"));
            send(emitter, "toolresult", Map.of("type", "file", "action", "write", "ok", true,
                    "path", "miaomiao/会话笔记.md", "message", "喵把本轮问答记进了 会话笔记.md（文件树已同步刷新）"));
        } catch (Exception e) {
            log.warn("[会话区] mock 会话笔记写入失败（不阻断）: {}", e.toString());
        }
    }

    /**
     * RAG 领地检索：拿主人这轮的消息去全文索引里搜（提问原样做 query，截 60 字防过长），
     * 命中拼成「领地检索命中」块交给模型引用；查不到 / 索引未就绪返回 null（不注入）。
     */
    private String buildRagContext(Long catId, String message) {
        try {
            String q = message.strip();
            if (q.length() > 60) q = q.substring(0, 60);
            List<java.util.Map<String, Object>> hits;
            if (catId == null) {
                /* Wiki管理员：搜当前用户全部喵的领地（请求线程，CurrentUser 可用） */
                Long uid = com.miaocang.auth.CurrentUser.get().getId();
                java.util.Set<Long> mine = catRepo.findByUserIdOrderByOrderIndexAscIdAsc(uid)
                        .stream().map(Cat::getId).collect(java.util.stream.Collectors.toSet());
                hits = searchService.search(mine, q, 5);
            } else {
                hits = searchService.search(catId, q, 4);
            }
            if (hits == null || hits.isEmpty()) return null;
            Map<String, String> typeLabel = Map.of("point", "知识点", "book", "书", "learn", "学习卡");
            StringBuilder sb = new StringBuilder("【领地检索命中】以下是从知识库全文索引里搜到的相关内容（回答时优先引用并标注《标题》，没覆盖到的再靠工作区文件或如实说明）：\n");
            int i = 0;
            for (Map<String, Object> h : hits) {
                String body = String.valueOf(h.getOrDefault("body", ""));
                if (body.length() > 400) body = body.substring(0, 400) + "…";
                /* 索引自带工作区相对路径（卡片按书分组在 points/{书}/ 下），喵可用 read_file 直接读全文 */
                String type = String.valueOf(h.get("type"));
                String path = h.get("path") == null ? null : String.valueOf(h.get("path"));
                sb.append("\n").append(++i).append(". 【").append(typeLabel.getOrDefault(type, "内容"))
                  .append("】《").append(h.get("title")).append("》（catId=").append(h.get("catId")).append("）");
                if (path != null && !path.isBlank()) sb.append("（工作区相对路径：").append(path).append("，需要全文可直接 read_file）");
                sb.append('\n').append(body.replace("```", "`` ")).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[会话区] RAG 领地检索跳过: {}", e.toString());
            return null;
        }
    }

    /** mock 模式单轮：分片流式吐模拟回复 → 落盘记忆 → done。 */
    private void runMockTurn(SseEmitter emitter, boolean master, Cat cat, String workspacePath,
                             String sid, String memKey, String message, String page) {
        Thread worker = new Thread(() -> {
            long startMs = System.currentTimeMillis();
            try {
                String reply = master
                        ? mockMasterReply(workspacePath, message, page)
                        : mockReply(cat, workspacePath, message, page);
                mockDemoFileAction(emitter, workspacePath, message, reply);
                for (int i = 0; i < reply.length(); i += 24) {
                    send(emitter, "token", reply.substring(i, Math.min(reply.length(), i + 24)));
                    Thread.sleep(30);
                }
                try {
                    memory.recordTool(memKey, 0);
                    memory.append(memKey, message, reply, null);
                } catch (Exception e) {
                    log.warn("[会话区] mock 记忆落盘失败（不阻断）: {}", e.toString());
                }
                mockReportEvents(workspacePath, sid, message, reply, startMs);
                send(emitter, "done", Map.of("sessionId", sid, "assistant", reply, "mock", true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                send(emitter, "error", String.valueOf(e.getMessage()));
            } finally {
                complete(emitter);
            }
        }, "cat-chat-mock");
        worker.setDaemon(true);
        worker.start();
    }

    /** mock 模式埋点：按原协议直报一条 text 事件 + 一条 Turn 主事件（layer=session），与 live 口径一致。 */
    private void mockReportEvents(String workspacePath, String sid, String message, String reply, long startMs) {
        if (eventService == null) return;
        try {
            String turnId = "turn-" + sid + "-" + System.nanoTime();
            Map<String, String> meta1 = new LinkedHashMap<>();
            meta1.put("turn_message_id", turnId);
            meta1.put("action_idx", "0");
            Map<String, Object> textEv = new LinkedHashMap<>();
            textEv.put("agentId", workspacePath);
            textEv.put("sessionId", sid);
            textEv.put("operation", "WRITE");
            textEv.put("layer", "text");
            textEv.put("memoryKey", "text:" + workspacePath);
            textEv.put("memorySummary", "内容：" + truncate(reply, 3000));
            textEv.put("timestamp", java.time.Instant.now().toString());
            textEv.put("metadata", meta1);
            eventService.report(textEv);

            long elapsed = System.currentTimeMillis() - startMs;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("turn_message_id", turnId);
            meta.put("turn_user", truncate(message, 500));
            meta.put("turn_outcome", truncate(reply, 2000));
            meta.put("action_count", "1");
            meta.put("turn_actions", "[\"" + truncate(reply, 120).replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
            meta.put("mock", "true");
            Map<String, Object> main = new LinkedHashMap<>();
            main.put("agentId", workspacePath);
            main.put("sessionId", sid);
            main.put("operation", "WRITE");
            main.put("layer", "session");
            main.put("memoryKey", "turn:" + sid);
            main.put("memorySummary", truncate(reply, 2000));
            main.put("tokenCount", reply.length() / 4);
            main.put("latencyMs", elapsed);
            main.put("timestamp", java.time.Instant.now().toString());
            main.put("metadata", meta);
            eventService.report(main);
        } catch (Exception e) {
            log.warn("[会话区] mock 埋点上报失败（不阻断）: {}", e.toString());
        }
    }

    /** 模拟回复：报家底（工作区文件清单）+ 提示配 AK；page 体现「看见主人所在页面」。 */
    private String mockReply(Cat cat, String workspacePath, String message, String page) {
        StringBuilder sb = new StringBuilder();
        sb.append("喵～（").append(cat.getName()).append("蹭了蹭你的手心）\n\n");
        sb.append("（我看到你现在正停在「").append(page).append("」页）\n\n");
        sb.append("【模拟输出】我还没有接入真实模型。在「喵喵设置」里给我配好 AK，\n");
        sb.append("我就能真的读懂并整理「").append(workspacePath).append("/」里的文档啦。\n\n");
        sb.append("目前我的专属工作区里有这些宝贝：\n");
        List<Map<String, Object>> files = listFiles(cat.getId());
        if (files.isEmpty()) {
            sb.append("（还是空的，一只文档都没有）\n");
        } else {
            int n = 0;
            for (Map<String, Object> f : files) {
                if (++n > 10) {
                    sb.append("…还有 ").append(files.size() - 10).append(" 个\n");
                    break;
                }
                sb.append("- ").append(f.get("path")).append("\n");
            }
        }
        sb.append("\n你刚才对我说：「").append(truncate(message, 80)).append("」");
        return sb.toString();
    }

    /** 管理员模拟回复：报全库家底（每只猫工作区概况 + 公共区）+ 提示配 AK；page 体现页面感知。 */
    private String mockMasterReply(String workspacePath, String message, String page) {
        String wsShow = workspacePath;
        StringBuilder sb = new StringBuilder();
        sb.append("喵～（Wiki管理员推了推眼镜，翻开总账本）\n\n");
        sb.append("（我看到你现在正停在「").append(page).append("」页）\n\n");
        sb.append("【模拟输出】我还没有接入真实模型。在「喵喵设置」里配好全局 AK，\n");
        sb.append("我就能替你操作整个书库（").append(wsShow).append("/）啦。\n\n");
        sb.append("目前馆里的家底：\n");
        /* 多用户隔离：管理员只盘点当前用户名下的喵（工作区位于该用户根目录之下） */
        for (Cat c : catRepo.findAll()) {
            String ws = c.getWorkspacePath() == null || c.getWorkspacePath().isBlank()
                    ? "cat-" + c.getId() : c.getWorkspacePath();
            if (!ws.startsWith(workspacePath + "/")) continue;
            int n = listFiles(c.getId()).size();
            sb.append("- ").append(c.getIcon() == null ? "🐱" : c.getIcon()).append(" ")
                    .append(c.getName()).append("（").append(ws).append("/，").append(n).append(" 个条目）\n");
        }
        sb.append("\n我的权限覆盖以上所有工作区 + 公共区（points/ 知识点、study/ 专题、reading/ 书骨架）。\n");
        sb.append("\n你刚才对我说：「").append(truncate(message, 80)).append("」");
        return sb.toString();
    }

    /* ---- 记忆面板 / 会话管理 ---- */

    /** 记忆面板数据：快照 + 折叠足迹 + 压缩日志 + 模式标记。 */
    public Map<String, Object> memoryState(Long catId, String sessionId) {
        boolean master = isMaster(catId);
        Cat cat = master ? null : cat(catId);
        String workspacePath = master ? masterWorkspace() : workspacePathOf(cat);
        String key = ConversationMemory.key(workspacePath, "miaomiao", sessionId);
        Map<String, Object> m = new LinkedHashMap<>();
        ConversationMemory.MemorySnapshot snap = memory.snapshot(key);
        m.put("snapshot", snap);
        m.put("foldTrail", memory.foldTrail(key));
        m.put("compressionLog", memory.compressionLog(key));
        m.put("live", miaomiao.liveMode(master ? null : catId));
        m.put("compressMarked", memory.isCompressMarked(key));
        m.put("workspacePath", workspacePath);
        m.put("catName", master ? "Wiki管理员" : cat.getName());
        // SYSTEM 区 = 每轮固定注入的系统提示（persona + 工作区说明）；TASK 区 = 会话记忆注入块
        m.put("systemChars", master
                ? agentFactory.masterSystemChars(workspacePath)
                : agentFactory.baseSystemPrompt(cat, workspacePath).length());
        String mc = memory.context(key);
        m.put("taskChars", mc == null ? 0 : mc.length());
        return m;
    }

    /** L4：清空某会话上下文（重新开始）。 */
    public Map<String, Object> clear(Long catId, String sessionId) {
        memory.clear(ConversationMemory.key(isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId)),
                "miaomiao", sessionId));
        return Map.of("ok", true);
    }

    /** 标记待压缩：下一轮对话开始时消费执行 L1→L4 自收敛。 */
    public Map<String, Object> markCompress(Long catId, String sessionId) {
        memory.markCompress(ConversationMemory.key(isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId)),
                "miaomiao", sessionId));
        return Map.of("ok", true);
    }

    /** 工作区文件清单（前端文件抽屉用；管理员 = 整个书库）。 */
    public List<Map<String, Object>> listFiles(Long catId) {
        boolean master = isMaster(catId);
        Cat cat = master ? null : cat(catId);
        String workspacePath = master ? masterWorkspace() : workspacePathOf(cat);
        catWorkspace.ensure(workspacePath);
        Path root = catWorkspace.workspaceRoot(workspacePath);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root))
                    .filter(p -> {
                        // 隐藏工作区内部目录（.context/.archive/.workbench）与临时文件
                        Path rel = root.relativize(p);
                        return rel.getNameCount() == 0
                                || !rel.getName(0).toString().startsWith(".")
                                        && !rel.getFileName().toString().endsWith(".tmp");
                    })
                    .sorted(Comparator.comparing(p -> p.toString()))
                    .limit(300)
                    .forEach(p -> {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("path", root.relativize(p).toString());
                        f.put("dir", Files.isDirectory(p));
                        try {
                            f.put("size", Files.isDirectory(p) ? 0L : Files.size(p));
                        } catch (IOException e) {
                            f.put("size", 0L);
                        }
                        out.add(f);
                    });
        } catch (IOException e) {
            log.warn("[会话区] 列出工作区文件失败: {}", e.toString());
        }
        return out;
    }

    /** 读工作区一个文本文件（前端预览用；管理员可读整个书库）。 */
    public Map<String, Object> readFile(Long catId, String relPath) {
        String workspacePath = isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId));
        Path root = catWorkspace.workspaceRoot(workspacePath);
        Path p = root.resolve(CatWorkspaceService.relativeToWorkspace(workspacePath, relPath)).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("路径越界");
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("文件不存在");
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            /* 截断的正文不能再写回去（会把原文截掉），truncated 让前端关掉编辑入口 */
            boolean truncated = s.length() > MAX_TEXT_FILE_CHARS;
            if (truncated) s = s.substring(0, MAX_TEXT_FILE_CHARS) + "\n…（已截断）";
            return Map.of("path", relPath, "content", s, "truncated", truncated);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
    }

    /** 文本文件在线预览（= 可编辑）的字符上限。 */
    private static final int MAX_TEXT_FILE_CHARS = 60_000;

    /**
     * 覆盖写回工作区里的一个文本文件（详情页「编辑」保存用）。
     * 路径守卫与 {@link #readFile} 同一口径；只改已存在的文件，不新建。
     */
    public Map<String, Object> writeFile(Long catId, String relPath, String content) {
        String workspacePath = isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId));
        Path root = catWorkspace.workspaceRoot(workspacePath);
        String rel = CatWorkspaceService.relativeToWorkspace(workspacePath, relPath);
        if (rel.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IllegalArgumentException("路径越界");
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("文件不存在");
        String text = content == null ? "" : content;
        try {
            /* 原子写入：先写临时文件再移动，避免写一半留下脏文件 */
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
            return Map.of("ok", true, "path", relPath, "size", text.length());
        } catch (IOException e) {
            throw new IllegalStateException("保存失败: " + e.getMessage(), e);
        }
    }

    /** 二进制文档预览上限：20MB。 */
    private static final long MAX_B64_FILE = 20L * 1024 * 1024;

    /**
     * 删除工作区里的一个文件或文件夹（文件树右键菜单）：路径守卫与 {@link #readFile} 同一口径。
     * 工作区根目录与内部隐藏目录（.context/.archive/.workbench 等记忆与工作台）不可删。
     */
    public void deleteWsEntry(Long catId, String relPath) {
        String workspacePath = isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId));
        Path root = catWorkspace.workspaceRoot(workspacePath);
        String rel = CatWorkspaceService.relativeToWorkspace(workspacePath, relPath);
        if (rel.isEmpty()) throw new IllegalArgumentException("工作区根目录不可删除");
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IllegalArgumentException("路径越界");
        if (root.relativize(p).getName(0).toString().startsWith(".")) {
            throw new IllegalArgumentException("工作区内部目录不可删除");
        }
        if (!Files.exists(p)) throw new IllegalArgumentException("文件不存在: " + rel);
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(t -> {
                        try {
                            Files.delete(t);
                        } catch (IOException e) {
                            throw new IllegalStateException("删除失败: " + t.getFileName(), e);
                        }
                    });
                }
            } else {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 预览工作区里的二进制文档（xlsx/docx 等压缩包格式）：base64 下发，
     * 前端 JSZip 解压渲染。路径守卫与 {@link #readFile} 同一口径。
     */
    public Map<String, Object> readFileBase64(Long catId, String relPath) {
        String workspacePath = isMaster(catId) ? masterWorkspace() : workspacePathOf(cat(catId));
        Path root = catWorkspace.workspaceRoot(workspacePath);
        Path p = root.resolve(CatWorkspaceService.relativeToWorkspace(workspacePath, relPath)).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("路径越界");
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("文件不存在");
        long size;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
        if (size > MAX_B64_FILE) {
            throw new IllegalArgumentException("文件超过 20MB，暂不支持在线预览");
        }
        try {
            byte[] data = Files.readAllBytes(p);
            return Map.of("path", relPath, "size", size,
                    "base64", java.util.Base64.getEncoder().encodeToString(data));
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + e.getMessage(), e);
        }
    }

    /* ---- 辅助 ---- */

    /** catId=0 = Wiki 管理员（全库权限），不对应任何真实猫。 */
    private boolean isMaster(Long catId) {
        return catId != null && catId == MASTER_CAT_ID;
    }

    /** 管理员工作区 = 当前登录用户的专属根目录（data/library/<username>/，其子目录是该用户养的喵）。 */
    private String masterWorkspace() {
        return com.miaocang.auth.CurrentUser.get().getUsername();
    }

    private Cat cat(Long catId) {
        return catRepo.findById(catId).orElseThrow(() -> new IllegalArgumentException("猫不存在: " + catId));
    }

    /** 猫的工作区相对路径；历史数据缺workspacePath时按 "<用户名>/cat-{id}" 兜底并回填。 */
    private String workspacePathOf(Cat cat) {
        if (cat.getWorkspacePath() == null || cat.getWorkspacePath().isBlank()) {
            cat.setWorkspacePath(masterWorkspace() + "/cat-" + cat.getId());
            catRepo.save(cat);
        }
        return cat.getWorkspacePath();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端已断开：忽略（主流程会在后续发送中自然结束）
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成的 emitter 再次 complete 会抛异常，忽略
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
