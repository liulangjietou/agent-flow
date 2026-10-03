package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.Money;
import java.sql.Timestamp;
import java.util.HashSet;
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
 * 独立资源调整只接受已消费准备、真实预算修订与完整资源反向明细。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseResourceAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcExpenseResourceAdjustmentPreparationRepository preparations;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final ExpenseReportRepository reports;
    private final ExpensePartialAdjustmentGuard partialAdjustments;
    private final ExpenseResourceReversal resourceRules = new ExpenseResourceReversal();

    /** 原授权、预算和报销修订均从本地持久来源核对，不请求外部系统。 */
    public JdbcExpenseResourceAdjustmentRepository(JdbcTemplate jdbc, JsonUtil json, JdbcExpenseResourceAdjustmentPreparationRepository preparations,
            JdbcBudgetConsumptionReversalRepository budgets, ExpenseReportRepository reports, ExpensePartialAdjustmentGuard partialAdjustments) {
        this.jdbc = jdbc; this.json = json; this.preparations = preparations; this.budgets = budgets; this.reports = reports; this.partialAdjustments = partialAdjustments;
    }

    /** 调整与预算 outbox 共用授权事务，同一报销只能有一笔未安全结束的调整。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseResourceAdjustment value, long preparationVersion) {
        var basis = value.input().basis(); partialAdjustments.requireWholeAllowed(basis.tenantId(), basis.reportId());
        var prepared = preparations.find(basis.tenantId(), value.id()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict);
        if (!value.equals(ExpenseResourceAdjustment.begin(value.input())) || prepared.version() != preparationVersion
                || !prepared.authorizedInput().equals(value.input())) throw conflict();
        jdbc.update("""
                INSERT INTO expense_resource_adjustment(tenant_id,id,report_id,round_no,preparation_version,input_json,state_json,version,status,
                active_report_id,resources_reversed,created_at,updated_at) VALUES(?,?,?,?,?,?,?,1,'WAITING_BUDGET',?,FALSE,?,?)
                """, basis.tenantId(), value.id().toString(), basis.reportId().toString(), basis.settlement().input().source().roundNo(), preparationVersion,
                json.write(value.input()), json.write(value), basis.reportId().toString(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
        append(value);
    }

    /** 使用领域转换复核前后状态，完成资源冲回还必须具备本事务完整的反向明细。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseResourceAdjustment value) {
        var before = currentBefore(value); var tenant = value.input().basis().tenantId();
        var expected = switch (value.status()) {
            case REVIEW_REQUIRED -> before.requireReview(value.issue(), value.updatedAt());
            case READY -> {
                var operation = budgets.find(tenant, value.id()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict);
                yield before.status() == ExpenseResourceAdjustment.Status.WAITING_BUDGET
                        ? before.budgetApplied(operation, value.updatedAt()) : before.retryResources(operation, value.updatedAt());
            }
            case APPLIED -> {
                requireResourceEffects(value);
                yield before.resourcesReversed() ? before.confirmCompleted(budgets.find(tenant, value.id()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict), value.updatedAt())
                        : before.applied(value.updatedAt());
            }
            default -> throw conflict();
        };
        if (!value.equals(expected)) throw conflict();
        if (value.budgetReversal() != null) {
            var accepted = budgets.revision(tenant, value.id(), value.budgetReversalVersion()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict);
            if (accepted.status() != BudgetConsumptionReversalOperation.Status.APPLIED || !accepted.input().equals(value.input().budget())
                    || !accepted.observation().equals(value.budgetReversal())) throw conflict();
        }
        save(value);
    }

    /** 结束记录、调整后状态与占用释放一并提交，迟到预算执行不能重新启用旧授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(ExpenseResourceAdjustmentRetirement record) {
        var after = record.after(); var before = currentBefore(after); var tenant = before.input().basis().tenantId();
        if (!before.equals(record.before()) || !budgets.find(tenant, before.id()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict).equals(record.stoppedBudget())) throw conflict();
        save(after);
        jdbc.update("""
                INSERT INTO expense_resource_adjustment_retirement(tenant_id,adjustment_id,before_version,after_version,stopped_budget_version,retired_by,retired_at,state_json)
                VALUES(?,?,?,?,?,?,?,?)
                """, tenant, before.id().toString(), before.version(), after.version(), record.stoppedBudget().version(), record.retiredBy(), Timestamp.from(record.retiredAt()), json.write(record));
    }

    /** 定位仅使用独立租户列，JSON 不能选择另一租户的调整。 */
    public Optional<ExpenseResourceAdjustment> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM expense_resource_adjustment WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 包括资源已完成或需复核的调整，只有明确安全结束才释放同报销占用。 */
    public Optional<ExpenseResourceAdjustment> active(String tenant, UUID report) {
        return jdbc.query("SELECT * FROM expense_resource_adjustment WHERE tenant_id=? AND active_report_id=?", row(), tenant, report.toString()).stream().findFirst();
    }
    /** 新旧办理同时保留，供财务查看原授权和结束原因。 */
    public List<ExpenseResourceAdjustment> history(String tenant, UUID report) {
        return jdbc.query("SELECT * FROM expense_resource_adjustment WHERE tenant_id=? AND report_id=? ORDER BY created_at,id", row(), tenant, report.toString());
    }
    /** 工作器只扫描预算已确认、资源尚未完成的有界候选。 */
    public List<Candidate> ready() {
        return jdbc.query("SELECT tenant_id,id,report_id,version FROM expense_resource_adjustment WHERE status='READY' ORDER BY updated_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), UUID.fromString(row.getString("report_id")), row.getLong("version")));
    }
    /** 结束证明引用准确的前后状态，读取时再次校验独立身份。 */
    public Optional<ExpenseResourceAdjustment> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM expense_resource_adjustment_revision WHERE tenant_id=? AND adjustment_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpenseResourceAdjustment.class);
            if (!value.input().basis().tenantId().equals(tenant) || !value.id().equals(id) || value.version() != version) throw inconsistent(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }
    /** 安全结束不删除授权历史，后续办理可以展示这份独立记录。 */
    public Optional<ExpenseResourceAdjustmentRetirement> retirement(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM expense_resource_adjustment_retirement WHERE tenant_id=? AND adjustment_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpenseResourceAdjustmentRetirement.class);
            if (!value.before().input().basis().tenantId().equals(tenant) || !value.before().id().equals(id)
                    || value.before().version() != row.getLong("before_version") || value.after().version() != row.getLong("after_version")
                    || value.stoppedBudget().version() != row.getLong("stopped_budget_version") || !value.retiredBy().equals(row.getString("retired_by"))
                    || !value.retiredAt().equals(row.getTimestamp("retired_at").toInstant())) throw inconsistent(); return value;
        }, tenant, id.toString()).stream().findFirst();
    }
    private ExpenseResourceAdjustment currentBefore(ExpenseResourceAdjustment after) {
        var before = find(after.input().basis().tenantId(), after.id()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict);
        if (!before.input().equals(after.input()) || !before.createdAt().equals(after.createdAt()) || before.version() + 1 != after.version()
                || before.status() == ExpenseResourceAdjustment.Status.RETIRED) throw conflict(); return before;
    }
    private void requireResourceEffects(ExpenseResourceAdjustment value) {
        var basis = value.input().basis(); var report = reports.find(basis.tenantId(), basis.reportId()).orElseThrow(JdbcExpenseResourceAdjustmentRepository::conflict); basis.requireReport(report);
        var required = resourceRules.requirements(report);
        var actual = jdbc.query("SELECT * FROM finance_consumption_reversal WHERE tenant_id=? AND adjustment_id=?", (row, index) -> {
            if (row.getTimestamp("reversed_at").toInstant().isAfter(value.updatedAt())) throw conflict();
            return new ExpenseResourceReversal.Consumption(ExpenseResourceReversal.Kind.valueOf(row.getString("resource_type")), UUID.fromString(row.getString("resource_id")), row.getInt("source_line"),
                    new ExpenseUse(UUID.fromString(row.getString("report_id")), row.getInt("round_no"), row.getInt("report_line")), row.getBigDecimal("amount") == null ? null : new Money(row.getBigDecimal("amount"), row.getString("currency")));
        }, basis.tenantId(), value.id().toString());
        if (required.size() != actual.size() || !new HashSet<>(required).equals(new HashSet<>(actual))) throw conflict();
    }
    private void save(ExpenseResourceAdjustment value) {
        var basis = value.input().basis();
        int changed = jdbc.update("""
                UPDATE expense_resource_adjustment SET state_json=?,version=?,status=?,active_report_id=?,budget_reversal_version=?,resources_reversed=?,issue=?,updated_at=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND status<>'RETIRED'
                """, json.write(value), value.version(), value.status().name(), value.status() == ExpenseResourceAdjustment.Status.RETIRED ? null : basis.reportId().toString(),
                value.budgetReversalVersion(), value.resourcesReversed(), value.issue(), Timestamp.from(value.updatedAt()), basis.tenantId(), value.id().toString(), value.version() - 1, json.write(value.input()));
        if (changed != 1) throw conflict(); append(value);
    }
    private RowMapper<ExpenseResourceAdjustment> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpenseResourceAdjustment.class); var basis = value.input().basis();
            if (!value.input().equals(json.read(row.getString("input_json"), ExpenseResourceAdjustment.Input.class)) || !basis.tenantId().equals(row.getString("tenant_id"))
                    || !value.id().toString().equals(row.getString("id")) || !basis.reportId().toString().equals(row.getString("report_id"))
                    || basis.settlement().input().source().roundNo() != row.getInt("round_no") || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !Objects.equals(value.status() == ExpenseResourceAdjustment.Status.RETIRED ? null : basis.reportId().toString(), row.getString("active_report_id"))
                    || !Objects.equals(value.budgetReversalVersion(), row.getObject("budget_reversal_version", Long.class)) || value.resourcesReversed() != row.getBoolean("resources_reversed")
                    || !Objects.equals(value.issue(), row.getString("issue")) || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) throw inconsistent(); return value;
        };
    }
    private void append(ExpenseResourceAdjustment value) {
        jdbc.update("INSERT INTO expense_resource_adjustment_revision(tenant_id,adjustment_id,version,state_json) VALUES(?,?,?,?)",
                value.input().basis().tenantId(), value.id().toString(), value.version(), json.write(value));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense resource adjustment source, result or version changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted expense resource adjustment identity is inconsistent"); }
    /**
     * 调度只传原报销定位与乐观版本。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, UUID reportId, long version) { }
}
