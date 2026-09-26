package com.miaocang.laya;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * laya 决策引擎（内嵌推理侧车）配置（miaocang.laya.*）。
 * 与 application.properties 的 miaocang.laya 段一一对应。
 *
 * <p>纯配置 POJO，不承载行为；装配见 {@link LayaConfig}，生命周期见 {@link LayaSidecarManager}。
 */
@ConfigurationProperties(prefix = "miaocang.laya")
public class LayaProperties {

    /** 总开关。false 时侧车不拉起，两个接入点（内容分类 / 会话意图）完全走原有路径。 */
    private boolean enabled = true;

    /** 是否由本应用拉起侧车子进程。false = 只连已有侧车（如 systemd 托管 / 开发时手动起的）。 */
    private boolean autoStart = true;

    /** 环境缺失（无 venv / 无权重）时是否自动跑 bin/laya-setup.sh 准备。 */
    private boolean autoPrepare = true;

    /** Python 解释器：默认用仓库内 venv；不存在时回退 python3。 */
    private String python = "sidecar/.venv/bin/python";

    /** 侧车入口脚本（相对仓库根）。 */
    private String script = "sidecar/laya_server.py";

    /** 本地权重根目录：&lt;models-dir&gt;/&lt;name&gt; 存在即用本地目录加载。 */
    private String modelsDir = "data/laya/models";

    /** 侧车监听地址。侧车无鉴权，**必须保持 loopback**，不要改成 0.0.0.0。 */
    private String host = "127.0.0.1";

    /**
     * 侧车端口；0 = 自动挑一个空闲端口。
     * 默认 8777 刻意避开上游 {@code laya/java/backend/server.py} 的默认 8770——
     * 撞上就会被误判成「已有侧车」而挂上去，等于静默依赖了外部进程。
     */
    private int port = 8777;

    /** 推理设备：cpu / mps / cuda。 */
    private String device = "cpu";

    /** 注入侧车的 OMP_NUM_THREADS：torch 默认按物理核起满线程，会与 Spring 抢 CPU。 */
    private int cpuThreads = 2;

    /** 使用的 checkpoint。**默认固定 multilingual**（"auto" 才启用自动路由，见方案 §4.1）。 */
    private String model = "multilingual";

    /** 启动时预加载的 checkpoint；纯 CPU 中文场景只常驻 multilingual。 */
    private List<String> preload = List.of("multilingual");

    /** 侧车就绪探活超时（秒）：首次含权重加载，给足。 */
    private int startupTimeoutSeconds = 180;

    /** 探活轮询间隔（毫秒）。 */
    private int pollIntervalMs = 1000;

    /** 侧车 stdout/stderr 落盘位置。 */
    private String logFile = "data/logs/laya.log";

    /** 就绪后是否跑一次真实判定自检（把「侧车可用」变成日志里的事实）。 */
    private boolean selfTest = true;

    /** 运行时实际地址；由 {@link LayaSidecarManager} 选好端口后写入（含自动挑端口的场景）。 */
    private volatile String baseUrl;

    private final Classify classify = new Classify();
    private final Intent intent = new Intent();
    private final Placement placement = new Placement();

    /** 侧车真实地址：未拉起时按 host:port 拼。 */
    public String getBaseUrl() {
        String u = baseUrl;
        return u != null ? u : "http://" + host + ":" + port;
    }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    // ---------------------------------------------------------------- 内容自动分类打分
    public static class Classify {
        /** 单次判定超时（毫秒）。 */
        private long timeoutMs = 1500;
        /** 候选分类上限（预筛 top-K）：multilingual 的 head_max_len 只有 256，选项不能多。 */
        private int topK = 12;
        /** state 正文截断字符数（multilingual 状态区只剩 768 token，中文约 1 token/字）。 */
        private int stateMaxChars = 600;
        /** 归档门控：top1 概率下限。 */
        private double minProb = 0.45;
        /** 归档门控：top1 − top2 间隔下限（温度校准前不能用绝对置信度）。 */
        private double margin = 0.15;

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long v) { this.timeoutMs = v; }
        public int getTopK() { return topK; }
        public void setTopK(int v) { this.topK = v; }
        public int getStateMaxChars() { return stateMaxChars; }
        public void setStateMaxChars(int v) { this.stateMaxChars = v; }
        public double getMinProb() { return minProb; }
        public void setMinProb(double v) { this.minProb = v; }
        public double getMargin() { return margin; }
        public void setMargin(double v) { this.margin = v; }
    }

    // ---------------------------------------------------------------- 会话意图快路由
    public static class Intent {
        /** 单次判定超时（毫秒）；与领地检索并发，超时即放弃走原路径。 */
        private long timeoutMs = 800;
        /** 短路门控：certain（是否确定性指令）的下限。 */
        private double sureThreshold = 0.90;
        /** 未短路时是否把预判结果作为软提示注入提示词。 */
        private boolean hintEnabled = true;

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long v) { this.timeoutMs = v; }
        public double getSureThreshold() { return sureThreshold; }
        public void setSureThreshold(double v) { this.sureThreshold = v; }
        public boolean isHintEnabled() { return hintEnabled; }
        public void setHintEnabled(boolean v) { this.hintEnabled = v; }
    }

    // ---------------------------------------------------------------- 成书归位（更细一层的分类）
    /**
     * 成书阶段把理解卡片归入「二级主题（节）」的判定。这是比内容归档（{@link Classify}，粒度是书籍分类）
     * 更细一层的分类：候选 = 书骨架里已有的节，被分类对象 = 一张卡片。
     *
     * <p>为什么值得单独接一层：内容归档只回答「这篇属于哪个大分类」，成书要回答「这个知识点属于
     * 哪本书的哪一章的哪一节」，候选空间大得多、也更需要语义（节名往往只差一两个字）。
     */
    public static class Placement {
        /** 归位复核总开关。false = 完全走原有 LLM 归位，行为与接入前一致。 */
        private boolean enabled = true;

        /** 单卡判定超时（毫秒）。单卡实测 80~130ms，给 2 秒足够。 */
        private long timeoutMs = 2000;

        /** 本轮总预算（毫秒）：超了就停判，剩下的卡直接交给 LLM 兜底（不拖住整个整理任务）。 */
        private long budgetMs = 60_000;

        /** 候选节上限：multilingual 的选项头只有 256，选项太多会精度衰减且可能直接超长失败。 */
        private int maxOptions = 24;

        /** state 正文截断字符数（卡片的 topic + point）。 */
        private int stateMaxChars = 400;

        /**
         * 采用门控：top1 概率下限（本层的主门控）。
         *
         * <p>实测（54 张卡 / 8 个候选节）：判对的卡 p1 落在 0.41~0.998；而主题根本不在候选里的卡，
         * 模型仍会硬挑一个并给出 0.47 且 p2 只有 0.13 —— 这种「自信的错放」间隔门控拦不住，
         * 只能靠概率下限挡。0.5 在实测里正好把错放全挡在外面。宁可多让 LLM 兜底，
         * 也不让 laya 静默把卡放错位置（放错没人复核）。
         */
        private double minProb = 0.50;

        /** 采用门控：top1 − top2 间隔下限（温度校准前不能用绝对置信度）。 */
        private double margin = 0.20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long v) { this.timeoutMs = v; }
        public long getBudgetMs() { return budgetMs; }
        public void setBudgetMs(long v) { this.budgetMs = v; }
        public int getMaxOptions() { return maxOptions; }
        public void setMaxOptions(int v) { this.maxOptions = v; }
        public int getStateMaxChars() { return stateMaxChars; }
        public void setStateMaxChars(int v) { this.stateMaxChars = v; }
        public double getMinProb() { return minProb; }
        public void setMinProb(double v) { this.minProb = v; }
        public double getMargin() { return margin; }
        public void setMargin(double v) { this.margin = v; }
    }

    // ---------------------------------------------------------------- getters / setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

    public boolean isAutoPrepare() { return autoPrepare; }
    public void setAutoPrepare(boolean autoPrepare) { this.autoPrepare = autoPrepare; }

    public String getPython() { return python; }
    public void setPython(String python) { this.python = python; }

    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }

    public String getModelsDir() { return modelsDir; }
    public void setModelsDir(String modelsDir) { this.modelsDir = modelsDir; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }

    public int getCpuThreads() { return cpuThreads; }
    public void setCpuThreads(int cpuThreads) { this.cpuThreads = cpuThreads; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<String> getPreload() { return preload; }
    public void setPreload(List<String> preload) { this.preload = preload; }

    public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
    public void setStartupTimeoutSeconds(int v) { this.startupTimeoutSeconds = v; }

    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int v) { this.pollIntervalMs = v; }

    public String getLogFile() { return logFile; }
    public void setLogFile(String logFile) { this.logFile = logFile; }

    public boolean isSelfTest() { return selfTest; }
    public void setSelfTest(boolean selfTest) { this.selfTest = selfTest; }

    public Classify getClassify() { return classify; }
    public Intent getIntent() { return intent; }
    public Placement getPlacement() { return placement; }
}