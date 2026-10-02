package io.agentflow.approval.process;

import org.flowable.engine.HistoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 原审批责任通过原生身份链接随任务完成保留，不把它混同为实际批准人或新一轮授权。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableProxyParticipation {
    static final String PRINCIPAL_LINK = "approvalProxyPrincipal";
    private final HistoryService history;

    /** 身份链接和任务审计随同一引擎事务提交或回滚。 */
    public FlowableProxyParticipation(HistoryService history) { this.history = history; }

    /** 只承认已结束任务上的明确代理责任链接，不把其他候选或抄送链接提升为历史责任。 */
    public boolean represented(String tenantId, UUID applicationId, String principal) {
        return history.createHistoricTaskInstanceQuery().finished().taskInvolvedUser(principal)
                .processVariableValueEquals("applicationId", applicationId.toString())
                .processVariableValueEquals("tenantId", tenantId).list().stream()
                .anyMatch(task -> principals(task.getId()).contains(principal));
    }

    /** 字段权限仅取该轮实际实例中被代理的节点；旧代理到期不抹去已经发生的责任。 */
    public Set<String> nodes(String tenantId, String instanceId, String principal) {
        return history.createHistoricTaskInstanceQuery().finished().processInstanceId(instanceId).taskTenantId(tenantId)
                .taskInvolvedUser(principal).list().stream().filter(task -> principals(task.getId()).contains(principal))
                .map(task -> task.getTaskDefinitionKey()).collect(Collectors.toUnmodifiableSet());
    }

    /** 调用方已经按租户和实例绑定任务；这里只读取该任务真实保留的原责任账号。 */
    public List<String> principals(String taskId) {
        return history.getHistoricIdentityLinksForTask(taskId).stream()
                .filter(link -> PRINCIPAL_LINK.equals(link.getType()) && link.getUserId() != null)
                .map(link -> link.getUserId()).distinct().sorted().toList();
    }
}
