package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import io.agentflow.finance.ExpenseAdjustmentFundingSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部分调整按原报销串行保存，命令注册和每次执行修订同事务追加，回款原件不会被两个调整采用。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePartialAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ExpenseReportRepository reports;
    private final ExpensePartialAdjustmentSources sources;
    private final ExpensePartialAdjustmentGuard guard;

    /** 源事实核对与互斥沿用实际业务仓储，网络调用不进入本仓储。 */
    public JdbcExpensePartialAdjustmentRepository(JdbcTemplate jdbc, JsonUtil json, ExpenseReportRepository reports,
            ExpensePartialAdjustmentSources sources, ExpensePartialAdjustmentGuard guard) {
        this.jdbc = jdbc; this.json = json; this.reports = reports; this.sources = sources; this.guard = guard;
    }

    /** 新建只接受无外部操作的初态；前次依据从真正完成的当前记录恢复，不能采用客户端准备。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePartialAdjustment value) {
        var input = value.input(); var basis = input.basis(); var financial = basis.funding().financial();
        guard.requirePartialAllowed(basis.tenantId(), basis.reportId());
        if (!value.equals(ExpensePartialAdjustment.begin(input))) throw conflict();
        if (active(basis.tenantId(), basis.reportId()).isPresent()) throw pending();
        sources.requireCurrent(basis.funding());
        var previous = latestCompleted(basis.tenantId(), basis.reportId()).orElse(null);
        if (!basis.equals(ExpensePartialAdjustmentBasis.from(basis.funding(), previous))) throw conflict();
        requireUsedReturns(basis);
        jdbc.update("""
                INSERT INTO expense_partial_adjustment(tenant_id,id,report_id,round_no,sequence_no,settlement_version,consumption_id,consumed_version,
                accrual_id,accrual_version,previous_id,previous_version,input_json,state_json,version,status,active_report_id,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'WAITING_FINANCE',?,?,?)
                """, basis.tenantId(), value.id().toString(), basis.reportId().toString(), financial.settlement().input().source().roundNo(), sequence(basis),
                financial.settlement().version(), financial.consumption().input().command().id().toString(), financial.consumption().version(),
                financial.accrual().input().command().id().toString(), financial.accrual().version(), basis.previous() == null ? null : basis.previous().id().toString(),
                basis.previous() == null ? null : basis.previous().version(), json.write(input), json.write(value), basis.reportId().toString(), timestamp(input.createdAt()), timestamp(value.updatedAt()));
        append(value);
        for (var entry : basis.funding().selectedReturns()) jdbc.update("""
                INSERT INTO expense_partial_adjustment_return(tenant_id,adjustment_id,report_id,funds_identity,registration_id,entry_json,active_funds_identity)
                VALUES(?,?,?,?,?,?,?)
                """, basis.tenantId(), value.id().toString(), basis.reportId().toString(), entry.proof().fundsIdentity(), entry.registrationId().toString(), json.write(entry), entry.proof().fundsIdentity());
    }

    /** 回放一项领域转换再落库；普通状态更新不能写入资源完成标记，也不能替换原完成事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePartialAdjustment value) {
        var basis = value.input().basis(); reports.lock(basis.tenantId(), basis.reportId());
        var before = find(basis.tenantId(), value.id()).orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (!before.input().equals(value.input()) || before.version() == Long.MAX_VALUE || value.version() != before.version() + 1
                || !Objects.equals(before.completion(), value.completion())) throw conflict();
        ExpensePartialAdjustment expected;
        ExpenseAdjustmentFundingSource authorizationSource = null;
        String newSide = null;
        if (value.retirement() != null && before.retirement() == null) {
            var decision = value.retirement();
            sources.current(basis.funding());
            expected = before.retire(decision.actor(), decision.evidenceReference(), decision.reason(), decision.at());
        } else if (!Objects.equals(before.budget(), value.budget())) {
            if (value.budget() != null && (before.budget() == null || !before.budget().input().equals(value.budget().input()))) {
                requireCurrentPredecessor(basis);
                authorizationSource = sources.current(basis.funding());
                var source = authorizationSource.financial().consumption(); var operation = value.budget();
                if (operation.input().consumedVersion() != source.version() || !operation.input().command().source().equals(source.input().command())
                        || !operation.input().command().consumed().equals(source.observation())) throw conflict();
                authorizationSource.requireAuthorization(operation.input().command().authorizedBy(), operation.input().command().createdAt());
                expected = before.authorizeBudget(operation, value.updatedAt()); newSide = "BUDGET";
            } else {
                if (value.budget() != null && value.budget().status() == BudgetConsumptionReductionOperation.Status.EXECUTING) {
                    requireCurrentPredecessor(basis); sources.current(basis.funding());
                }
                expected = before.withBudget(value.budget(), value.updatedAt());
            }
        } else if (!Objects.equals(before.accrual(), value.accrual())) {
            if (value.accrual() != null && (before.accrual() == null || !before.accrual().input().equals(value.accrual().input()))) {
                requireCurrentPredecessor(basis);
                authorizationSource = sources.current(basis.funding());
                var source = authorizationSource.financial().accrual(); var operation = value.accrual();
                if (operation.input().originalVersion() != source.version() || !operation.input().command().source().command().equals(source.input().command())
                        || !operation.input().command().source().original().equals(source.observation())) throw conflict();
                authorizationSource.requireAuthorization(operation.input().command().authorizedBy(), operation.input().command().createdAt());
                expected = before.authorizeAccrual(operation, value.updatedAt()); newSide = "ACCRUAL";
            } else {
                if (value.accrual() != null && value.accrual().status() == ExpenseAccrualReductionOperation.Status.POSTING) {
                    requireCurrentPredecessor(basis); sources.current(basis.funding());
                }
                expected = before.withAccrual(value.accrual(), value.updatedAt());
            }
        } else if (value.issue() != null) expected = before.requireReview(value.issue(), value.updatedAt());
        else {
            sources.current(basis.funding());
            expected = before.confirmCurrent(value.updatedAt());
        }
        if (!expected.equals(value)) throw conflict();
        save(value);
        if (newSide != null) registerOperation(value, newSide, authorizationSource);
        if (value.retirement() != null && before.retirement() == null) {
            int released = jdbc.update("UPDATE expense_partial_adjustment_return SET active_funds_identity=NULL,released_at=? WHERE tenant_id=? AND adjustment_id=? AND released_at IS NULL AND completed_at IS NULL",
                    timestamp(value.retirement().at()), basis.tenantId(), value.id().toString());
            if (released != basis.funding().selectedReturns().size()) throw conflict();
        }
    }

    /** 读取始终从独立租户列定位，JSON 身份及规范化操作列不一致时拒绝恢复。 */
    public Optional<ExpensePartialAdjustment> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM expense_partial_adjustment WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 只有安全结束或真正资源完成才释放当前办理位置，历史原件保护仍保留。 */
    public Optional<ExpensePartialAdjustment> active(String tenant, UUID report) {
        return jdbc.query("SELECT * FROM expense_partial_adjustment WHERE tenant_id=? AND active_report_id=?", row(), tenant, report.toString()).stream().findFirst();
    }
    /** 后继读取最新完成顺序，当前争议记录也返回给领域核对，不能跳过它采用更早净额。 */
    public Optional<ExpensePartialAdjustment> latestCompleted(String tenant, UUID report) {
        return jdbc.query("SELECT * FROM expense_partial_adjustment WHERE tenant_id=? AND report_id=? AND completed_at IS NOT NULL ORDER BY completed_sequence DESC LIMIT 1", row(), tenant, report.toString()).stream().findFirst();
    }
    /** 精确修订作为后继和授权证据，不允许原号对应另一份状态。 */
    public Optional<ExpensePartialAdjustment> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM expense_partial_adjustment_revision WHERE tenant_id=? AND adjustment_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpensePartialAdjustment.class);
            if (!value.input().basis().tenantId().equals(tenant) || !value.id().equals(id) || value.version() != version) throw inconsistent();
            requireRegisteredOperations(value); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }
    /** 预算和会计分别扫描各自到期队列，同一操作租约到期后仍只查询原号。 */
    public List<Candidate> dueBudget(Instant at) { return due("budget", at); }
    /** 不以预算已经成功推断 ERP 已过账，单独领取会计操作。 */
    public List<Candidate> dueAccrual(Instant at) { return due("accrual", at); }
    private List<Candidate> due(String side, Instant at) {
        return jdbc.query("SELECT tenant_id,id,report_id,version FROM expense_partial_adjustment WHERE retired_at IS NULL AND ((" + side + "_status IN ('QUEUED','UNKNOWN') AND " + side
                + "_next_at<=?) OR (" + side + "_status IN ('EXECUTING','POSTING','QUERYING') AND " + side + "_lease_until<=?)) ORDER BY updated_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), UUID.fromString(row.getString("report_id")), row.getLong("version")), timestamp(at), timestamp(at));
    }
    private void save(ExpensePartialAdjustment value) {
        var basis = value.input().basis(); var budget = value.budget(); var accrual = value.accrual();
        int changed = jdbc.update("""
                UPDATE expense_partial_adjustment SET state_json=?,version=?,status=?,active_report_id=?,budget_operation_id=?,budget_status=?,budget_next_at=?,budget_lease_until=?,
                accrual_operation_id=?,accrual_status=?,accrual_next_at=?,accrual_lease_until=?,completed_at=?,completed_sequence=?,retired_at=?,updated_at=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND retired_at IS NULL
                """, json.write(value), value.version(), value.status().name(), activeReport(value), budget == null ? null : budget.input().command().id().toString(),
                budget == null ? null : budget.status().name(), budget == null ? null : timestamp(budget.nextAttemptAt()), budget == null ? null : timestamp(budget.leaseUntil()),
                accrual == null ? null : accrual.input().command().id().toString(), accrual == null ? null : accrual.status().name(),
                accrual == null ? null : timestamp(accrual.nextAttemptAt()), accrual == null ? null : timestamp(accrual.leaseUntil()),
                value.completion() == null ? null : timestamp(value.completion().at()), value.completion() == null ? null : sequence(basis),
                value.retirement() == null ? null : timestamp(value.retirement().at()), timestamp(value.updatedAt()), basis.tenantId(), value.id().toString(), value.version() - 1, json.write(value.input()));
        if (changed != 1) throw conflict(); append(value);
    }
    private void registerOperation(ExpensePartialAdjustment value, String side, ExpenseAdjustmentFundingSource source) {
        boolean budget = side.equals("BUDGET");
        var id = budget ? value.budget().input().command().id() : value.accrual().input().command().id();
        Object input = budget ? value.budget().input() : value.accrual().input();
        jdbc.update("INSERT INTO expense_partial_adjustment_operation(tenant_id,id,adjustment_id,side,adjustment_version,input_json,authorization_source_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                value.input().basis().tenantId(), id.toString(), value.id().toString(), side, value.version(), json.write(input), json.write(source), timestamp(value.updatedAt()));
    }
    private void requireUsedReturns(ExpensePartialAdjustmentBasis basis) {
        for (var entry : basis.funding().previousReturns()) {
            var known = jdbc.queryForList("SELECT entry_json FROM expense_partial_adjustment_return WHERE tenant_id=? AND report_id=? AND active_funds_identity=? AND completed_at IS NOT NULL",
                    String.class, basis.tenantId(), basis.reportId().toString(), entry.proof().fundsIdentity());
            if (known.size() != 1 || !entry.equals(json.read(known.get(0), ExpensePaymentReturns.Entry.class))) throw conflict();
        }
    }
    /** 新效果不能跨过前次争议；完成后无变化的查询允许增加修订，但原完成事实不能替换。 */
    private void requireCurrentPredecessor(ExpensePartialAdjustmentBasis basis) {
        var current = latestCompleted(basis.tenantId(), basis.reportId()).orElse(null);
        var expected = basis.previous();
        if (expected == null) { if (current != null) throw conflict(); return; }
        if (current == null || !current.id().equals(expected.id()) || current.version() < expected.version()
                || current.status() != ExpensePartialAdjustment.Status.APPLIED) throw conflict();
        var original = revision(basis.tenantId(), expected.id(), expected.version()).orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (!basis.equals(ExpensePartialAdjustmentBasis.from(basis.funding(), original)) || !current.input().equals(original.input())
                || !current.completion().equals(original.completion())) throw conflict();
    }
    private long sequence(ExpensePartialAdjustmentBasis basis) {
        if (basis.previous() == null) return 1;
        var previous = jdbc.queryForList("SELECT sequence_no FROM expense_partial_adjustment WHERE tenant_id=? AND id=? AND report_id=? AND completed_at IS NOT NULL",
                Long.class, basis.tenantId(), basis.previous().id().toString(), basis.reportId().toString());
        if (previous.size() != 1) throw conflict(); return Math.incrementExact(previous.get(0));
    }
    private RowMapper<ExpensePartialAdjustment> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpensePartialAdjustment.class); var basis = value.input().basis(); var financial = basis.funding().financial();
            var budget = value.budget(); var accrual = value.accrual();
            if (!basis.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id")) || !basis.reportId().toString().equals(row.getString("report_id"))
                    || financial.settlement().input().source().roundNo() != row.getInt("round_no") || row.getLong("sequence_no") != sequence(basis)
                    || financial.settlement().version() != row.getLong("settlement_version") || financial.consumption().version() != row.getLong("consumed_version")
                    || !financial.consumption().input().command().id().toString().equals(row.getString("consumption_id"))
                    || financial.accrual().version() != row.getLong("accrual_version") || !financial.accrual().input().command().id().toString().equals(row.getString("accrual_id"))
                    || !Objects.equals(basis.previous() == null ? null : basis.previous().id().toString(), row.getString("previous_id"))
                    || !Objects.equals(basis.previous() == null ? null : basis.previous().version(), row.getObject("previous_version", Long.class))
                    || !value.input().equals(json.read(row.getString("input_json"), ExpensePartialAdjustment.Input.class)) || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status")) || !Objects.equals(activeReport(value), row.getString("active_report_id"))
                    || !Objects.equals(budget == null ? null : budget.input().command().id().toString(), row.getString("budget_operation_id"))
                    || !Objects.equals(budget == null ? null : budget.status().name(), row.getString("budget_status"))
                    || !Objects.equals(budget == null ? null : budget.nextAttemptAt(), instant(row.getTimestamp("budget_next_at")))
                    || !Objects.equals(budget == null ? null : budget.leaseUntil(), instant(row.getTimestamp("budget_lease_until")))
                    || !Objects.equals(accrual == null ? null : accrual.input().command().id().toString(), row.getString("accrual_operation_id"))
                    || !Objects.equals(accrual == null ? null : accrual.status().name(), row.getString("accrual_status"))
                    || !Objects.equals(accrual == null ? null : accrual.nextAttemptAt(), instant(row.getTimestamp("accrual_next_at")))
                    || !Objects.equals(accrual == null ? null : accrual.leaseUntil(), instant(row.getTimestamp("accrual_lease_until")))
                    || !Objects.equals(value.completion() == null ? null : value.completion().at(), instant(row.getTimestamp("completed_at")))
                    || !Objects.equals(value.completion() == null ? null : sequence(basis), row.getObject("completed_sequence", Long.class))
                    || !Objects.equals(value.retirement() == null ? null : value.retirement().at(), instant(row.getTimestamp("retired_at")))
                    || !value.input().createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))) throw inconsistent();
            requireRegisteredOperations(value); return value;
        };
    }
    private void requireRegisteredOperations(ExpensePartialAdjustment value) {
        if (value.budget() != null) requireRegistered(value, "BUDGET", value.budget().input().command().id(), value.budget().input());
        if (value.accrual() != null) requireRegistered(value, "ACCRUAL", value.accrual().input().command().id(), value.accrual().input());
    }
    private void requireRegistered(ExpensePartialAdjustment value, String side, UUID id, Object input) {
        var registered = jdbc.query("SELECT * FROM expense_partial_adjustment_operation WHERE tenant_id=? AND id=? AND adjustment_id=? AND side=?", (row, index) -> {
            if (row.getLong("adjustment_version") > value.version() || !json.write(input).equals(row.getString("input_json"))) throw inconsistent();
            var source = json.read(row.getString("authorization_source_json"), ExpenseAdjustmentFundingSource.class);
            var basis = new ExpensePartialAdjustmentBasis(source, value.input().basis().previous());
            if (side.equals("BUDGET")) basis.requireBudget(value.id(), value.budget().input()); else basis.requireAccrual(value.id(), value.accrual().input());
            return true;
        }, value.input().basis().tenantId(), id.toString(), value.id().toString(), side);
        if (registered.size() != 1) throw inconsistent();
    }
    private void append(ExpensePartialAdjustment value) {
        jdbc.update("INSERT INTO expense_partial_adjustment_revision(tenant_id,adjustment_id,version,state_json) VALUES(?,?,?,?)",
                value.input().basis().tenantId(), value.id().toString(), value.version(), json.write(value));
    }
    private static String activeReport(ExpensePartialAdjustment value) { return value.completion() == null && value.retirement() == null ? value.input().basis().reportId().toString() : null; }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException pending() { return new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_PENDING", "Another partial adjustment already protects this expense"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial adjustment source, current operation or version changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted partial adjustment identity or command registration is inconsistent"); }
    /**
     * 工作器只传租户、报销和当前修订，账务内容由实际状态恢复。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, UUID reportId, long version) { }
}
