package com.miaocang.laya;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * laya 推理侧车的生命周期管家：**应用启动时把侧车一同带起，应用关闭时回收**。
 *
 * <p>侧车是 Python（模型前向绕不过 PyTorch），所以这里管的是一个子进程：
 * <ol>
 *   <li>先探 {@code /health}：已有健康侧车 → 直接挂上去（ATTACHED），不 spawn、退出时也不杀它
 *       —— 兼容 systemd 托管或开发时手动起的场景；</li>
 *   <li>环境缺失（无 venv / 无权重）且开了 {@code auto-prepare} → 跑 {@code bin/laya-setup.sh}；</li>
 *   <li>{@code ProcessBuilder} 拉起侧车，stdout/stderr 落 {@code data/logs/laya.log}；</li>
 *   <li>轮询 {@code /health} 直到 {@code loaded} 非空且含期望模型（只看 200 不够）；</li>
 *   <li>就绪后跑一次真实判定自检；</li>
 *   <li>{@code @PreDestroy} 时 SIGTERM → 3 秒 → 强杀，只回收自己 spawn 的进程。</li>
 * </ol>
 *
 * <p>全程在独立线程里做，**不阻塞应用启动**；任何失败都只让状态变 DOWN，业务链路静默降级。
 */
@Component
public class LayaSidecarManager {

    private static final Logger log = LoggerFactory.getLogger(LayaSidecarManager.class);

    public enum State { DISABLED, STARTING, ATTACHED, UP, DOWN }

    /** 环境准备（建 venv + 装 torch + 下权重）最坏可能几十分钟，给个上限防呆。 */
    private static final long PREPARE_TIMEOUT_MINUTES = 30;

    private final LayaProperties props;
    private final LayaDecisionClient client;
    private final LayaClassifyService classify;
    private final LayaIntentRouter intent;

    private volatile State state = State.DISABLED;
    private volatile String detail = "未启动";
    /** 只回收自己 spawn 的进程；挂到别人起的侧车上时保持 null。 */
    private volatile Process process;

    public LayaSidecarManager(LayaProperties props, LayaDecisionClient client,
                              LayaClassifyService classify, LayaIntentRouter intent) {
        this.props = props;
        this.client = client;
        this.classify = classify;
        this.intent = intent;
    }

    public State state() { return state; }
    public String detail() { return detail; }

    /** 侧车是否可用（两个接入点据此决定走 laya 还是原路径）。 */
    public boolean available() { return state == State.UP || state == State.ATTACHED; }

    // ---------------------------------------------------------------- 启动

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isEnabled()) {
            state = State.DISABLED;
            detail = "miaocang.laya.enabled=false";
            log.info("[laya] 已禁用（miaocang.laya.enabled=false），内容分类与会话意图走原有路径");
            return;
        }
        Thread t = new Thread(this::boot, "laya-sidecar-boot");
        t.setDaemon(true);
        t.start();
    }

    private void boot() {
        state = State.STARTING;
        detail = "启动中";
        long t0 = System.currentTimeMillis();
        try {
            if (probeExisting()) return;          // 已有健康侧车 → ATTACHED
            if (!props.isAutoStart()) {
                fail("miaocang.laya.auto-start=false 且未探到可用侧车（" + props.getBaseUrl() + "）");
                return;
            }
            if (!prepareEnvironment()) {
                fail("Python 环境未就绪；请手动执行 bin/laya-setup.sh");
                return;
            }
            spawn();
            if (!awaitReady(t0)) {
                // 探活失败就别留着这个可能卡在权重加载里的进程（几十 MB 到 2 GB 都可能）
                killSpawned();
                fail("侧车未在 " + props.getStartupTimeoutSeconds() + " 秒内就绪（看 " + props.getLogFile() + "）");
                return;
            }
            state = State.UP;
            detail = "就绪，耗时 " + (System.currentTimeMillis() - t0) / 1000 + "s";
            log.info("[laya] 侧车就绪 pid={} {} loaded={}（耗时 {}s）",
                    process == null ? "-" : process.pid(), props.getBaseUrl(),
                    safeLoaded(), (System.currentTimeMillis() - t0) / 1000);
            if (props.isSelfTest()) selfTest();
        } catch (Exception e) {
            fail("启动失败: " + e);
        }
    }

    /** 探已有侧车：健康且 loaded 非空就挂上去（并补齐期望的预加载模型）。 */
    private boolean probeExisting() {
        try {
            List<String> loaded = client.loadedModels(2_000L);
            if (loaded.isEmpty()) return false;
            state = State.ATTACHED;
            detail = "挂到已有侧车 " + props.getBaseUrl() + " loaded=" + loaded;
            log.info("[laya] 探到已有侧车 {}（loaded={}），不重复拉起、退出时也不回收",
                    props.getBaseUrl(), loaded);
            ensurePreloaded(loaded);
            return true;
        } catch (Exception e) {
            return false;                          // 没探到 → 走自己拉起
        }
    }

    /** 期望的预加载模型没常驻就补一次 preload（挂靠场景可能出现）。 */
    private void ensurePreloaded(List<String> loaded) {
        try {
            List<String> want = props.getPreload();
            if (want == null || want.isEmpty() || loaded.containsAll(want)) return;
            client.preload(want);
            log.info("[laya] 已补预加载 {} → {}", want, safeLoaded());
        } catch (Exception e) {
            log.warn("[laya] 补预加载失败（不阻断）: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 环境准备

    /** venv 可用 + 期望模型的本地权重目录完整，才算就绪。 */
    private boolean prepareEnvironment() {
        Path modelDir = root().resolve(props.getModelsDir())
                .resolve(props.getPreload().isEmpty() ? props.getModel() : props.getPreload().get(0));
        boolean venvOk = Files.isExecutable(root().resolve(props.getPython()));
        boolean weightsOk = Files.isRegularFile(modelDir.resolve("rl_agent_config.json"));
        if (venvOk && weightsOk) return true;
        if (!props.isAutoPrepare()) {
            log.warn("[laya] 环境缺失（venv={} 权重={}）且 auto-prepare=false", venvOk, weightsOk);
            return false;
        }
        log.info("[laya] 环境缺失（venv={} 权重={}），自动执行 bin/laya-setup.sh（首次可能较久）…",
                venvOk, weightsOk);
        Path setup = root().resolve("bin/laya-setup.sh");
        if (!Files.isExecutable(setup)) {
            log.warn("[laya] 找不到可执行脚本 {}", setup);
            return false;
        }
        Path out = root().resolve("data/logs/laya-setup.log");
        try {
            Files.createDirectories(out.getParent());
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", setup.toString());
            pb.directory(root().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
            Process p = pb.start();
            boolean done = p.waitFor(PREPARE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                log.warn("[laya] 环境准备超时（{} 分钟），详见 {}", PREPARE_TIMEOUT_MINUTES, out);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("[laya] 环境准备失败（exit={}），详见 {}", p.exitValue(), out);
                return false;
            }
            log.info("[laya] 环境准备完成（日志 {}）", out);
            return true;
        } catch (Exception e) {
            log.warn("[laya] 环境准备异常: {}", e.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------- 子进程

    private void spawn() throws IOException {
        Path script = root().resolve(props.getScript());
        if (!Files.isRegularFile(script)) {
            throw new IOException("找不到侧车脚本: " + script);
        }
        int port = props.getPort() > 0 ? props.getPort() : freePort();
        props.setBaseUrl("http://" + props.getHost() + ":" + port);

        Path logFile = root().resolve(props.getLogFile());
        Files.createDirectories(logFile.getParent());

        List<String> cmd = new java.util.ArrayList<>(List.of(
                pythonPath().toString(), script.toString(),
                "--host", props.getHost(), "--port", String.valueOf(port),
                "--device", props.getDevice(),
                "--preload", String.join(",", props.getPreload()),
                "--models-dir", root().resolve(props.getModelsDir()).toString()));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        // 侧车自己 import 仓库内的 vendored laya 包，不依赖机器上装过 laya
        pb.environment().put("PYTHONPATH", root().resolve("sidecar").toString());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("OMP_NUM_THREADS", String.valueOf(props.getCpuThreads()));
        pb.environment().put("HF_HOME", root().resolve("data/laya/hf").toString());
        // 权重已在本地目录里 → 禁止运行期联网核对/下载；缺权重时才允许联网取
        if (Files.isDirectory(root().resolve(props.getModelsDir()))) {
            pb.environment().put("HF_HUB_OFFLINE", "1");
        }
        process = pb.start();
        log.info("[laya] 已拉起侧车 pid={} {}（日志 {}）", process.pid(), String.join(" ", cmd), logFile);
    }

    /** 轮询 /health 直到 loaded 非空且含期望模型；进程中途退出直接判失败。 */
    private boolean awaitReady(long t0) {
        List<String> want = props.getPreload();
        long deadline = t0 + props.getStartupTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                log.warn("[laya] 侧车进程已退出（exit={}），详见 {}", process.exitValue(), props.getLogFile());
                return false;
            }
            try {
                List<String> loaded = client.loadedModels(props.getPollIntervalMs());
                if (!loaded.isEmpty() && (want == null || loaded.containsAll(want))) return true;
            } catch (Exception ignored) {
                // 未就绪/未监听，继续等
            }
            try {
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 自检

    /**
     * 就绪后跑一次真实判定（分类问题集 + 意图问题集）。
     * 既是端到端验收证据，也能立刻暴露「选项数超 head_max_len 导致 400」这类问题。
     */
    private void selfTest() {
        for (String line : new String[]{
                classify.selfTest(), intent.selfTest()}) {
            if (line != null) log.info("[laya] 自检 {}", line);
        }
    }

    // ---------------------------------------------------------------- 回收

    @PreDestroy
    public void shutdown() {
        killSpawned();
    }

    /** SIGTERM → 3 秒 → 强杀；只处理自己 spawn 的进程（ATTACHED 时为 null，不动别人的侧车）。 */
    private void killSpawned() {
        Process p = process;
        process = null;
        if (p == null || !p.isAlive()) return;
        log.info("[laya] 回收侧车 pid={} …", p.pid());
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                log.warn("[laya] 侧车 3 秒内未退出，强制结束 pid={}", p.pid());
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void fail(String reason) {
        state = State.DOWN;
        detail = reason;
        log.warn("[laya] {}（应用照常服务，内容分类与会话意图走原有路径）", reason);
    }

    private List<String> safeLoaded() {
        try {
            return client.loadedModels(2_000L);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 仓库根 = 进程工作目录（bin/mc.sh 与 LaunchAgent 都是先 cd 到仓库根再起 jar）。 */
    private static Path root() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** venv 解释器不存在时回退 python3（依赖缺失会在侧车日志里直接报出来）。 */
    private Path pythonPath() {
        Path configured = root().resolve(props.getPython());
        return Files.isExecutable(configured) ? configured : Paths.get("python3");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}