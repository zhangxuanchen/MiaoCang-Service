package com.miaocang.harness;

import java.util.List;

/** 门禁校验结果：通过 / 不通过（带可读失败原因，拼回上下文让模型自修） */
public record GateResult(boolean pass, List<String> reasons) {

    public static GateResult ok() { return new GateResult(true, List.of()); }

    public static GateResult fail(List<String> reasons) { return new GateResult(false, reasons); }

    public static GateResult fail(String reason) { return new GateResult(false, List.of(reason)); }

    public String reasonText() { return String.join("；", reasons); }
}
