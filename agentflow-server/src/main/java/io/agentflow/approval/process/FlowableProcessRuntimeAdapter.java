package io.agentflow.approval.process;

import io.agentflow.event.EventContractBindings;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionInitiatorRequirements;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flowable 运行时防腐层：只向审批域返回稳定的流程标识。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableProcessRuntimeAdapter implements ProcessRuntimePort {
    private static final String BUNDLED_PROCESS_KEY = "expense-reimbursement";
    private static final long BUNDLED_PROCESS_VERSION = 1L;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final DefinitionDraftRepository platformDefinitions;
    private final JsonUtil json;
    private final EventContractBindings eventContracts;
    private final DefinitionInitiatorRequirements initiatorRequirements;
    public static final String INITIATOR_CONTEXT = "agentflowInitiatorContext";

    /** 注入 Flowable 运行服务。 */
    public FlowableProcessRuntimeAdapter(RepositoryService repositoryService, RuntimeService runtimeService,
                                         TaskService taskService, HistoryService historyService, DefinitionDraftRepository platformDefinitions,
                                         JsonUtil json, EventContractBindings eventContracts, DefinitionInitiatorRequirements initiatorRequirements) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.platformDefinitions = platformDefinitions;
        this.json = json;
        this.eventContracts = eventContracts;
        this.initiatorRequirements = initiatorRequirements;
    }

    /** 创建申请时严格解析指定来源，返回不透明定义标识。 */
    @Override
    public String resolveDefinition(String tenantId, String processKey, long definitionVersion, boolean bundled) {
        ProcessDefinition definition = findDefinition(tenantId, processKey, definitionVersion, bundled);
        requireDefinitionMatches(definition, tenantId, processKey, definitionVersion);
        return definition.getId();
    }

    @Override
    @Transactional
    public StartedProcess start(StartProcessCommand command) {
        ProcessDefinition definition = boundDefinition(command.definitionBinding());
        // 首提和重提均检查已绑定版本；先确认引擎来源，避免误停同名内置流程。
        if (command.tenantId().equals(definition.getTenantId())) {
            platformDefinitions.lockPublished(command.tenantId(), command.processKey(), command.definitionVersion())
                    .ifPresent(published -> {
                        published.requireStartEnabled();
                        eventContracts.requireAvailable(command.tenantId(), published.graph());
                        // 任职要求只取实际绑定的租户定义，同名新定义不能改变内置申请或旧轮次来源。
                        if (command.initiatorContext() == null && initiatorRequirements.required(command.tenantId(), published.graph())) {
                            throw new DomainException("INITIATOR_APPOINTMENT_REQUIRED", "Select an initiator appointment for this process");
                        }
                    });
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("tenantId", command.tenantId());
        variables.put("applicationId", command.applicationId().toString());
        variables.put("businessNo", command.businessNo());
        variables.put("roundNo", command.roundNo());
        if (command.initiatorContext() != null) variables.put(INITIATOR_CONTEXT, json.write(command.initiatorContext()));
        // 表单的显式 null 需要原样交给引擎，不能因不可变拷贝丢失清空语义。
        variables.put("formData", command.payload() == null ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(command.payload())));
        if (command.formSchema() != null) variables.put("formFieldTypes", command.formSchema().fieldTypes());
        org.flowable.engine.runtime.ProcessInstance instance = runtimeService
                .createProcessInstanceBuilder().processDefinitionId(definition.getId())
                .businessKey(command.businessNo()).tenantId(command.tenantId()).variables(variables).start();
        // 会签会同时产生多张待办；兼容端口只提供首个标识，不把它作为全部运行任务。
        List<Task> firstTasks = taskService.createTaskQuery().processInstanceId(instance.getId())
                .orderByTaskCreateTime().asc().orderByTaskId().asc().listPage(0, 1);
        return new StartedProcess(instance.getId(), firstTasks.isEmpty() ? null : firstTasks.get(0).getId());
    }

    /** 路由更新复查唯一活跃实例和实际轮次，保留原任务与历史提交快照。 */
    @Override
    @Transactional
    public void updateBusinessPayload(UpdateBusinessPayload command) {
        var instances = runtimeService.createProcessInstanceQuery().active()
                .variableValueEquals("tenantId", command.tenantId())
                .variableValueEquals("applicationId", command.applicationId().toString())
                .variableValueEquals("roundNo", command.roundNo()).listPage(0, 2);
        if (instances.size() != 1 || !instances.get(0).getId().equals(command.processInstanceId())) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Business adjustment requires the exact active submission instance");
        }
        requireTenantBinding(instances.get(0), command.tenantId());
        runtimeService.setVariable(command.processInstanceId(), "formData", Collections.unmodifiableMap(new HashMap<>(command.payload())));
    }

    /** 查询沿用启动时的精确来源规则；无租户的已知内置版本没有动态组织规则。 */
    @Override
    @Transactional(readOnly = true)
    public boolean requiresInitiatorAppointment(DefinitionBinding binding) {
        var definition = boundDefinition(binding);
        if (!binding.tenantId().equals(definition.getTenantId())) return false;
        var published = platformDefinitions.findPublished(binding.tenantId(), binding.processKey(), binding.definitionVersion())
                .orElseThrow(this::definitionUnavailable);
        return initiatorRequirements.required(binding.tenantId(), published.graph());
    }

    private ProcessDefinition boundDefinition(DefinitionBinding binding) {
        String definitionId = binding.runtimeDefinitionId();
        if (definitionId == null && binding.previousProcessInstanceId() != null) {
            // 旧申请只相信实际历史实例的租户、申请绑定与定义标识，不能改用当前同号版本。
            var previous = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(binding.previousProcessInstanceId())
                    .variableValueEquals("tenantId", binding.tenantId())
                    .variableValueEquals("applicationId", binding.applicationId().toString()).singleResult();
            if (previous == null || previous.getTenantId() != null && !previous.getTenantId().isEmpty()
                    && !binding.tenantId().equals(previous.getTenantId())) {
                throw definitionUnavailable();
            }
            definitionId = previous.getProcessDefinitionId();
        }
        ProcessDefinition definition;
        if (definitionId != null) {
            definition = repositoryService.createProcessDefinitionQuery().processDefinitionId(definitionId).singleResult();
        } else {
            ProcessDefinition tenantDefinition = findDefinition(binding.tenantId(), binding.processKey(), binding.definitionVersion(), false);
            ProcessDefinition bundledDefinition = isBundled(binding.processKey(), binding.definitionVersion())
                    ? findDefinition(binding.tenantId(), binding.processKey(), binding.definitionVersion(), true) : null;
            if (tenantDefinition != null && bundledDefinition != null) {
                throw new DomainException("DEFINITION_BINDING_AMBIGUOUS", "Legacy application has no saved definition source and multiple sources match");
            }
            definition = tenantDefinition == null ? bundledDefinition : tenantDefinition;
        }
        requireDefinitionMatches(definition, binding.tenantId(), binding.processKey(), binding.definitionVersion());
        return definition;
    }

    private ProcessDefinition findDefinition(String tenantId, String key, long version, boolean bundled) {
        if (version < 1 || version > Integer.MAX_VALUE || bundled && !isBundled(key, version)) throw definitionUnavailable();
        var query = repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).processDefinitionVersion((int) version);
        return (bundled ? query.processDefinitionWithoutTenantId() : query.processDefinitionTenantId(tenantId)).singleResult();
    }

    private void requireDefinitionMatches(ProcessDefinition definition, String tenantId, String key, long version) {
        if (definition == null || !key.equals(definition.getKey()) || version != definition.getVersion()) throw definitionUnavailable();
        String owner = definition.getTenantId();
        if (owner == null || owner.isEmpty()) {
            if (!isBundled(key, version)) throw definitionUnavailable();
        } else if (!tenantId.equals(owner)) throw definitionUnavailable();
    }

    private boolean isBundled(String key, long version) { return BUNDLED_PROCESS_KEY.equals(key) && version == BUNDLED_PROCESS_VERSION; }

    private DomainException definitionUnavailable() {
        return new DomainException("PROCESS_DEFINITION_NOT_FOUND", "The bound process definition is not available");
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
                .variableValueEquals("tenantId", command.tenantId()).singleResult();
        if (instance != null) {
            requireTenantBinding(instance, command.tenantId());
            runtimeService.deleteProcessInstance(instance.getId(), command.reason());
        }
    }

    @Override
    @Transactional
    public String withdraw(WithdrawProcessCommand command) {
        List<ProcessInstance> instances = runtimeService.createProcessInstanceQuery()
                .active()
                .variableValueEquals("tenantId", command.tenantId())
                .variableValueEquals("applicationId", command.applicationId().toString())
                .variableValueEquals("roundNo", command.roundNo()).listPage(0, 2);
        if (instances.size() != 1) {
            throw new DomainException("CONCURRENCY_CONFLICT", "The application round must have exactly one active process instance");
        }
        ProcessInstance instance = instances.get(0);
        requireTenantBinding(instance, command.tenantId());
        if (command.expectedProcessInstanceId() != null
                && !command.expectedProcessInstanceId().equals(instance.getId())) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round is bound to another process instance");
        }
        runtimeService.deleteProcessInstance(instance.getId(), command.reason());
        return instance.getId();
    }

    private void requireTenantBinding(ProcessInstance instance, String tenantId) {
        // 内置全局定义启动的旧实例没有引擎租户列，租户变量仍必须精确匹配；有引擎租户时再核对一致性。
        if (instance.getTenantId() != null && !instance.getTenantId().isEmpty()
                && !tenantId.equals(instance.getTenantId())) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Process instance tenant binding does not match the application");
        }
    }
}
