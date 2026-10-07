package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.observability.DiagnosticContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(FlowableTaskAuthorization.class);
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
        if (!canAct(actor, task)) {
            throw new DomainException("FORBIDDEN", "The task is not assigned to or available for the current user");
        }
        traceAuthorized(task, actor);
        return task;
    }

    /** 读取可使用当前直接代理；决策写入必须通过 requireAction 在锁内重新授权。 */
    public Task requireReadable(String taskId, Actor actor) {
        Task task = requireActive(taskId, actor);
        if (!canAct(actor, task)
                && !proxies.forActor(actor, java.time.Instant.now()).canRead(task)) {
            throw new DomainException("FORBIDDEN", "The task is not available for the current user");
        }
        traceAuthorized(task, actor);
        return task;
    }

    /** 申请锁之后重新读取；原生权利默认优先，显式代理失效时不得静默改用其他依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizedTask requireAction(String taskId, Actor actor, TaskAction action, UUID proxyId) {
        Task task = requireActive(taskId, actor);
        if (proxyId == null && canAct(actor, task)) {
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

    private void traceAuthorized(Task task, Actor actor) {
        Object businessNo = task.getProcessVariables().get("businessNo");
        try (var scope = DiagnosticContext.forBusiness(actor.tenantId(), businessNo instanceof String value ? value : null,
                task.getProcessInstanceId(), task.getId()).open()) {
            LOG.info("Task access authorized, errorCode={}", "NONE");
        }
    }

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

    /** 列表和办理共用当前职责约束；并行候选创建后发生的实际决策也可能使原候选失权。 */
    public boolean canAct(Actor actor, Task task) {
        if (!actor.hasRole("APPROVER") || !actor.tenantId().equals(String.valueOf(task.getProcessVariables().get("tenantId")))) return false;
        if (actor.userId().equals(task.getAssignee())) return responsibilities.allows(task, actor.userId());
        if (task.getAssignee() != null) return false;
        return task.getIdentityLinks().stream().anyMatch(link -> actor.userId().equals(link.getUserId())
                || link.getGroupId() != null && actor.hasRole(link.getGroupId())) && responsibilities.allows(task, actor.userId());
    }

    /** 费用策略已展开为人员候选；分页查询在计数前排除失权原候选，不改写历史名单。 */
    public List<String> conflictingExpenseTaskIds(Actor actor) {
        return tasks.createTaskQuery().active().processVariableValueEquals("tenantId", actor.tenantId())
                .taskCandidateOrAssigned(actor.userId()).includeProcessVariables().includeIdentityLinks().list().stream()
                .filter(task -> task.getProcessVariables().containsKey(io.agentflow.expense.ExpenseSelfApprovalBindings.VARIABLE))
                .filter(task -> !canAct(actor, task)).map(Task::getId).toList();
    }

    /** 展示和执行共用原节点职责约束，不以组织资格代替职责分离。 */
    public List<String> allowedTargets(Task task, List<String> subjects) {
        return responsibilities.allowedTargets(task, subjects);
    }

    /** 被排除人员不能借责任变更取得新的待办。 */
    public void requireTargetAllowed(Task task, String subject) { responsibilities.requireAllowed(task, subject); }

    /** 会签成员读写使用同一本轮项目职责，历史或管理员身份不能改写它。 */
    public boolean fixedProjectMembers(Task task) { return responsibilities.fixedProjectMembers(task); }
}
