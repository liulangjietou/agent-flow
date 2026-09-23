package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.Set;

/**
 * 会签的运行事实视图；固定审批责任不因转交或释放被替换。
 * @author owlzhangfq@gmail.com
 */
public record CountersignProgress(int total, int completed) {
    private static final Set<TaskAction> CHANGES_RESPONSIBILITY = Set.of(TaskAction.TRANSFER, TaskAction.RELEASE, TaskAction.CLAIM);

    /** 会签成员保留最终决策权，允许委派协助后回交。 */
    public boolean allows(TaskAction action) { return !CHANGES_RESPONSIBILITY.contains(action); }

    /** 由执行入口强制检查，不能仅隐藏界面按钮。 */
    public void requireAction(TaskAction action) {
        if (!allows(action)) throw new DomainException("COUNTERSIGN_ASSIGNMENT_FIXED", "Countersign responsibility cannot be transferred or released");
    }
}
