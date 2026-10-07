package io.agentflow.approval.process;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.expense.ExpenseApprovalService;
import io.agentflow.expense.ExpenseDuplicateApprovalPolicy;
import io.agentflow.expense.ExpenseSelfApprovalBindings;
import io.agentflow.expense.ExpenseSelfApprovalSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在原申请锁和进度事务中编排重复审批，原生任务、领域版本和审计任一失败则共同回滚。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExpenseDuplicateApprovalProgress {
    private static final String ACTOR = "system:expense-duplicate-approval";
    private final DefinitionDraftRepository definitions;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final FlowableExpenseDuplicateTrace traces;
    private final FlowableApprovalResponsibilities responsibilities;
    private final TaskRecipientDirectory directory;
    private final ExpenseApprovalService expenses;
    private final TaskAuditPort audit;
    private final JsonUtil json;

    /** 共用原资格、职责分离和费用控制，不在自动审批中另建宽松的授权入口。 */
    public ExpenseDuplicateApprovalProgress(DefinitionDraftRepository definitions, RuntimeService runtime, TaskService tasks,
            FlowableExpenseDuplicateTrace traces, FlowableApprovalResponsibilities responsibilities, TaskRecipientDirectory directory,
            ExpenseApprovalService expenses, TaskAuditPort audit, JsonUtil json) {
        this.definitions = definitions; this.runtime = runtime; this.tasks = tasks; this.traces = traces;
        this.responsibilities = responsibilities; this.directory = directory; this.expenses = expenses; this.audit = audit; this.json = json;
    }

    /** 人工和异步等待推进均调用此处；返回自动动作后的实际结束状态。 */
    public boolean advance(Application application, String instanceId) {
        if (application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.EXPENSE) return false;
        var definition = definitions.findPublished(application.tenantId(), application.processKey(), application.definitionVersion()).orElse(null);
        if (definition == null || !ExpenseDuplicateApprovalPolicy.enabled(definition.graph())) return false;
        var snapshot = json.read((String) runtime.getVariableLocal(instanceId, ExpenseSelfApprovalBindings.VARIABLE), ExpenseSelfApprovalSnapshot.class);
        if (!snapshot.applicationId().equals(application.id()) || !snapshot.tenantId().equals(application.tenantId())
                || snapshot.roundNo() != application.roundNo() || !snapshot.definitionId().equals(definition.id())
                || snapshot.definitionVersion() != definition.version()
                || !snapshot.runtimeDefinitionId().equals(application.runtimeDefinitionId())) throw invalid();
        while (runtime.createProcessInstanceQuery().processInstanceId(instanceId).singleResult() != null) {
            var stored = runtime.getVariableLocal(instanceId, FlowableExpenseDuplicateTrace.VARIABLE);
            if (stored == null) throw invalid();
            var trace = traces.read(stored); boolean advanced = false;
            for (var task : tasks.createTaskQuery().processInstanceId(instanceId).active().includeIdentityLinks().includeProcessVariables().list()) {
                String sourceNode = ExpenseDuplicateApprovalPolicy.previousBusinessNode(definition.graph(), task.getTaskDefinitionKey(), Set.copyOf(trace.takenEdges()));
                if (sourceNode == null) continue;
                var decisions = trace.decisions().getOrDefault(sourceNode, List.of());
                var subjects = decisions.stream().map(FlowableExpenseDuplicateTrace.Decision::subject).distinct().toList();
                if (subjects.size() != 1 || !subjects.get(0).equals(soleApprover(task))) continue;
                String subject = subjects.get(0);
                if (subject.equals(application.createdBy()) || !directory.eligible(application.tenantId(), subject)
                        || !responsibilities.allows(task, subject)) continue;
                expenses.requireApproval(application, task);
                var proof = new TaskAuditPort.DuplicateApproval(trace.ruleVersion(), definition.id(), definition.version(),
                        sourceNode, decisions.stream().map(FlowableExpenseDuplicateTrace.Decision::taskId).sorted().toList(), subject);
                String reason = "相邻业务审批由同一人办理，已自动通过；原审批人 " + subject + "，来源节点 " + sourceNode
                        + "，来源任务 " + String.join(",", proof.sourceTaskIds()) + "；发布版本 " + definition.version() + "，规则版本 " + trace.ruleVersion();
                application.recordTaskAction(application.version());
                if (task.getAssignee() == null) tasks.claim(task.getId(), subject);
                tasks.complete(task.getId(), Map.of("lastAction", ExpenseDuplicateApprovalPolicy.ACTION));
                audit.record(new TaskAuditPort.TaskOperation(application.tenantId(), application.businessNo(), task.getId(), application.id(), application.version(),
                        application.roundNo(), instanceId, ACTOR, ExpenseDuplicateApprovalPolicy.ACTION, reason, subject,
                        task.getTaskDefinitionKey(), task.getName(), application.status(), application.status(), null, null, proof));
                advanced = true; break;
            }
            if (!advanced) return false;
        }
        return true;
    }

    private String soleApprover(Task task) {
        if (task.isSuspended() || task.getOwner() != null || task.getDelegationState() != null) return null;
        Object total = tasks.getVariable(task.getId(), FlowableCountersignMembers.TOTAL);
        if (total instanceof Number count && count.intValue() != 1) return null;
        if (task.getAssignee() != null) return task.getAssignee();
        var links = task.getIdentityLinks().stream().filter(link -> "candidate".equals(link.getType())).toList();
        if (links.stream().anyMatch(link -> link.getGroupId() != null)) return null;
        var subjects = links.stream().map(link -> link.getUserId()).filter(java.util.Objects::nonNull).distinct().toList();
        return subjects.size() == 1 ? subjects.get(0) : null;
    }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_DUPLICATE_SCOPE_INVALID", "Expense duplicate approval requires its original published round");
    }
}
