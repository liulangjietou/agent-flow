package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * 通用审批与财务任务操作共用的实时任务授权，不能以历史参与或管理员身份代替当前候选关系。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableTaskAuthorization {
    private final TaskService tasks;
    private final TaskRecipientDirectory recipients;
    private final ApplicationRepository applications;

    /** 候选事实来自运行引擎，组织资格仍由当前权威目录决定。 */
    public FlowableTaskAuthorization(TaskService tasks, TaskRecipientDirectory recipients, ApplicationRepository applications) {
        this.tasks = tasks; this.recipients = recipients; this.applications = applications;
    }

    /** 每次操作重新读取任务，锁等待后也必须重新授权。 */
    public Task require(String taskId, Actor actor) {
        actor.requireRole("APPROVER");
        if (!recipients.eligible(actor.tenantId(), actor.userId())) throw new DomainException("FORBIDDEN", "Current organization approval eligibility is missing");
        Task task = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().includeIdentityLinks().singleResult();
        if (task == null || task.isSuspended() || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) {
            throw new DomainException("NOT_FOUND", "Active task not found");
        }
        if (!canAct(actor, task)) throw new DomainException("FORBIDDEN", "The task is not assigned to or available for the current user");
        return task;
    }

    /** 申请标识只读取引擎受控变量，不接受客户端关联。 */
    public Application application(Actor actor, Task task) {
        try {
            UUID id = UUID.fromString(String.valueOf(task.getProcessVariables().get("applicationId")));
            return applications.findById(actor.tenantId(), id).orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        } catch (IllegalArgumentException invalid) { throw new DomainException("NOT_FOUND", "Application not found"); }
    }

    /** 列表已批量读取身份链接，继续复用相同的租户与候选判断。 */
    public boolean canAct(Actor actor, Task task) {
        if (!actor.hasRole("APPROVER") || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) return false;
        if (actor.userId().equals(task.getAssignee())) return true;
        if (task.getAssignee() != null) return false;
        return task.getIdentityLinks().stream().anyMatch(link -> actor.userId().equals(link.getUserId())
                || link.getGroupId() != null && actor.hasRole(link.getGroupId()));
    }
}
