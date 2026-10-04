package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原轮次财务来源与原生责任的只读组合；先校验敏感明细权限，不查询当前目录替换历史。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseProjectApprovalQuery {
    private final ExpenseDraftService expenses;
    private final JdbcExpenseProjectApprovalRepository projects;
    private final SubmissionRoundRepository rounds;
    private final HistoryService history;
    private final CurrentActor actors;
    private final JsonUtil json;

    /** 复用费用明细授权、业务原依据和引擎历史，不建立另一份审批状态。 */
    public ExpenseProjectApprovalQuery(ExpenseDraftService expenses, JdbcExpenseProjectApprovalRepository projects,
            SubmissionRoundRepository rounds, HistoryService history, CurrentActor actors, JsonUtil json) {
        this.expenses = expenses; this.projects = projects; this.rounds = rounds;
        this.history = history; this.actors = actors; this.json = json;
    }

    /** 草稿、外单及未知轮次不补造记录；管理员同样服从原节点的敏感字段规则。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID reportId, int roundNo) {
        var expense = expenses.read(reportId, roundNo); String tenant = actors.actor().tenantId();
        var source = projects.find(tenant, reportId, roundNo).orElse(null);
        if (source == null) return new View(reportId, expense.applicationId(), roundNo, Status.NOT_RECORDED, null);
        var round = rounds.findByRound(tenant, expense.applicationId(), roundNo).orElseThrow(ExpenseProjectApprovalQuery::unavailable);
        if (!source.applicationId().equals(expense.applicationId()) || source.definitionVersion() != round.definitionVersion()
                || !source.precheck().initiator().equals(round.initiatorContext())
                || ExpenseFormContract.hasProjectControl(round.formSchema())
                    && !Objects.equals(round.payload().get(ExpenseFormContract.HAS_PROJECT_ALLOCATION), source.hasProjects())) throw unavailable();
        ExpenseSelfApprovalSnapshot.Selection responsibility = null;
        if (source.hasProjects()) {
            var variables = history.createHistoricVariableInstanceQuery().processInstanceId(round.processInstanceId())
                    .variableName(ExpenseSelfApprovalBindings.VARIABLE).list();
            if (variables.size() != 1) throw unavailable();
            var variable = variables.get(0);
            if (variable.getTaskId() != null || !round.processInstanceId().equals(variable.getExecutionId())
                    || !(variable.getValue() instanceof String encoded)) throw unavailable();
            var frozen = json.read(encoded, ExpenseSelfApprovalSnapshot.class);
            var instance = history.createHistoricProcessInstanceQuery().processInstanceId(round.processInstanceId()).singleResult();
            if (instance == null || !tenant.equals(frozen.tenantId()) || !expense.applicationId().equals(frozen.applicationId())
                    || frozen.roundNo() != roundNo || !source.definitionId().equals(frozen.definitionId())
                    || source.definitionVersion() != frozen.definitionVersion() || frozen.ruleVersion() != ExpenseSelfApprovalPolicy.RULE_VERSION
                    || !instance.getProcessDefinitionId().equals(frozen.runtimeDefinitionId()) || !round.initiatorContext().equals(frozen.initiator())) throw unavailable();
            responsibility = frozen.node(source.nodeId());
            if (responsibility.stage() != ExpenseProcessPolicy.Stage.PROJECT_REVIEW || !ExpenseProjectApprovalPolicy.isRule(responsibility.rule())
                    || !responsibility.originalSubjects().equals(source.owners().subjects())) throw unavailable();
            var escalation = responsibility.escalation();
            boolean self = responsibility.originalSubjects().contains(frozen.initiator().subject());
            if (self != (escalation != null) || escalation != null && (!frozen.initiator().subject().equals(escalation.originalSubject())
                    || escalation.supervisorAppointmentId() == null || escalation.replacementSubject() == null
                    || escalation.replacementSubject().isBlank() || escalation.replacementSubject().equals(escalation.originalSubject()))) throw unavailable();
            var expected = responsibility.originalSubjects().stream().map(subject -> escalation != null && subject.equals(escalation.originalSubject())
                    ? escalation.replacementSubject() : subject).distinct().sorted().toList();
            if (!responsibility.candidateSubjects().equals(expected)) throw unavailable();
        }
        return new View(reportId, expense.applicationId(), roundNo, source.hasProjects() ? Status.RECORDED : Status.NO_PROJECT,
                new Details(source.ruleVersion(), source.precheck().id(), source.precheckVersion(), source.applicationVersion(), source.financialVersion(),
                        source.definitionId(), source.definitionVersion(), source.nodeId(), source.submittedAt(), source.owners(), source.precheck().initiator(), responsibility));
    }

    private static DomainException unavailable() {
        return new DomainException("EXPENSE_PROJECT_SNAPSHOT_MISSING", "Original project evidence and native responsibility must belong to the same published submission round");
    }

    /**
     * 未记录不能被解释为无项目，更不能被解释为审批通过。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_RECORDED, NO_PROJECT, RECORDED }

    /**
     * 返回读取所必需的原责任信息，不重复暴露完整预检和账户内容。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Details(int ruleVersion, UUID precheckId, long precheckVersion, long applicationVersion, long financialVersion,
            UUID definitionId, long definitionVersion, String nodeId, Instant submittedAt, ExpenseProjectOwners source,
            InitiatorContext initiator, ExpenseSelfApprovalSnapshot.Selection responsibility) { }

    /**
     * 原依据与审批结果分开；未记录时明确返回空正文。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, Status status, Details details) { }
}
