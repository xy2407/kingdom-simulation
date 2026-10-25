package com.xy2407.kingdom.common.employment;

/**
 * 雇佣结果：成功或带原因的失败，供面板逐条提示
 */
public record EmploymentResult(boolean success, String reason) {

    public static EmploymentResult ok() {
        return new EmploymentResult(true, "");
    }

    public static EmploymentResult fail(String reason) {
        return new EmploymentResult(false, reason == null ? "雇佣失败" : reason);
    }
}
