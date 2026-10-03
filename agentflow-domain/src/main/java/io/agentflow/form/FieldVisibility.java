package io.agentflow.form;

/**
 * 审批期间只读；脱敏不返回任何原值，隐藏不返回字段及其内容。
 * @author owlzhangfq@gmail.com
 */
public enum FieldVisibility {
    READ_ONLY, MASKED, HIDDEN;

    /** 并行节点权限冲突时保留较严格的配置。 */
    public static FieldVisibility stricter(FieldVisibility left, FieldVisibility right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
