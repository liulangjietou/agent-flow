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
import java.util.List;

/**
 * 通用审批与财务任务操作共用的实时任务授权，不能以历史参与或管理员身份代替当前候选关系。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableTaskAuthorization {
    private final TaskService tasks;
    private final TaskRecipientDirectory recipients;
    private final ApplicationRepository applications;
    private final FlowableApprovalResponsibilities responsibilities;

    /** 候选事实来自运行引擎，组织资格仍由当前权威目录决定。 */
    public FlowableTaskAuthorization(TaskService tasks, TaskRecipientDirectory recipients, ApplicationRepository applications,
                                     FlowableApprovalResponsibilities responsibilities) {
        this.tasks = tasks; this.recipients = recipients; this.applications = applications; this.responsibilities = responsibilities;
    }

    /** 每次操作重新读取任务，锁等待后也必须重新授权。 */
    public Task require(String taskId, Actor actor) {
        actor.requireRole("APPROVER");
        if (!recipients.eligible(actor.tenantId(), actor.userId())) throw new DomainException("FORBIDDEN", "Current organization approval eligibility is missing");
        Task task = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().includeIdentityLinks().singleResult();
        if (task == null || task.isSuspended() || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) {
            throw new DomainException("NOT_FOUND", "Active task not found");
        }
        if (!canAct(actor, task) || !responsibilities.allows(task, actor.userId())) {
            throw new DomainException("FORBIDDEN", "The task is not assigned to or available for the current user");
        }
        return task;
    }

    /** 申请标识只读取引擎受控变量，不接受客户端关联。 */
    public Application application(Actor actor, Task task) {
        try {
            UUID id = UUID.fromString(String.valueOf(task.getProcessVariables().get("applicationId")));
            return applications.findById(actor.tenantId(), id).orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        } catch (IllegalArgumentException invalid) { throw new DomainException("NOT_FOUND", "Application not found"); }
    }

    /** 列表复用已受职责约束的原生候选，不逐条查引擎变量；实际办理由 require 再复核。 */
    public boolean canAct(Actor actor, Task task) {
        if (!actor.hasRole("APPROVER") || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) return false;
        if (actor.userId().equals(task.getAssignee())) return true;
        if (task.getAssignee() != null) return false;
        return task.getIdentityLinks().stream().anyMatch(link -> actor.userId().equals(link.getUserId())
                || link.getGroupId() != null && actor.hasRole(link.getGroupId()));
    }

    /** 展示和执行共用原节点职责约束，不以组织资格代替职责分离。 */
    public List<String> allowedTargets(Task task, List<String> subjects) {
        return responsibilities.allowedTargets(task, subjects);
    }

    /** 被排除人员不能借责任变更取得新的待办。 */
    public void requireTargetAllowed(Task task, String subject) { responsibilities.requireAllowed(task, subject); }
}
