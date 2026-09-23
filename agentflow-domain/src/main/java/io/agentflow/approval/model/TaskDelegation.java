package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.List;

/**
 * 任务委派状态值对象；自身状态决定可执行动作，不依赖引擎或人员目录。
 * @author owlzhangfq@gmail.com
 */
public record TaskDelegation(String owner, boolean pending) {
    /** 委派期间只能回交，最终决策留给原责任人。 */
    public void requireAction(TaskAction action) {
        if (pending && action != TaskAction.RESOLVE) {
            throw new DomainException("TASK_DELEGATION_PENDING", "The delegated task must be resolved before further decisions");
        }
        if (action == TaskAction.RESOLVE) {
            if (!pending) throw new DomainException("TASK_NOT_DELEGATED", "The task is not awaiting delegation resolution");
            if (owner == null || owner.isBlank()) {
                throw new DomainException("TASK_DELEGATION_OWNER_MISSING", "The delegation owner is missing; verify the original assignment");
            }
        }
    }

    /** 供已通过资源授权的当前处理人展示操作，不向其他角色扩张权限。 */
    public List<TaskAction> allowedActions(boolean assigned) {
        if (pending) return owner == null || owner.isBlank() ? List.of() : List.of(TaskAction.RESOLVE);
        return List.of(TaskAction.APPROVE, TaskAction.RETURN, TaskAction.REJECT, TaskAction.TRANSFER, TaskAction.DELEGATE,
                assigned ? TaskAction.RELEASE : TaskAction.CLAIM);
    }
}
