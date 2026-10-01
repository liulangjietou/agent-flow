package io.agentflow.approval.process;

import io.agentflow.approval.model.CountersignMembership;
import io.agentflow.common.DomainException;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 会签增减的引擎防腐层；只操作原多实例，不修改最初组织快照，不把取消计为同意。
 * 授权、申请版本、具名审计及通知由外层应用事务负责。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FlowableCountersignRuntime {
    private static final String USER = "agentflowCountersignUser";
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final RepositoryService definitions;

    /** 引擎本身是任务、完成计数和历史意见的事实来源。 */
    public FlowableCountersignRuntime(RuntimeService runtime, TaskService tasks, HistoryService history, RepositoryService definitions) {
        this.runtime = runtime; this.tasks = tasks; this.history = history; this.definitions = definitions;
    }

    /** 按当前任务的实际父执行定位本节点，不能混入另一个并行会签。 */
    public Scope read(Task source) {
        var child = execution(source.getExecutionId());
        var root = execution(child.getParentId());
        var element = definitions.getBpmnModel(source.getProcessDefinitionId()).getFlowElement(source.getTaskDefinitionKey());
        if (!(element instanceof UserTask node) || node.getLoopCharacteristics() == null
                || node.getLoopCharacteristics().isSequential()
                || !USER.equals(node.getLoopCharacteristics().getElementVariable())
                || node.getLoopCharacteristics().getCompletionCondition() != null
                || !source.getTaskDefinitionKey().equals(root.getActivityId())
                || !source.getProcessInstanceId().equals(root.getProcessInstanceId())
                || child.isSuspended() || root.isSuspended()) throw invalid();
        Object snapshot = runtime.getVariableLocal(root.getId(), FlowableCountersignMembers.MEMBERS);
        if (!(snapshot instanceof List<?> members) || members.isEmpty() || members.stream().anyMatch(member -> !(member instanceof String))) {
            throw invalid();
        }
        var pending = tasks.createTaskQuery().processInstanceId(source.getProcessInstanceId())
                .taskDefinitionKey(source.getTaskDefinitionKey()).active().list().stream()
                .map(task -> member(task, root)).toList();
        // 平台图禁止循环且尚不支持子流程，同一轮的节点只激活一次；取消历史不属于已同意。
        var completed = history.createHistoricTaskInstanceQuery().processInstanceId(source.getProcessInstanceId())
                .taskDefinitionKey(source.getTaskDefinitionKey()).finished().list().stream()
                .filter(task -> task.getDeleteReason() == null)
                .map(task -> task.getAssignee()).toList();
        var membership = new CountersignMembership(counter(root, FlowableCountersignMembers.TOTAL),
                counter(root, FlowableCountersignMembers.COMPLETED), pending, completed);
        return new Scope(root.getId(), root.getParentId(), members.stream().map(String.class::cast).toList(), membership);
    }

    /** 新执行直接绑定明确人员，原集合与原成员的任务、期限和完成意见保持。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Change add(Task source, String targetUser) {
        Scope before = read(source);
        before.membership().requireAddition(source.getId(), targetUser);
        Execution added = runtime.addMultiInstanceExecution(source.getTaskDefinitionKey(), before.parentExecutionId(), Map.of(USER, targetUser));
        Task target = tasks.createTaskQuery().executionId(added.getId()).singleResult();
        if (target == null || !targetUser.equals(target.getAssignee())) throw invalid();
        Scope after = read(source);
        requireDelta(before, after, 1);
        return new Change(before.executionId(), target.getId(), targetUser, before.membership().total(), after.membership().total(), after.membership().completed());
    }

    /** 删除未决执行时明确使用未完成语义；引擎保留取消历史，剩余任务仍须实际同意。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Change remove(Task source, String targetTaskId) {
        Scope before = read(source);
        var selected = before.membership().requireRemoval(source.getId(), targetTaskId);
        Task target = tasks.createTaskQuery().taskId(selected.taskId()).singleResult();
        if (target == null) throw invalid();
        runtime.deleteMultiInstanceExecution(target.getExecutionId(), false);
        Scope after = read(source);
        requireDelta(before, after, -1);
        return new Change(before.executionId(), selected.taskId(), selected.user(), before.membership().total(), after.membership().total(), after.membership().completed());
    }

    private CountersignMembership.Member member(Task task, Execution root) {
        Execution child = execution(task.getExecutionId());
        Object user = runtime.getVariableLocal(child.getId(), USER);
        boolean delegated = task.getDelegationState() == DelegationState.PENDING;
        if (!root.getId().equals(child.getParentId()) || !(user instanceof String responsibility)
                || task.getAssignee() == null || delegated && !responsibility.equals(task.getOwner())) throw invalid();
        return new CountersignMembership.Member(task.getId(), responsibility, task.getAssignee(), delegated);
    }

    private Execution execution(String id) {
        if (id == null) throw invalid();
        Execution execution = runtime.createExecutionQuery().executionId(id).singleResult();
        if (execution == null) throw invalid();
        return execution;
    }

    private int counter(Execution root, String name) {
        Object value = runtime.getVariableLocal(root.getId(), name);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) throw invalid();
        return number.intValue();
    }

    private void requireDelta(Scope before, Scope after, int delta) {
        if (!before.executionId().equals(after.executionId()) || !before.originalMembers().equals(after.originalMembers())
                || before.membership().total() + delta != after.membership().total()
                || before.membership().completed() != after.membership().completed()) throw invalid();
    }

    private static DomainException invalid() {
        return new DomainException("COUNTERSIGN_STATE_INVALID", "The active countersign execution cannot be verified");
    }

    /**
     * 原选人快照和当前引擎事实分别展示，显式变更不重写原组织解析结果。
     * @author owlzhangfq@gmail.com
     */
    public record Scope(String executionId, String parentExecutionId, List<String> originalMembers, CountersignMembership membership) { }

    /**
     * 应用层审计使用实际变更后的任务身份和计数，不能采用客户端伪造的结果。
     * @author owlzhangfq@gmail.com
     */
    public record Change(String executionId, String targetTaskId, String targetUser, int totalBefore, int totalAfter, int completed) { }
}
