package com.miaocang.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 五区上下文记忆法：每个阶段 Agent 的上下文只由五个区构成，不多不少——区外内容一律不进 prompt。
 * ① 目标区（spec.goal+出参标准）② 输入区（inputs）③ 规则区（skillsPrompt）④ 工作区（work，重跑时拼失败原因）⑤ 产物区（output）。
 * 本对象一次性使用：run() 结束即弃，编排器不跨阶段/跨运行持有（执行即销毁，状态在库不在会话）。
 */
public class HarnessRunContext {

    public final Long catId;
    public final String stage;

    /* ① 目标区 + ② 输入区 + ③ 规则区：启动前装配 */
    public final String goalPrompt;
    public final String inputs;
    public final String skillsPrompt;
    public final List<String> skillCodes = new ArrayList<>();

    /* ④ 工作区：本次运行中的中间信息（失败原因/自修提示），run 结束即清 */
    public final List<String> work = new ArrayList<>();

    /* ⑤ 产物区：执行后回填 */
    public Object output;
    public GateResult gateResult;

    /* 偏好捕获回写（带 stage + skillCodes 归因） */
    public final BiConsumer<String, String> feedbackSink;

    /* 进度文案回写（前端 current 可见，防护过程不黑箱） */
    public final BiConsumer<String, Integer> progressSink;

    /* 任务级熔断：全任务重试总次数预算（多阶段共享同一 AtomicInteger，正常首跑不扣） */
    public final java.util.concurrent.atomic.AtomicInteger budget;

    public HarnessRunContext(Long catId, String stage, String goalPrompt, String inputs, String skillsPrompt,
                             BiConsumer<String, String> feedbackSink, BiConsumer<String, Integer> progressSink,
                             java.util.concurrent.atomic.AtomicInteger budget) {
        this.catId = catId;
        this.stage = stage;
        this.goalPrompt = goalPrompt;
        this.inputs = inputs;
        this.skillsPrompt = skillsPrompt;
        this.feedbackSink = feedbackSink;
        this.progressSink = progressSink;
        this.budget = budget;
    }

    /** 五区拼装为一次 chat 的 user prompt（系统提示由调用方给角色，目标区/规则区拼在正文前） */
    public String buildPrompt(String taskBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("【目标】").append(goalPrompt).append('\n');
        if (!skillsPrompt.isBlank()) sb.append("【规则与主人偏好】").append(skillsPrompt).append('\n');
        if (!work.isEmpty()) {
            sb.append("【上一次尝试的问题】").append(String.join("；", work)).append('\n');
        }
        sb.append("【材料】\n").append(inputs).append("\n\n");
        sb.append("【任务】").append(taskBody);
        return sb.toString();
    }

    /** 记录工作区并回写进度（第 n 次尝试可见） */
    public void note(String message, int attempt) {
        work.add(message);
        if (progressSink != null) progressSink.accept(message, attempt);
    }
}
