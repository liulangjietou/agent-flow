package io.agentflow.expense;

import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetConsumptionReductionObservation;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionObservation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立裁决历史从真实原修订恢复，具名证明和相邻状态逐条回放，不把当前 JSON 当作完整历史。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePartialDisputeRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 只负责本地历史与证明，不查询外部系统，也不自行改变调整状态。 */
    public JdbcExpensePartialDisputeRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 原操作可能已经换过未执行的旧授权，历史只收集当前固定命令及其全部真实观察。 */
    public BudgetConsumptionReductionOperation.ResolutionHistory budgetHistory(ExpensePartialAdjustment current) {
        if (current.budget() == null) throw inconsistent();
        var input = current.budget().input(); BudgetConsumptionReductionObservation first = null; boolean effect = false; Instant latest = null;
        for (var value : revisions(current)) {
            var operation = value.budget();
            if (operation == null || !operation.input().command().id().equals(input.command().id())) continue;
            if (!input.equals(operation.input())) throw inconsistent();
            var observed = operation.observation(); var candidate = operation.conflictingObservation();
            if (first == null && observed != null && observed.status() == BudgetConsumptionReductionObservation.Status.APPLIED) first = observed;
            effect |= BudgetConsumptionReductionOperation.applicationRisk(observed) || BudgetConsumptionReductionOperation.applicationRisk(candidate);
            latest = latest(latest, observed == null ? null : observed.observedAt(), candidate == null ? null : candidate.observedAt());
        }
        return new BudgetConsumptionReductionOperation.ResolutionHistory(first, effect, latest);
    }

    /** 已被后续候选覆盖的过账仍属于历史效果，不能因当前只剩失败候选而消失。 */
    public ExpenseAccrualReductionOperation.ResolutionHistory accrualHistory(ExpensePartialAdjustment current) {
        if (current.accrual() == null) throw inconsistent();
        var input = current.accrual().input(); ExpenseAccrualReductionObservation first = null; boolean effect = false; Instant latest = null;
        for (var value : revisions(current)) {
            var operation = value.accrual();
            if (operation == null || !operation.input().command().id().equals(input.command().id())) continue;
            if (!input.equals(operation.input())) throw inconsistent();
            var observed = operation.observation(); var candidate = operation.conflictingObservation();
            if (first == null && ExpenseAccrualReductionOperation.posted(observed)) first = observed;
            effect |= ExpenseAccrualReductionOperation.posted(observed) || ExpenseAccrualReductionOperation.posted(candidate);
            latest = latest(latest, observed == null ? null : observed.observedAt(), candidate == null ? null : candidate.observedAt());
        }
        return new ExpenseAccrualReductionOperation.ResolutionHistory(first, effect, latest);
    }

    /** 按服务端实际历史回放决定，普通状态转换不借用这个入口。 */
    public ExpensePartialAdjustment resolve(ExpensePartialDisputeResolution decision, ExpensePartialAdjustment before) {
        return decision.side() == ExpensePartialAdjustmentPreparation.Side.BUDGET ? decision.resolve(before, budgetHistory(before)) : decision.resolve(before, accrualHistory(before));
    }

    /** 根修订已在同一事务保存，决定计数、真实侧和实际候选必须共同相符。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ExpensePartialDisputeResolution decision, ExpensePartialAdjustment before, ExpensePartialAdjustment after) {
        if (!resolve(decision, before).equals(after)) throw inconsistent();
        jdbc.update("""
                INSERT INTO expense_partial_adjustment_dispute(tenant_id,id,adjustment_id,side,operation_id,sequence_no,before_version,after_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, decision.tenantId(), decision.id().toString(), decision.adjustmentId().toString(), decision.side().name(), decision.operationId().toString(), after.resolutionCount(),
                decision.beforeVersion(), decision.afterVersion(), decision.outcome().name(), decision.resolvedBy(), Timestamp.from(time(decision.observedAt())), Timestamp.from(time(decision.resolvedAt())), json.write(decision));
    }

    /** 只读取该根修订已包含的决定；并发新增不污染旧快照，缺失或替换的既有证明仍拒绝恢复。 */
    public List<ExpensePartialDisputeResolution> recorded(ExpensePartialAdjustment current) {
        var decisions = jdbc.query("SELECT * FROM expense_partial_adjustment_dispute WHERE tenant_id=? AND adjustment_id=? AND after_version<=? ORDER BY sequence_no", (row, index) -> {
            var decision = json.read(row.getString("state_json"), ExpensePartialDisputeResolution.class);
            if (!decision.tenantId().equals(current.input().basis().tenantId()) || !decision.adjustmentId().equals(current.id())
                    || !decision.id().toString().equals(row.getString("id")) || !decision.side().name().equals(row.getString("side"))
                    || !decision.operationId().toString().equals(row.getString("operation_id")) || row.getInt("sequence_no") != index + 1
                    || decision.beforeVersion() != row.getLong("before_version") || decision.afterVersion() != row.getLong("after_version")
                    || decision.afterVersion() > current.version() || !decision.outcome().name().equals(row.getString("outcome"))
                    || !decision.resolvedBy().equals(row.getString("resolved_by")) || !time(decision.observedAt()).equals(row.getTimestamp("observed_at").toInstant())
                    || !time(decision.resolvedAt()).equals(row.getTimestamp("resolved_at").toInstant())) throw inconsistent();
            var before = revision(current, decision.beforeVersion()); var after = revision(current, decision.afterVersion());
            if (before.resolutionCount() != index || after.resolutionCount() != index + 1 || !resolve(decision, before).equals(after)) throw inconsistent();
            return decision;
        }, current.input().basis().tenantId(), current.id().toString(), current.version());
        if (decisions.size() != current.resolutionCount()) throw inconsistent();
        return decisions;
    }

    private ExpensePartialAdjustment revision(ExpensePartialAdjustment current, long version) {
        var values = jdbc.query("SELECT state_json FROM expense_partial_adjustment_revision WHERE tenant_id=? AND adjustment_id=? AND version=?",
                (row, index) -> json.read(row.getString("state_json"), ExpensePartialAdjustment.class), current.input().basis().tenantId(), current.id().toString(), version);
        if (values.size() != 1 || values.get(0).version() != version || !values.get(0).input().equals(current.input())) throw inconsistent();
        return values.get(0);
    }
    /** 返回截至指定修订的连续原记录，供争议证明及原编号通知共同核对。 */
    public List<ExpensePartialAdjustment> revisions(ExpensePartialAdjustment current) {
        var values = jdbc.query("SELECT version,state_json FROM expense_partial_adjustment_revision WHERE tenant_id=? AND adjustment_id=? AND version<=? ORDER BY version", (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpensePartialAdjustment.class);
            if (value.version() != index + 1L || value.version() != row.getLong("version") || !value.input().equals(current.input())) throw inconsistent();
            return value;
        }, current.input().basis().tenantId(), current.id().toString(), current.version());
        if (values.isEmpty() || !values.get(values.size() - 1).equals(current)) throw inconsistent();
        return values;
    }
    private static Instant latest(Instant previous, Instant observed, Instant candidate) {
        var result = previous;
        if (observed != null && (result == null || observed.isAfter(result))) result = observed;
        if (candidate != null && (result == null || candidate.isAfter(result))) result = candidate;
        return result;
    }
    // 数据库时间列使用微秒；JSON 保留外部纳秒原件，比较相邻凭据时仍使用完整原值。
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted partial adjustment dispute history or adjacent proof is inconsistent"); }
}
