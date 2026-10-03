package io.agentflow.approval.process;

import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.common.Actor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Flowable 参与者查询适配器，统一处理当前任务和历史任务。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableApplicationParticipantAdapter implements ApplicationParticipantPort {
    private final TaskService taskService;
    private final HistoryService historyService;
    private final FlowableApprovalProxyAccess proxies;
    private final FlowableProxyParticipation proxyParticipation;

    /** 创建参与者查询适配器。 */
    public FlowableApplicationParticipantAdapter(TaskService taskService, HistoryService historyService, FlowableApprovalProxyAccess proxies,
                                                  FlowableProxyParticipation proxyParticipation) {
        this.taskService = taskService;
        this.historyService = historyService;
        this.proxies = proxies;
        this.proxyParticipation = proxyParticipation;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.Set<String> readableNodes(String tenantId, String processInstanceId, Actor actor) {
        if (!tenantId.equals(actor.tenantId()) || !actor.hasRole("APPROVER")) return java.util.Set.of();
        var scope = proxies.forActor(actor, java.time.Instant.now());
        // 暂停不会移除原任务参与事实；办理权限另由实时任务授权严格检查暂停状态。
        var active = taskService.createTaskQuery().processInstanceId(processInstanceId).taskTenantId(tenantId)
                .includeIdentityLinks().includeProcessVariables().list().stream()
                .filter(task -> actor.userId().equals(task.getAssignee()) || actor.userId().equals(task.getOwner())
                        || task.getAssignee() == null && task.getIdentityLinks().stream().anyMatch(link -> "candidate".equals(link.getType())
                            && (actor.userId().equals(link.getUserId()) || link.getGroupId() != null && actor.hasRole(link.getGroupId())))
                        || scope.canRead(task))
                .map(org.flowable.task.api.Task::getTaskDefinitionKey).collect(java.util.stream.Collectors.toSet());
        if (!active.isEmpty()) return java.util.Set.copyOf(active);
        var historical = new java.util.HashSet<>(proxyParticipation.nodes(tenantId, processInstanceId, actor.userId()));
        historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).taskTenantId(tenantId)
                .finished().taskAssignee(actor.userId()).list().stream()
                .map(org.flowable.task.api.history.HistoricTaskInstance::getTaskDefinitionKey).forEach(historical::add);
        return java.util.Set.copyOf(historical);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public boolean isParticipant(String tenantId, UUID applicationId, Actor actor) {
        if (!tenantId.equals(actor.tenantId())) return false;
        var scope = proxies.forActor(actor, java.time.Instant.now());
        boolean activeParticipant = taskService.createTaskQuery().includeProcessVariables().includeIdentityLinks()
                .processVariableValueEquals("applicationId", applicationId.toString())
                .processVariableValueEquals("tenantId", tenantId).list().stream()
                .anyMatch(task -> actor.userId().equals(task.getAssignee())
                        || task.getIdentityLinks().stream().anyMatch(link -> actor.userId().equals(link.getUserId())
                        || (link.getGroupId() != null && actor.hasRole(link.getGroupId()))) || scope.canRead(task));
        if (activeParticipant) {
            return true;
        }
        return historyService.createHistoricTaskInstanceQuery()
                .processVariableValueEquals("applicationId", applicationId.toString())
                .processVariableValueEquals("tenantId", tenantId)
                .taskAssignee(actor.userId()).count() > 0 || proxyParticipation.represented(tenantId, applicationId, actor.userId());
    }
}
