package com.miaocang.harness;

/** 生成物合格验证标准：一组可编程断言的宿主（每阶段实现自己的 check） */
@FunctionalInterface
public interface QualityGate {

    GateResult check(Object output, HarnessRunContext ctx);
}
