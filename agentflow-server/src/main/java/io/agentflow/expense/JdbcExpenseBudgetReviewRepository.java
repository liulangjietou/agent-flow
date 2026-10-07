package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetOperation;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 原轮次预算例外与审计一起存储，读取时复核预检、冻结版本、原操作及真实任务审计。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseBudgetReviewRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcBudgetOperationRepository operations;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final JdbcExpensePrecheckRepository prechecks;

    /** 复用已有来源仓储，不维护第二套金额或外部执行台账。 */
    public JdbcExpenseBudgetReviewRepository(JdbcTemplate jdbc, JsonUtil json, JdbcBudgetOperationRepository operations,
            JdbcExpenseSubmissionControlRepository controls, JdbcExpensePrecheckRepository prechecks) {
        this.jdbc = jdbc; this.json = json; this.operations = operations; this.controls = controls; this.prechecks = prechecks;
    }

    /** 必须在本轮提交事务中已有正式控制与原预算操作，旧轮次不自动回填。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseBudgetReview value) {
        if (value.version() != 1 || value.status() != ExpenseBudgetReview.Status.WAITING_BUDGET) throw conflict();
        requireSources(value); var input = value.input();
        int inserted = jdbc.update("""
                INSERT INTO expense_budget_review(trace_id,tenant_id,report_id,application_id,employee_id,round_no,submitted_financial_version,
                    precheck_id,original_operation_id,target_digest,budget_node_id,policy_reference,version,status,input_json,state_json,
                    submitted_at,updated_at,next_check_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?,?,1,'WAITING_BUDGET',?,?,?,?,?
                FROM approval_application WHERE tenant_id=? AND id=? AND status='IN_APPROVAL' AND round_no=?
                """, DiagnosticContext.capture().traceId(), input.tenantId(), input.reportId().toString(), input.applicationId().toString(), input.employeeId(), input.roundNo(),
                input.submittedFinancialVersion(), input.precheckId().toString(), input.originalOperationId().toString(), input.targetDigest(),
                input.budgetNodeId(), policy(value), json.write(input), json.write(value), Timestamp.from(value.submittedAt()), Timestamp.from(value.updatedAt()),
                Timestamp.from(value.updatedAt()), input.tenantId(), input.applicationId().toString(), input.roundNo());
        if (inserted != 1) throw conflict();
        append(value);
    }

    /** 原输入保持不变，决定、新预算操作和实际审计必须已在同一事务存在。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseBudgetReview value) {
        requireSources(value); var input = value.input();
        int updated = jdbc.update("""
                UPDATE expense_budget_review SET version=?,status=?,authorized_operation_id=?,decision_audit_id=?,automatic_audit_id=?,
                    state_json=?,updated_at=?,next_check_at=?
                WHERE tenant_id=? AND report_id=? AND round_no=? AND version=? AND input_json=?
                """, value.version(), value.status().name(), text(value.authorizedOperationId()), decision(value), automatic(value), json.write(value),
                Timestamp.from(value.updatedAt()), Timestamp.from(value.updatedAt()), input.tenantId(), input.reportId().toString(), input.roundNo(), value.version()-1, json.write(input));
        if (updated != 1) throw conflict();
        append(value);
    }

    /** 只按精确轮次恢复，当前版本和历史原预算均不得互相冒充。 */
    public Optional<ExpenseBudgetReview> find(String tenant, UUID report, int round) {
        return jdbc.query("SELECT * FROM expense_budget_review WHERE tenant_id=? AND report_id=? AND round_no=?", this::restore,
                tenant, report.toString(), round).stream().findFirst();
    }

    /** 只扫描已有预算终态或等待系统通过的当前轮次，调度延后防止早期节点饥饿。 */
    public List<Candidate> due(Instant at) {
        return jdbc.query("""
                SELECT r.tenant_id,r.report_id,r.application_id,r.round_no,b.id AS operation_id,COALESCE(b.trace_id,r.trace_id) AS trace_id FROM expense_budget_review r
                JOIN approval_application a ON a.tenant_id=r.tenant_id AND a.id=r.application_id AND a.round_no=r.round_no
                JOIN budget_operation b ON b.tenant_id=r.tenant_id AND b.id=COALESCE(r.authorized_operation_id,r.original_operation_id)
                WHERE a.status='IN_APPROVAL' AND r.next_check_at<=? AND
                    ((r.status IN ('WAITING_BUDGET','AUTHORIZED') AND b.status IN ('APPLIED','REJECTED')) OR
                    (r.status='CONFIRMED' AND r.budget_node_id IS NOT NULL AND r.automatic_audit_id IS NULL AND r.authorized_operation_id IS NULL))
                ORDER BY r.next_check_at,r.tenant_id,r.report_id,r.round_no LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("report_id")),
                        UUID.fromString(row.getString("application_id")), row.getInt("round_no"), row.getString("trace_id"), UUID.fromString(row.getString("operation_id"))), Timestamp.from(at));
    }

    /** 轮询时刻不是业务事实，不为尚未到达的原生节点追加虚假的审批修订。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reschedule(Candidate candidate, Instant at) {
        jdbc.update("UPDATE expense_budget_review SET next_check_at=? WHERE tenant_id=? AND report_id=? AND round_no=?",
                Timestamp.from(at), candidate.tenantId(), candidate.reportId().toString(), candidate.roundNo());
    }

    private ExpenseBudgetReview restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), ExpenseBudgetReview.class); var input = value.input();
        if (!input.equals(json.read(row.getString("input_json"), ExpenseBudgetReview.Input.class))
                || !input.tenantId().equals(row.getString("tenant_id")) || !input.reportId().toString().equals(row.getString("report_id"))
                || !input.applicationId().toString().equals(row.getString("application_id")) || !input.employeeId().equals(row.getString("employee_id"))
                || input.roundNo()!=row.getInt("round_no") || input.submittedFinancialVersion()!=row.getLong("submitted_financial_version")
                || !input.precheckId().toString().equals(row.getString("precheck_id")) || !input.originalOperationId().toString().equals(row.getString("original_operation_id"))
                || !input.targetDigest().equals(row.getString("target_digest")) || !Objects.equals(input.budgetNodeId(), row.getString("budget_node_id"))
                || !Objects.equals(policy(value), row.getString("policy_reference")) || value.version()!=row.getLong("version")
                || !value.status().name().equals(row.getString("status")) || !Objects.equals(text(value.authorizedOperationId()), row.getString("authorized_operation_id"))
                || !Objects.equals(decision(value), row.getString("decision_audit_id")) || !Objects.equals(automatic(value), row.getString("automatic_audit_id"))
                || !value.submittedAt().equals(row.getTimestamp("submitted_at").toInstant()) || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) throw inconsistent();
        requireSources(value); return value;
    }

    private void requireSources(ExpenseBudgetReview value) {
        var input = value.input();
        var control = controls.find(input.tenantId(), input.reportId(), input.roundNo()).orElseThrow(JdbcExpenseBudgetReviewRepository::inconsistent);
        var submitted = control.input();
        if (!input.applicationId().equals(submitted.applicationId()) || !input.employeeId().equals(submitted.employeeId())
                || input.submittedFinancialVersion()!=submitted.submittedFinancialVersion() || !input.precheckId().equals(submitted.precheckId())
                || !value.submittedAt().equals(control.submittedAt())
                || input.budgetNodeId()!=null && submitted.stages().get(input.budgetNodeId())!=ExpenseProcessPolicy.Stage.BUDGET_REVIEW
                || input.budgetNodeId()==null && submitted.stages().containsValue(ExpenseProcessPolicy.Stage.BUDGET_REVIEW)) throw inconsistent();
        var original = operation(input.tenantId(), input.originalOperationId());
        var authorized = inputOperation(value);
        value.requireSources(original, authorized);
        var checked = prechecks.find(input.tenantId(), input.precheckId()).orElseThrow(JdbcExpenseBudgetReviewRepository::inconsistent);
        if (checked.status()!=ExpensePrecheckJob.Status.READY || !checked.input().reportId().equals(input.reportId())
                || !checked.input().applicationId().equals(input.applicationId()) || !checked.input().employeeId().equals(input.employeeId())
                || checked.input().roundNo()!=input.roundNo() || checked.input().financialVersion()+1!=input.submittedFinancialVersion()
                || !checked.input().targetDigest().equals(input.targetDigest()) || !Objects.equals(input.policy(), checked.result().evidence().budget().exceptionPolicy())
                || checked.completedAt().isAfter(value.submittedAt()) || !checked.result().evidence().validUntil().isAfter(value.submittedAt())) throw inconsistent();
        var report = jdbc.query("SELECT state_json FROM expense_report_revision WHERE tenant_id=? AND report_id=? AND financial_version=?",
                (row, index) -> ExpenseReport.restore(json.read(row.getString(1), ExpenseReport.State.class)), input.tenantId(), input.reportId().toString(),
                input.submittedFinancialVersion()).stream().findFirst().orElseThrow(JdbcExpenseBudgetReviewRepository::inconsistent);
        if (!report.applicationId().equals(input.applicationId()) || !report.currentRound().submittedAt().equals(value.submittedAt())
                || !original.input().command().position().equals(BudgetPrecheckPort.Request.fromCurrent(report, submitted.accountingDate()))
                || !checked.result().evidence().budget().request().equals(BudgetPrecheckPort.Request.from(report, submitted.accountingDate()))) throw inconsistent();
        if (value.approval()!=null) audit(value, value.approval().auditEventId(), value.approval().taskId(), value.approval().actorId(), "APPROVE", value.approval().approvedAt(), null);
        if (value.automaticPass()!=null) audit(value, value.automaticPass().auditEventId(), value.automaticPass().taskId(), ExpenseBudgetApprovalPolicy.SYSTEM_ACTOR,
                ExpenseBudgetApprovalPolicy.AUTOMATIC_ACTION, value.automaticPass().passedAt(), original);
        if (value.closure()!=null && !jdbc.queryForList("SELECT status FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?",
                String.class, input.tenantId(), input.applicationId().toString(), input.roundNo()).equals(List.of(value.closure().name()))) throw inconsistent();
    }

    private void audit(ExpenseBudgetReview value, UUID auditId, String taskId, String actor, String action, Instant at, BudgetOperation confirmed) {
        var input = value.input();
        var matches = jdbc.query("""
                SELECT payload_json,occurred_at FROM audit_event WHERE tenant_id=? AND event_id=? AND aggregate_type='Task'
                    AND aggregate_id=? AND application_id=? AND actor_id=? AND action=?
                """, (row, index) -> {
            var payload = json.read(row.getString("payload_json"), JsonNode.class);
            return payload.path("roundNo").isIntegralNumber() && payload.path("roundNo").asInt()==input.roundNo()
                    && payload.path("nodeId").asText().equals(input.budgetNodeId()) && payload.path("actor").asText().equals(actor)
                    && payload.path("action").asText().equals(action) && payload.path("applicationId").asText().equals(input.applicationId().toString())
                    && (confirmed==null || payload.at("/budgetConfirmation/operationId").asText().equals(confirmed.input().command().id().toString())
                        && payload.at("/budgetConfirmation/commandDigest").asText().equals(confirmed.input().command().digest()))
                    && !row.getTimestamp("occurred_at").toInstant().isAfter(at);
        }, input.tenantId(), auditId.toString(), taskId, input.applicationId().toString(), actor, action);
        if (!matches.equals(List.of(true))) throw inconsistent();
    }
    private BudgetOperation inputOperation(ExpenseBudgetReview value) { return value.authorizedOperationId()==null ? null : operation(value.input().tenantId(), value.authorizedOperationId()); }
    private BudgetOperation operation(String tenant, UUID id) { return operations.find(tenant, id).orElseThrow(JdbcExpenseBudgetReviewRepository::inconsistent); }
    private void append(ExpenseBudgetReview value) {
        jdbc.update("INSERT INTO expense_budget_review_revision(tenant_id,report_id,round_no,version,state_json) VALUES(?,?,?,?,?)",
                value.input().tenantId(), value.input().reportId().toString(), value.input().roundNo(), value.version(), json.write(value));
    }
    private static String policy(ExpenseBudgetReview value) { return value.input().policy()==null ? null : value.input().policy().reference(); }
    private static String decision(ExpenseBudgetReview value) { return value.approval()==null ? null : text(value.approval().auditEventId()); }
    private static String automatic(ExpenseBudgetReview value) { return value.automaticPass()==null ? null : text(value.automaticPass().auditEventId()); }
    private static String text(UUID value) { return value==null ? null : value.toString(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense budget review input or version changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted expense budget review is inconsistent with original sources"); }

    /**
     * 后台扫描只持有身份，进入申请锁后重新核对真实状态。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID reportId, UUID applicationId, int roundNo, String traceId, UUID operationId) {
        /** 显式恢复旧身份时保留兼容入口，持久扫描始终携带原操作。 */
        public Candidate(String tenantId, UUID reportId, UUID applicationId, int roundNo, String traceId) { this(tenantId, reportId, applicationId, roundNo, traceId, null); }
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID reportId, UUID applicationId, int roundNo) { this(tenantId, reportId, applicationId, roundNo, null, null); }
    }
}
