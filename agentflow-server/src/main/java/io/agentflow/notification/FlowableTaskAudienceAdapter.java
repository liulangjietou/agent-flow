package io.agentflow.notification;

import io.agentflow.approval.service.TaskRecipientDirectory;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 仅从当前申请实际任务解析接收人；已指派任务不再向候选组广播。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableTaskAudienceAdapter implements TaskAudiencePort {
    private final TaskService tasks;
    private final TaskRecipientDirectory directory;

    /** 注入引擎防腐依赖与同源身份目录。 */
    public FlowableTaskAudienceAdapter(TaskService tasks, TaskRecipientDirectory directory) {
        this.tasks = tasks;
        this.directory = directory;
    }

    @Override
    public List<Audience> pending(String tenantId, UUID applicationId) {
        return tasks.createTaskQuery().active().processVariableValueEquals("applicationId", applicationId.toString())
                .includeProcessVariables().includeIdentityLinks().list().stream()
                .filter(task -> tenantId.equals(task.getProcessVariables().get("tenantId")))
                .map(task -> {
                    Set<String> users = new HashSet<>(), roles = new HashSet<>();
                    if (task.getAssignee() != null) users.add(task.getAssignee());
                    else task.getIdentityLinks().stream().filter(link -> "candidate".equals(link.getType())).forEach(link -> {
                        if (link.getUserId() != null) users.add(link.getUserId());
                        if (link.getGroupId() != null) roles.add(link.getGroupId());
                    });
                    return new Audience(task.getId(), task.getName(), directory.members(tenantId, users, roles));
                }).toList();
    }
}
