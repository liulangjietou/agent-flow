package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.approval.model.TaskAction;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionModels.ApprovalMode;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.DraftStatus;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.expense.ExpenseProcessPolicy;
import io.agentflow.organization.ApprovalProxyRepository;
import io.agentflow.organization.ApprovalProxyRepository.ActiveProxy;
import io.agentflow.organization.ApprovalProxyUse;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 将直接代理与真实发布、申请轮次和原生责任相交；不伪造原审批人身份或继承其系统角色。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableApprovalProxyAccess {
    static final List<TaskAction> DECISIONS = List.of(TaskAction.APPROVE, TaskAction.RETURN, TaskAction.REJECT);
    private final ApprovalProxyRepository proxies;
    private final DefinitionDraftRepository definitions;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final RepositoryService engineDefinitions;
    private final TaskService tasks;
    private final HistoryService history;
    private final FlowableApprovalResponsibilities responsibilities;

    /** 组织提供限期与人员事实，防腐层负责比对引擎任务和当前业务范围。 */
    public FlowableApprovalProxyAccess(ApprovalProxyRepository proxies, DefinitionDraftRepository definitions,
            ApplicationRepository applications, SubmissionRoundRepository rounds, RepositoryService engineDefinitions,
            TaskService tasks, HistoryService history, FlowableApprovalResponsibilities responsibilities) {
        this.proxies = proxies;
        this.definitions = definitions;
        this.applications = applications;
        this.rounds = rounds;
        this.engineDefinitions = engineDefinitions;
        this.tasks = tasks;
        this.history = history;
        this.responsibilities = responsibilities;
    }

    /** 单次读取共用观察时刻；调用方在只读事务内使用，不把此快照缓存为后续办理授权。 */
    public ReadScope forActor(Actor actor, Instant observedAt) {
        return new ReadScope(actor, actor.hasRole("APPROVER") ? grants(actor.tenantId(), actor.userId(), observedAt) : List.of());
    }

    /** 仅判断最小提醒的本地范围，不推断身份源角色，也不能用于读取表单或办理任务。 */
    public boolean canNotify(String tenantId, String recipient, UUID proxyId, Task task, Instant observedAt) {
        return canNotify(tenantId, recipient, proxyId, task, observedAt, false);
    }

    /** 暂停任务仍可接收结束事实；该内部通知路径不开放暂停任务的读取或办理。 */
    boolean canNotifyUnfinished(String tenantId, String recipient, UUID proxyId, Task task, Instant observedAt) {
        return canNotify(tenantId, recipient, proxyId, task, observedAt, true);
    }

    private boolean canNotify(String tenantId, String recipient, UUID proxyId, Task task, Instant observedAt, boolean includeSuspended) {
        if (originalResponsibility(task, recipient)) return false;
        var selected = grants(tenantId, recipient, observedAt).stream()
                .filter(grant -> grant.active().proxy().id().equals(proxyId)).toList();
        return !eligibleGrants(tenantId, recipient, task, selected, includeSuspended).isEmpty();
    }

    private List<GrantScope> grants(String tenantId, String recipient, Instant observedAt) {
        var grants = new ArrayList<GrantScope>();
        for (var active : proxies.activeForSubstitute(tenantId, recipient, observedAt)) {
            var definition = definitions.findById(tenantId, active.proxy().definitionId()).orElse(null);
            if (definition == null || definition.status() != DraftStatus.PUBLISHED || definition.version() > Integer.MAX_VALUE) continue;
            var nativeDefinition = engineDefinitions.createProcessDefinitionQuery().processDefinitionTenantId(tenantId)
                    .processDefinitionKey(definition.key()).processDefinitionVersion((int) definition.version()).singleResult();
            if (nativeDefinition != null) grants.add(new GrantScope(active, definition, nativeDefinition.getId()));
        }
        return List.copyOf(grants);
    }

    /**
     * 调用方已经持有申请及关联财务锁，再与撤销共用代理行锁；等待后按新时刻重新检查全部读取依据。
     * 此路径不取得组织目录写锁，避免与管理员的目录锁、代理锁顺序形成环路。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalProxyUse lockForDecision(Actor actor, Task task, UUID proxyId) {
        var locked = proxies.lock(actor.tenantId(), proxyId).orElseThrow(FlowableApprovalProxyAccess::unavailable);
        Instant authorizedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var selected = forActor(actor, authorizedAt).options(task).stream()
                .filter(option -> option.proxyId().equals(locked.id()) && option.revision() == locked.revision())
                .findFirst().orElseThrow(FlowableApprovalProxyAccess::unavailable);
        return ApprovalProxyUse.authorized(locked, selected.principal(), authorizedAt);
    }

    private static DomainException unavailable() {
        return new DomainException("FORBIDDEN", "The selected approval proxy is no longer available for this task");
    }

    /**
     * 一次业务读取的代理依据；详情、列表与字段使用同一判定，不产生持久参与者身份。
     * @author owlzhangfq@gmail.com
     */
    public final class ReadScope {
        private final Actor actor;
        private final List<GrantScope> grants;

        private ReadScope(Actor actor, List<GrantScope> grants) {
            this.actor = actor;
            this.grants = grants;
        }

        /** 只接受已加载受控变量和身份链接的当前原生任务，委派协助不能再变为最终审批代理。 */
        public boolean canRead(Task task) {
            return !matching(task).isEmpty();
        }

        /** 候选依据只用于明确选择；编号与范围不能代替办理时的重新授权。 */
        public List<Option> options(Task task) {
            return matching(task).stream().map(grant -> {
                var proxy = grant.active().proxy();
                return new Option(proxy.id(), proxy.revision(), proxy.definitionId(), proxy.principalId(),
                        grant.active().principalSubject(), proxy.startsAt(), proxy.endsAt());
            }).toList();
        }

        private List<GrantScope> matching(Task task) {
            var matching = eligibleGrants(actor.tenantId(), actor.userId(), task, grants, false);
            if (matching.isEmpty()) return List.of();
            var node = matching.get(0).definition().graph().node(task.getTaskDefinitionKey());
            return ExpenseProcessPolicy.stage(node).finance() && !actor.hasRole("FINANCE") ? List.of() : matching;
        }

        /** 分页之前确定完整授权集合；按原审批人和精确原生版本查询，不扫描全租户任务。 */
        public List<String> taskIds() {
            var allowed = new TreeSet<String>();
            for (var grant : grants) {
                var candidates = tasks.createTaskQuery().active().processDefinitionId(grant.runtimeDefinitionId())
                        .processVariableValueEquals("tenantId", actor.tenantId())
                        .taskCandidateOrAssigned(grant.active().principalSubject()).includeProcessVariables().includeIdentityLinks().list();
                for (var task : candidates) if (canRead(task)) allowed.add(task.getId());
            }
            return List.copyOf(allowed);
        }

    }

    private List<GrantScope> eligibleGrants(String tenantId, String recipient, Task task, List<GrantScope> grants, boolean includeSuspended) {
        if (grants.isEmpty() || !includeSuspended && task.isSuspended() || task.getDelegationState() == DelegationState.PENDING
                || !tenantId.equals(task.getProcessVariables().get("tenantId"))
                || task.getTenantId() != null && !task.getTenantId().isEmpty() && !tenantId.equals(task.getTenantId())) return List.of();
        var matching = grants.stream().filter(grant -> grant.runtimeDefinitionId().equals(task.getProcessDefinitionId())
                && originalResponsibility(task, grant.active().principalSubject())).toList();
        if (matching.isEmpty()) return List.of();
        var application = currentApplication(tenantId, task);
        if (application == null || !responsibilities.allows(task, recipient)) return List.of();
        var definition = matching.get(0).definition();
        if (!definition.key().equals(application.processKey()) || definition.version() != application.definitionVersion()) return List.of();
        var node = definition.graph().node(task.getTaskDefinitionKey());
        if (node == null || node.type() != NodeType.USER_TASK) return List.of();
        return node.approvalMode() == ApprovalMode.SINGLE || independentCountersignParticipant(task, recipient) ? matching : List.of();
    }

    private Application currentApplication(String tenantId, Task task) {
        Object id = task.getProcessVariables().get("applicationId");
        if (!(id instanceof String text)) return null;
        UUID applicationId;
        try { applicationId = UUID.fromString(text); }
        catch (IllegalArgumentException invalid) { return null; }
        var application = applications.findById(tenantId, applicationId).orElse(null);
        if (application == null || application.status() != ApplicationStatus.IN_APPROVAL
                || !(task.getProcessVariables().get("roundNo") instanceof Number roundNo)
                || roundNo.longValue() != application.roundNo()
                || application.runtimeDefinitionId() != null && !application.runtimeDefinitionId().equals(task.getProcessDefinitionId())) return null;
        return rounds.findByRound(tenantId, application.id(), application.roundNo())
                .filter(round -> round.status() == SubmissionRound.Status.IN_APPROVAL
                        && round.definitionVersion() == application.definitionVersion()
                        && round.processInstanceId().equals(task.getProcessInstanceId()))
                .map(round -> application).orElse(null);
    }

    private boolean independentCountersignParticipant(Task task, String recipient) {
        // 同一人已有本节点责任（包括委派出去等待返回的责任）或已批准，不能再代理另一票。
        boolean ownPending = tasks.createTaskQuery().processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey()).list().stream()
                .anyMatch(pending -> recipient.equals(pending.getAssignee())
                        || recipient.equals(tasks.getVariable(pending.getId(), FlowableCountersignRuntime.USER)));
        if (ownPending) return false;
        return history.createHistoricTaskInstanceQuery().processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(task.getTaskDefinitionKey()).taskAssignee(recipient).finished().list().stream()
                .noneMatch(completed -> completed.getDeleteReason() == null);
    }

    private static boolean originalResponsibility(Task task, String principal) {
        if (task.getAssignee() != null) return principal.equals(task.getAssignee());
        // 原生候选必须明确列出该账号；本地目录不能证明任意 IdP 角色，更不能递归读取代理关系。
        return task.getIdentityLinks().stream().anyMatch(link -> "candidate".equals(link.getType()) && principal.equals(link.getUserId()));
    }

    /**
     * 同时保留组织授权和已发布原生版本，不靠同名流程或当前最新版本推断。
     * @author owlzhangfq@gmail.com
     */
    private record GrantScope(ActiveProxy active, DefinitionDraft definition, String runtimeDefinitionId) { }

    /**
     * 给实际审批人展示本任务可选的直接代理范围，不暴露管理员原因或其他代理关系。
     * @author owlzhangfq@gmail.com
     */
    public record Option(UUID proxyId, long revision, UUID definitionId, UUID principalId, String principal,
                          Instant startsAt, Instant endsAt) { }
}
