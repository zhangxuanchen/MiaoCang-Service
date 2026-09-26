package com.miaocang.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流阶段规格：一个节点 = 一份完整契约（工程宪法四件套，声明式，编排器按此执行与校验）。
 * goal / inputContract / outputContract / gate 缺一不可——生成物合格才能进下一阶段。
 */
public class HarnessSpec {

    /** 目标：本阶段为什么存在（一句话，拼进 system prompt 的目标区） */
    private final String goal;

    /** 出参标准：产物结构/格式/数量边界（拼进目标区，作为模型的验收依据） */
    private final String outputContract;

    /** 入参标准：可编程断言，输入不满足直接拒绝启动（不浪费一次 LLM 调用） */
    private final List<String> inputChecks;

    /** 生成物合格验证标准：一组可编程断言 */
    private final QualityGate gate;

    public HarnessSpec(String goal, String outputContract, List<String> inputChecks, QualityGate gate) {
        this.goal = goal;
        this.outputContract = outputContract;
        this.inputChecks = inputChecks == null ? new ArrayList<>() : inputChecks;
        this.gate = gate;
    }

    /** 入参校验：全部断言通过才允许启动阶段 */
    public GateResult checkInput(Object input) {
        List<String> fails = new ArrayList<>();
        for (String rule : inputChecks) {
            if (input == null || String.valueOf(input).isBlank()) fails.add("输入为空: " + rule);
        }
        return fails.isEmpty() ? GateResult.ok() : GateResult.fail(fails);
    }

    public String getGoal() { return goal; }
    public String getOutputContract() { return outputContract; }
    public QualityGate getGate() { return gate; }
}
