package io.agentflow.approval.process;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseDuplicateApprovalPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.stereotype.Component;

/**
 * 新发布版本的原生路径与决策事实随引擎事务持久化；监听器不推进流程或修改申请聚合。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableExpenseDuplicateTrace")
public class FlowableExpenseDuplicateTrace implements ExecutionListener, TaskListener {
    public static final String VARIABLE = "agentflowExpenseDuplicateTrace";
    private final JsonUtil json;

    /** 只依赖序列化，避免引擎初始化与审批应用服务形成循环依赖。 */
    public FlowableExpenseDuplicateTrace(JsonUtil json) { this.json = json; }

    /** 记录实际经过的顺序流，恢复后无需用可能变化的金额重新执行条件。 */
    @Override
    public void notify(DelegateExecution execution) {
        if (!ExecutionListener.EVENTNAME_TAKE.equals(execution.getEventName())
                || !(execution.getCurrentFlowElement() instanceof SequenceFlow edge)) throw invalid();
        var instance = instance(execution.getProcessInstanceId(), execution.getTenantId());
        var trace = read(instance.getVariableLocal(VARIABLE));
        var edges = new TreeSet<>(trace.takenEdges()); edges.add(edge.getId());
        instance.setVariableLocal(VARIABLE, json.write(new Trace(trace.ruleVersion(), List.copyOf(edges), trace.decisions())));
    }

    /** 取消会签及委派回交不会产生批准事实；实际 assignee 已由原办理入口确认。 */
    @Override
    public void notify(DelegateTask task) {
        if (ExpenseBudgetReviewProgress.automatic(task)) return;
        String action = String.valueOf(task.getVariable("lastAction"));
        if (!TaskListener.EVENTNAME_COMPLETE.equals(task.getEventName()) || task.getAssignee() == null
                || !("APPROVE".equals(action) || ExpenseDuplicateApprovalPolicy.ACTION.equals(action))) throw invalid();
        var instance = instance(task.getProcessInstanceId(), task.getTenantId());
        var trace = read(instance.getVariableLocal(VARIABLE));
        var decisions = new TreeMap<>(trace.decisions());
        var node = new ArrayList<>(decisions.getOrDefault(task.getTaskDefinitionKey(), List.of()));
        node.add(new Decision(task.getId(), task.getAssignee(), action));
        decisions.put(task.getTaskDefinitionKey(), List.copyOf(node));
        instance.setVariableLocal(VARIABLE, json.write(new Trace(trace.ruleVersion(), trace.takenEdges(), decisions)));
    }

    /** 实例初次经过开始边时初始化；规则版本不允许在服务升级时被静默重解释。 */
    public Trace read(Object stored) {
        var trace = stored == null ? new Trace(ExpenseDuplicateApprovalPolicy.RULE_VERSION, List.of(), Map.of())
                : json.read((String) stored, Trace.class);
        if (trace.ruleVersion() != ExpenseDuplicateApprovalPolicy.RULE_VERSION) throw invalid();
        return trace;
    }

    private ExecutionEntity instance(String id, String tenant) {
        var instance = CommandContextUtil.getExecutionEntityManager().findById(id);
        if (instance == null || tenant == null || !tenant.equals(instance.getVariableLocal("tenantId"))) throw invalid();
        return instance;
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_DUPLICATE_SCOPE_INVALID", "Expense duplicate approval trace scope is invalid");
    }

    /**
     * 原生任务完成时的实际人员及动作，不把角色、代理委托人或未完成会签票当作批准。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(String taskId, String subject, String action) { }

    /**
     * 图无环，边与已完成任务仅追加；与流程实例共用持久化和回滚边界。
     * @author owlzhangfq@gmail.com
     */
    public record Trace(int ruleVersion, List<String> takenEdges, Map<String, List<Decision>> decisions) {
        public Trace { takenEdges = List.copyOf(takenEdges); decisions = Map.copyOf(decisions); }
    }
}
