package com.miaocang.chat;

import com.miaocang.service.LibrarySyncService;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每猫工作区路径解析 + 状态存储缓存。
 *
 * <p>每只猫的专属工作区 = {@code <library.dir>/<cat.workspacePath>/}，下面挂三个子目录：
 * <ul>
 *   <li>{@code .context/} —— {@link JsonFileAgentStateStore} 的根，存会话状态 + 伴生文件</li>
 *   <li>{@code .archive/}  —— 折叠归档（已并入 .context 同结构，本字段保留兼容）</li>
 *   <li>{@code .workbench/} —— {@link io.agentscope.harness.agent.HarnessAgent} 的工作目录</li>
 * </ul>
 *
 * <p>{@link JsonFileAgentStateStore} 实例按 workspacePath 缓存：同一只猫多次会话共享同一 store，
 * 不同猫天然隔离（不同根目录）。store 的 root = workspaceRoot/.context。
 */
@Service
public class CatWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(CatWorkspaceService.class);

    private final LibrarySyncService librarySync;
    private final ChatProperties props;

    /** 每只猫的 AgentStateStore 缓存（key = workspacePath，例如 "cat-1"）。 */
    private final Map<String, AgentStateStore> stores = new ConcurrentHashMap<>();

    public CatWorkspaceService(LibrarySyncService librarySync, ChatProperties props) {
        this.librarySync = librarySync;
        this.props = props;
    }

    /** 该猫的工作区根目录：{@code <library.dir>/<workspacePath>/}。 */
    public Path workspaceRoot(String workspacePath) {
        return librarySync.resolveWorkspace(workspacePath);
    }

    /** 该猫的状态存储根：{@code workspaceRoot/.context/}。 */
    public Path contextRoot(String workspacePath) {
        return workspaceRoot(workspacePath).resolve(props.getContextDir());
    }

    /** 该猫的 sandbox 目录：{@code workspaceRoot/.workbench/}。 */
    public Path sandboxRoot(String workspacePath) {
        return workspaceRoot(workspacePath).resolve(props.getSandboxDir());
    }

    /**
     * 把「外层视角的路径」归一成工作区相对路径。
     *
     * <p>喵的读文件 / shell 视角看到的是整条书库路径，于是它常把
     * {@code data/library/admin/cat-1/origin/x.md} 当成工作区相对路径传出来。
     * 直接按工作区根解析会叠加成 {@code .../cat-1/data/library/...} 而误报「文件不存在」
     * （写文件更糟：会在工作区里长出一棵镜像目录树）。这里剥掉工作区路径及其之前的
     * 所有层级，含绝对路径前缀与 {@code ./}；剥错了只会查不到文件，越界防护仍由调用方兜底。
     */
    public static String relativeToWorkspace(String workspacePath, String raw) {
        if (raw == null) return "";
        String s = raw.strip().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        String ws = workspacePath == null ? "" : workspacePath.strip().replace('\\', '/');
        if (ws.isEmpty()) return s;
        if (s.startsWith("/")) {
            int at = s.indexOf("/" + ws + "/");
            return at >= 0 ? s.substring(at + ws.length() + 2) : s;
        }
        if (s.equals(ws)) return "";
        if (s.startsWith(ws + "/")) return s.substring(ws.length() + 1);
        int at = s.indexOf(ws + "/");
        return at > 0 ? s.substring(at + ws.length() + 1) : s;
    }

    /** 该猫的归档根：{@code workspaceRoot/.archive/}。 */
    public Path archiveRoot(String workspacePath) {
        return workspaceRoot(workspacePath).resolve(props.getArchiveDir());
    }

    /** 确保该猫工作区的所有子目录就绪（幂等）。 */
    public void ensure(String workspacePath) {
        try {
            Files.createDirectories(workspaceRoot(workspacePath));
            Files.createDirectories(contextRoot(workspacePath));
            Files.createDirectories(sandboxRoot(workspacePath));
            Files.createDirectories(archiveRoot(workspacePath));
        } catch (IOException e) {
            throw new IllegalStateException("猫工作区目录创建失败 (" + workspacePath + "): " + e.getMessage(), e);
        }
    }

    /** 取该猫的 AgentStateStore（按 workspacePath 缓存；首次调用时惰性创建）。 */
    public AgentStateStore stateStore(String workspacePath) {
        return stores.computeIfAbsent(workspacePath, k -> {
            ensure(k);
            Path root = contextRoot(k);
            log.info("[工作区] 为 {} 创建 AgentStateStore (root={})", k, root);
            return new JsonFileAgentStateStore(root);
        });
    }

    /** 该猫的会话隔离键前缀（与 ConversationMemory.key 拼接）。 */
    public static String memKeyPrefix(String workspacePath, String agentId) {
        return workspacePath + "/" + agentId;
    }
}
