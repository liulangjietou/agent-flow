package io.agentflow.approval.process;

import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.common.DomainException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Flowable 运行时防腐层：只向审批域返回稳定的流程标识。 */
@Component
public class FlowableProcessRuntimeAdapter implements ProcessRuntimePort {
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    /** 注入 Flowable 运行服务。 */
    public FlowableProcessRuntimeAdapter(RepositoryService repositoryService, RuntimeService runtimeService,
                                         TaskService taskService) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    @Override
    @Transactional
    public StartedProcess start(StartProcessCommand command) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(command.processKey())
                .processDefinitionVersion((int) command.definitionVersion())
                .processDefinitionTenantId(command.tenantId())
                .singleResult();
        if (definition == null && "expense-reimbursement".equals(command.processKey())) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(command.processKey())
                    .processDefinitionVersion((int) command.definitionVersion())
                    .processDefinitionWithoutTenantId()
                    .singleResult();
        }
        if (definition == null) {
            throw new DomainException("PROCESS_DEFINITION_NOT_FOUND", "Published process definition is not available");
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("tenantId", command.tenantId());
        variables.put("applicationId", command.applicationId().toString());
        variables.put("businessNo", command.businessNo());
        variables.put("roundNo", command.roundNo());
        variables.put("formData", command.payload() == null ? Map.of() : Map.copyOf(command.payload()));
        org.flowable.engine.runtime.ProcessInstance instance = runtimeService
                .createProcessInstanceBuilder().processDefinitionId(definition.getId())
                .businessKey(command.businessNo()).tenantId(command.tenantId()).variables(variables).start();
        Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
        return new StartedProcess(instance.getId(), task == null ? null : task.getId());
    }

    @Override
    @Transactional
    public CompletedTask complete(CompleteTaskCommand command) {
        Task task = taskService.createTaskQuery().taskId(command.taskId()).singleResult();
        if (task == null) {
            throw new DomainException("NOT_FOUND", "Task not found");
        }
        if (command.comment() != null && !command.comment().isBlank()) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), command.comment());
        }
        taskService.complete(task.getId(), Map.of("lastAction", command.action()));
        boolean ended = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult() == null;
        return new CompletedTask(task.getId(), ended);
    }

    @Override
    @Transactional
    public void terminate(TerminateProcessCommand command) {
        org.flowable.engine.runtime.ProcessInstance instance = runtimeService
                .createProcessInstanceQuery().processInstanceId(command.processInstanceId())
                .processInstanceTenantId(command.tenantId()).singleResult();
        if (instance != null) {
            runtimeService.deleteProcessInstance(instance.getId(), command.reason());
        }
    }
}
