package io.agentflow.approval.process;

import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.common.Actor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Flowable 参与者查询适配器，统一处理当前任务和历史任务。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableApplicationParticipantAdapter implements ApplicationParticipantPort {
    private final TaskService taskService;
    private final HistoryService historyService;

    /** 创建参与者查询适配器。 */
    public FlowableApplicationParticipantAdapter(TaskService taskService, HistoryService historyService) {
        this.taskService = taskService;
        this.historyService = historyService;
    }

    @Override
    public java.util.Set<String> readableNodes(String tenantId, String processInstanceId, Actor actor) {
        if (!tenantId.equals(actor.tenantId()) || !actor.hasRole("APPROVER")) return java.util.Set.of();
        var active = taskService.createTaskQuery().processInstanceId(processInstanceId).taskTenantId(tenantId)
                .active().includeIdentityLinks().list().stream()
                .filter(task -> actor.userId().equals(task.getAssignee()) || actor.userId().equals(task.getOwner())
                        || task.getAssignee() == null && task.getIdentityLinks().stream().anyMatch(link -> "candidate".equals(link.getType())
                            && (actor.userId().equals(link.getUserId()) || link.getGroupId() != null && actor.hasRole(link.getGroupId()))))
                .map(org.flowable.task.api.Task::getTaskDefinitionKey).collect(java.util.stream.Collectors.toSet());
        if (!active.isEmpty()) return java.util.Set.copyOf(active);
        return historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).taskTenantId(tenantId)
                .finished().taskAssignee(actor.userId()).list().stream()
                .map(org.flowable.task.api.history.HistoricTaskInstance::getTaskDefinitionKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isParticipant(String tenantId, UUID applicationId, Actor actor) {
        boolean activeParticipant = taskService.createTaskQuery().includeProcessVariables().includeIdentityLinks()
                .processVariableValueEquals("applicationId", applicationId.toString())
                .processVariableValueEquals("tenantId", tenantId).list().stream()
                .anyMatch(task -> actor.userId().equals(task.getAssignee())
                        || task.getIdentityLinks().stream().anyMatch(link -> actor.userId().equals(link.getUserId())
                        || (link.getGroupId() != null && actor.hasRole(link.getGroupId()))));
        if (activeParticipant) {
            return true;
        }
        return historyService.createHistoricTaskInstanceQuery()
                .processVariableValueEquals("applicationId", applicationId.toString())
                .processVariableValueEquals("tenantId", tenantId)
                .taskAssignee(actor.userId()).count() > 0;
    }
}
