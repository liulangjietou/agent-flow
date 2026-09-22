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
