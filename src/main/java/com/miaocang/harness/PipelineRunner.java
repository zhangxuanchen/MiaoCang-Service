package com.miaocang.harness;

import java.util.function.Function;

/**
 * 流水线执行器：入参校验 → 执行 → 门禁 → 失败带原因自修重跑。
 * 防无限循环多层防护（禁止无界重试）：
 *   第 1 层 单阶段门禁重试 ≤ maxRetry（含首跑）
 *   第 2 层 重试耗尽走调用方降级 fallback（产物标注降级）
 *   第 3 层 任务级熔断：ctx.budget 递减，预算耗尽抛 IllegalStateException 终止任务
 *   第 4 层 体量护栏：执行前调用方负责材料截断（buildPrompt 前）；本类截产物超长字符串
 * 执行即销毁：exec 内部使用一次性会话，Runner 不持有 Agent 实例。
 */
public class PipelineRunner {

    public static final int DEFAULT_STAGE_RETRIES = 3; // 首跑 + 2 次自修
    public static final int DEFAULT_TASK_BUDGET = 12;  // 任务级熔断上限

    /** 执行一个阶段：exec 收 ctx 产出产物（内部一次性调用 LLM）；gate 不过则带原因重跑；耗尽走 fallback。budget 只在重跑时扣减（正常首跑不占任务预算） */
    public static <T> T runStage(HarnessSpec spec, HarnessRunContext ctx, Function<HarnessRunContext, T> exec,
                                 Function<HarnessRunContext, T> fallback) {
        GateResult in = spec.checkInput(ctx.inputs);
        if (!in.pass()) throw new IllegalArgumentException("阶段入参不合格: " + in.reasonText());

        int attempt = 0;
        T out = null;
        while (attempt < DEFAULT_STAGE_RETRIES) {
            attempt++;
            out = exec.apply(ctx);
            GateResult g = spec.getGate().check(out, ctx);
            ctx.gateResult = g;
            if (g.pass()) {
                ctx.output = out;
                ctx.note("✅ 校验通过（第 " + attempt + " 次尝试）", attempt);
                return out;
            }
            if (attempt >= DEFAULT_STAGE_RETRIES) break;
            /* 第 3 层：重跑前扣任务级预算，耗尽即熔断 */
            if (ctx.budget.getAndDecrement() <= 0) throw new IllegalStateException("任务熔断：重试总次数超上限，终止以免无限循环");
            ctx.note("⚠ 校验未过（第 " + attempt + " 次）：" + g.reasonText() + "，自修中…", attempt);
        }
        ctx.note("🛟 自修重试耗尽，走降级兜底", attempt);
        if (fallback == null) throw new IllegalStateException("阶段校验未过且无降级: " + ctx.work);
        T fb = fallback.apply(ctx);
        ctx.gateResult = GateResult.ok();
        ctx.output = fb;
        return fb;
    }
}
