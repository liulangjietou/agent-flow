package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import io.agentflow.definition.ApprovalPolicy;
import io.agentflow.definition.DefinitionModels.ApprovalMode;
import java.util.Set;

/**
 * 会签的运行事实视图；固定审批责任不因转交或释放被替换。
 * @author owlzhangfq@gmail.com
 */
public record CountersignProgress(int total, int completed, ApprovalMode mode, Integer percentage, int required) {
    private static final Set<TaskAction> CHANGES_RESPONSIBILITY = Set.of(TaskAction.TRANSFER, TaskAction.RELEASE, TaskAction.CLAIM);

    /** 历史全员节点继续以当前实际责任人数作为门槛，包含已明确增减的责任。 */
    public CountersignProgress(int total, int completed) { this(total, completed, ApprovalMode.ALL, null, total); }

    /** 引擎事实与发布规则必须一致，取消任务不能伪装为完成数。 */
    public CountersignProgress {
        var policy = new ApprovalPolicy(mode, percentage);
        if (!policy.multiInstance() || total < 1 || completed < 0 || completed > total
                || required != policy.requiredApprovals(total)) {
            throw new DomainException("COUNTERSIGN_STATE_INVALID", "Countersign progress does not match the published approval policy");
        }
    }

    /** 会签成员保留最终决策权，允许委派协助后回交。 */
    public boolean allows(TaskAction action) { return !CHANGES_RESPONSIBILITY.contains(action); }

    /** 由执行入口强制检查，不能仅隐藏界面按钮。 */
    public void requireAction(TaskAction action) {
        if (!allows(action)) throw new DomainException("COUNTERSIGN_ASSIGNMENT_FIXED", "Countersign responsibility cannot be transferred or released");
    }
}
