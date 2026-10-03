package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.TaskAction;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.organization.ApprovalProxyUse;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    private final FlowableApprovalProxyAccess proxies;

    /** 候选事实来自运行引擎，组织资格仍由当前权威目录决定。 */
    public FlowableTaskAuthorization(TaskService tasks, TaskRecipientDirectory recipients, ApplicationRepository applications,
                                     FlowableApprovalResponsibilities responsibilities, FlowableApprovalProxyAccess proxies) {
        this.tasks = tasks; this.recipients = recipients; this.applications = applications; this.responsibilities = responsibilities;
        this.proxies = proxies;
    }

    /** 每次操作重新读取任务，锁等待后也必须重新授权。 */
    public Task require(String taskId, Actor actor) {
        Task task = requireActive(taskId, actor);
        if (!canAct(actor, task) || !responsibilities.allows(task, actor.userId())) {
            throw new DomainException("FORBIDDEN", "The task is not assigned to or available for the current user");
        }
        return task;
    }

    /** 读取可使用当前直接代理；决策写入必须通过 requireAction 在锁内重新授权。 */
    public Task requireReadable(String taskId, Actor actor) {
        Task task = requireActive(taskId, actor);
        if (!(canAct(actor, task) && responsibilities.allows(task, actor.userId()))
                && !proxies.forActor(actor, java.time.Instant.now()).canRead(task)) {
            throw new DomainException("FORBIDDEN", "The task is not available for the current user");
        }
        return task;
    }

    /** 申请锁之后重新读取；原生权利默认优先，显式代理失效时不得静默改用其他依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizedTask requireAction(String taskId, Actor actor, TaskAction action, UUID proxyId) {
        Task task = requireActive(taskId, actor);
        if (proxyId == null && canAct(actor, task) && responsibilities.allows(task, actor.userId())) {
            return new AuthorizedTask(task, null);
        }
        if (!FlowableApprovalProxyAccess.DECISIONS.contains(action)) {
            throw new DomainException("FORBIDDEN", "Approval proxies cannot change or delegate task responsibility");
        }
        UUID selected = proxyId;
        if (selected == null) {
            var options = proxies.forActor(actor, java.time.Instant.now()).options(task);
            if (options.isEmpty()) throw new DomainException("FORBIDDEN", "No approval proxy is available for this task");
            if (options.size() != 1) throw new DomainException("APPROVAL_PROXY_SELECTION_REQUIRED", "Select the original approver for this decision");
            selected = options.get(0).proxyId();
        }
        return new AuthorizedTask(task, proxies.lockForDecision(actor, task, selected));
    }

    /**
     * 当次事务中已复核的任务与代理依据；原生办理的 proxyUse 为空。
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizedTask(Task task, ApprovalProxyUse proxyUse) { }

    private Task requireActive(String taskId, Actor actor) {
        actor.requireRole("APPROVER");
        if (!recipients.eligible(actor.tenantId(), actor.userId())) throw new DomainException("FORBIDDEN", "Current organization approval eligibility is missing");
        Task task = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().includeIdentityLinks().singleResult();
        if (task == null || task.isSuspended() || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) {
            throw new DomainException("NOT_FOUND", "Active task not found");
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
