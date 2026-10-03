package io.agentflow.definition;

import io.agentflow.common.DomainException;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.ApprovalMode;

/**
 * 发布节点的审批规则；人数门槛按节点激活时的真实名单计算。
 * @author owlzhangfq@gmail.com
 */
public record ApprovalPolicy(ApprovalMode mode, Integer percentage) {
    public static final String MODE_PROPERTY = "approvalMode";
    public static final String PERCENTAGE_PROPERTY = "approvalPercentage";
    public static final int FULL_PERCENTAGE = 100;

    /** 百分比仅适用于比例会签，不允许残留参数悄悄改变其他方式。 */
    public ApprovalPolicy {
        if (mode == null) throw new DomainException("APPROVAL_MODE_INVALID", "Approval mode is required");
        if (mode == ApprovalMode.PERCENT) {
            if (percentage == null) throw new DomainException("APPROVAL_PERCENTAGE_REQUIRED", "Approval percentage is required");
            if (percentage < 1 || percentage > FULL_PERCENTAGE) throw invalidPercentage();
        } else if (percentage != null) {
            throw new DomainException("APPROVAL_PERCENTAGE_UNEXPECTED", "Approval percentage only applies to percentage countersign");
        }
    }

    /** 定义入口保留历史缺省方式，比例只接受 1 至 100 的规范整数字面量。 */
    public static ApprovalPolicy fromProperties(Map<String, String> properties) {
        ApprovalMode mode;
        try { mode = ApprovalMode.valueOf(properties.getOrDefault(MODE_PROPERTY, ApprovalMode.SINGLE.name())); }
        catch (IllegalArgumentException exception) { throw new DomainException("APPROVAL_MODE_INVALID", "Unsupported approval mode"); }
        String raw = properties.get(PERCENTAGE_PROPERTY);
        if (raw != null && mode != ApprovalMode.PERCENT) {
            throw new DomainException("APPROVAL_PERCENTAGE_UNEXPECTED", "Approval percentage only applies to percentage countersign");
        }
        if (raw != null && !raw.matches("[1-9][0-9]?|100")) throw invalidPercentage();
        return new ApprovalPolicy(mode, raw == null ? null : Integer.valueOf(raw));
    }

    /** 整数运算向上取整，不用浮点近似或截断降低所需人数。 */
    public int requiredApprovals(int members) {
        if (members < 1) throw new DomainException("COUNTERSIGN_NO_MEMBERS", "Approval requires at least one active member");
        return switch (mode) {
            case SINGLE, ANY -> 1;
            case ALL -> members;
            case PERCENT -> (int) (((long) members * percentage + FULL_PERCENTAGE - 1) / FULL_PERCENTAGE);
        };
    }

    /** 多人模式逐人创建任务，单人模式保留原候选组办理行为。 */
    public boolean multiInstance() { return mode != ApprovalMode.SINGLE; }

    private static DomainException invalidPercentage() {
        return new DomainException("APPROVAL_PERCENTAGE_INVALID", "Approval percentage must be an integer between 1 and 100");
    }
}
