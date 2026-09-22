package io.agentflow.approval.history;

import io.agentflow.approval.model.SubmissionRound;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从精确系统变量定位引擎历史；结束节点仅表示节点离开，不代表业务批准。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableProcessHistoryAdapter implements ProcessHistoryPort {
    private final HistoryService history;

    /** 注入引擎历史服务。 */
    public FlowableProcessHistoryAdapter(HistoryService history) {
        this.history = history;
    }

    @Override
    public ProcessHistory read(String tenantId, UUID applicationId, List<SubmissionRound> rounds) {
        Map<Integer, SubmissionRound> byRound = rounds.stream().collect(Collectors.toMap(SubmissionRound::roundNo, Function.identity()));
        Map<String, SubmissionRound> byInstance = rounds.stream().collect(Collectors.toMap(SubmissionRound::processInstanceId, Function.identity()));
        List<HistoricProcessInstance> instances = history.createHistoricProcessInstanceQuery()
                .variableValueEquals("tenantId", tenantId).variableValueEquals("applicationId", applicationId.toString())
                .includeProcessVariables(List.of("tenantId", "applicationId", "roundNo")).list();
        List<HistoryEvent> events = new ArrayList<>();
        Map<String, ProcessBinding> processes = new LinkedHashMap<>();
        Map<String, TaskBinding> tasks = new LinkedHashMap<>();
        for (HistoricProcessInstance instance : instances) {
            Object rawRound = instance.getProcessVariables().get("roundNo");
            if (!matchesTenant(instance.getTenantId(), tenantId) || !(rawRound instanceof Number number)
                    || number.intValue() <= 0 || number.doubleValue() != number.intValue()) continue;
            int roundNo = number.intValue();
            Long definitionVersion = instance.getProcessDefinitionVersion() == null ? null : instance.getProcessDefinitionVersion().longValue();
            SubmissionRound round = byRound.get(roundNo);
            SubmissionRound boundInstance = byInstance.get(instance.getId());
            if (round != null && (!round.processInstanceId().equals(instance.getId())
                    || definitionVersion != null && round.definitionVersion() != definitionVersion)) continue;
            if (boundInstance != null && boundInstance.roundNo() != roundNo) continue;
            ProcessBinding binding = new ProcessBinding(instance.getId(), roundNo, definitionVersion);
            processes.put(instance.getId(), binding);
            history.createHistoricTaskInstanceQuery().processInstanceId(instance.getId()).list().stream()
                    .filter(task -> matchesTenant(task.getTenantId(), tenantId))
                    .forEach(task -> tasks.put(task.getId(), new TaskBinding(task.getId(), instance.getId(), roundNo,
                            definitionVersion, task.getTaskDefinitionKey(), task.getName())));
            for (HistoricActivityInstance activity : history.createHistoricActivityInstanceQuery().processInstanceId(instance.getId()).list()) {
                if (!matchesTenant(activity.getTenantId(), tenantId) || "sequenceFlow".equals(activity.getActivityType())) continue;
                if (activity.getStartTime() != null) events.add(nodeEvent(activity, binding, false));
                if (activity.getEndTime() != null) events.add(nodeEvent(activity, binding, true));
            }
        }
        return new ProcessHistory(List.copyOf(events), Map.copyOf(processes), Map.copyOf(tasks));
    }

    private HistoryEvent nodeEvent(HistoricActivityInstance activity, ProcessBinding binding, boolean ended) {
        String nodeType = switch (activity.getActivityType()) {
            case "startEvent" -> "START";
            case "userTask" -> "USER_TASK";
            case "exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway" -> "GATEWAY";
            case "endEvent" -> "END";
            default -> activity.getActivityType();
        };
        // 同一活动若在同一毫秒开始并结束，以阶段前缀保持进入在前，游标使用完全相同的稳定标识。
        return new HistoryEvent("process:" + activity.getId() + (ended ? ":1-ended" : ":0-started"), 0,
                (ended ? activity.getEndTime() : activity.getStartTime()).toInstant(), HistoryEvent.Source.PROCESS_HISTORY,
                ended ? "NODE_ENDED" : "NODE_STARTED", null, binding.roundNo(), null, null, null,
                activity.getActivityId(), activity.getActivityName(), nodeType, activity.getTaskId(),
                binding.processInstanceId(), binding.definitionVersion(), null, null);
    }

    private boolean matchesTenant(String engineTenant, String tenantId) {
        // 全局内置定义可能没有引擎租户，但系统租户变量已在查询中精确匹配。
        return engineTenant == null || engineTenant.isEmpty() || engineTenant.equals(tenantId);
    }
}
