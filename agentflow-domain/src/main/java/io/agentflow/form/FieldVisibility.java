package io.agentflow.form;

/**
 * 审批期间字段可见性与回填控制；脱敏不返回任何原值，隐藏不返回字段及其内容。
 * @author owlzhangfq@gmail.com
 */
public enum FieldVisibility {
    EDITABLE, READ_ONLY, MASKED, HIDDEN;

    /** 并行节点权限冲突时保留较严格的配置。 */
    public static FieldVisibility stricter(FieldVisibility left, FieldVisibility right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /** 可编辑与只读均允许查看明文值，脱敏与隐藏属于受限可见性。 */
    public boolean readable() {
        return this == EDITABLE || this == READ_ONLY;
    }
}
