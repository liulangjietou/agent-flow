package io.agentflow.expense;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.FinancialResourceReversalJournalMapper;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.ReservedAmount;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三类资源的核销反向事实与原资源修订关联，防止直接恢复快照绕过已授权调整。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class FinancialResourceReversalJournal {
    private final FinancialResourceReversalJournalMapper sqlMapper;
    private final JsonUtil json;
    private final ExpenseReportRepository reports;
    private final JdbcBudgetConsumptionReversalRepository budgets;
    private final FinancialResourceReductionJournal reductions;
    private final ExpenseResourceReversal resourceRules = new ExpenseResourceReversal();

    /** 共用本地事务，外部预算已由工作器确认，这里不做网络调用。 */
    public FinancialResourceReversalJournal(
            FinancialResourceReversalJournalMapper sqlMapper,
            JsonUtil json,
            ExpenseReportRepository reports,
            JdbcBudgetConsumptionReversalRepository budgets,
            FinancialResourceReductionJournal reductions) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.reports = reports;
        this.budgets = budgets;
        this.reductions = reductions;
    }

    /** 新资源没有历史核销，更不能携带其他资源的冲回证据。 */
    public void requireInitial(FinancialResourceStore.Kind kind, FinancialResourceStore.Stored value) {
        if (!entries(kind, value).isEmpty()) throw conflict();
    }

    /** 旧反向事实只增不改，每个相邻修订最多转换一笔原核销，其余聚合状态必须原样保留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Entry prepare(
            FinancialResourceStore.Kind kind,
            FinancialResourceStore.Stored before,
            FinancialResourceStore.Stored after,
            String operation) {
        var original = entries(kind, before);
        var changed = entries(kind, after);
        if (!changed.containsAll(original)) throw conflict();
        var added = changed.stream().filter(value -> !original.contains(value)).toList();
        if (added.isEmpty()) {
            if (ExpenseSubmissionResources.Operation.REVERSE_CONSUMPTION.name().equals(operation)
                    || ExpenseSubmissionResources.Operation.REDUCE_CONSUMPTION
                            .name()
                            .equals(operation)) throw conflict();
            return null;
        }
        boolean partial =
                ExpenseSubmissionResources.Operation.REDUCE_CONSUMPTION.name().equals(operation);
        if (added.size() != 1
                || !partial
                        && !ExpenseSubmissionResources.Operation.REVERSE_CONSUMPTION
                                .name()
                                .equals(operation)) throw conflict();
        var entry = added.get(0);
        if (entry.partial() != (partial && kind != FinancialResourceStore.Kind.INVOICE))
            throw conflict();
        requireExactTransition(kind, before, after, entry);
        if (partial) {
            reductions.requireEffect(kind, before, entry);
            return entry;
        }
        var use = entry.consumption().use();
        reports.lock(after.tenantId(), use.reportId());
        var adjustment =
                SqlRows.map(
                                sqlMapper.prepare(
                                        after.tenantId(), entry.adjustmentId().toString()),
                                row ->
                                        json.read(
                                                row.getString("state_json"),
                                                ExpenseResourceAdjustment.class))
                        .stream()
                        .findFirst()
                        .orElseThrow(FinancialResourceReversalJournal::conflict);
        if (adjustment.status() != ExpenseResourceAdjustment.Status.READY
                || adjustment.resourcesReversed()
                || !adjustment.id().equals(entry.adjustmentId())
                || !adjustment.input().basis().tenantId().equals(after.tenantId())
                || !adjustment.input().basis().reportId().equals(use.reportId())
                || entry.reversedAt().isBefore(adjustment.updatedAt())) throw conflict();
        var budget =
                budgets.find(after.tenantId(), adjustment.id())
                        .orElseThrow(FinancialResourceReversalJournal::conflict);
        adjustment.requireAcceptedBudget(budget);
        if (entry.reversedAt().isBefore(budget.updatedAt())) throw conflict();
        var report =
                reports.find(after.tenantId(), use.reportId())
                        .orElseThrow(FinancialResourceReversalJournal::conflict);
        adjustment.input().basis().requireReport(report);
        if (!resourceRules.requirements(report).contains(entry.consumption())) throw conflict();
        requireOriginalUse(kind, before, entry);
        return entry;
    }

    /** 后修订写入后追加规范明细，外键或唯一归属冲突使整笔资源事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            FinancialResourceStore.Kind kind,
            FinancialResourceStore.Stored after,
            Entry entry,
            String operation) {
        if (entry == null) return;
        if (ExpenseSubmissionResources.Operation.REDUCE_CONSUMPTION.name().equals(operation)) {
            reductions.append(kind, after, entry);
            return;
        }
        var original = entry.consumption();
        var amount = original.amount();
        var use = original.use();
        sqlMapper.append(
                after.tenantId(),
                kind.name(),
                after.id().toString(),
                original.sourceLine(),
                use.reportId().toString(),
                use.roundNo(),
                use.lineNo(),
                entry.adjustmentId().toString(),
                after.version() - 1,
                after.version(),
                amount == null ? null : amount.value(),
                amount == null ? null : amount.currency(),
                Timestamp.from(entry.reversedAt()));
    }

    private void requireExactTransition(FinancialResourceStore.Kind kind, FinancialResourceStore.Stored before, FinancialResourceStore.Stored after, Entry entry) {
        var required = entry.consumption();
        boolean matches = switch (kind) {
            case INVOICE -> {
                var original = Invoice.restore(json.read(before.state(), Invoice.State.class));
                original.reverseConsumption(before.version(), required.use(), entry.adjustmentId(), entry.reversedAt());
                yield original.state().equals(Invoice.restore(json.read(after.state(), Invoice.State.class)).state());
            }
            case PRIOR_REQUEST -> {
                var original = ExpenseRequest.restore(json.read(before.state(), ExpenseRequest.State.class));
                if (entry.partial()) original.reduceConsumption(before.version(), required.sourceLine(), required.use(), required.amount(), entry.adjustmentId(), entry.reversedAt());
                else original.reverseConsumption(before.version(), required.sourceLine(), required.use(), entry.adjustmentId(), entry.reversedAt());
                yield original.state().equals(ExpenseRequest.restore(json.read(after.state(), ExpenseRequest.State.class)).state());
            }
            case ADVANCE -> {
                var original = EmployeeAdvance.restore(json.read(before.state(), EmployeeAdvance.State.class));
                if (entry.partial()) original.reduceOffset(before.version(), required.use(), required.amount(), entry.adjustmentId(), entry.reversedAt());
                else original.reverseOffset(before.version(), required.use(), entry.adjustmentId(), entry.reversedAt());
                yield original.state().equals(EmployeeAdvance.restore(json.read(after.state(), EmployeeAdvance.State.class)).state());
            }
        };
        if (!matches) throw conflict();
    }

    private void requireOriginalUse(
            FinancialResourceStore.Kind kind, FinancialResourceStore.Stored original, Entry entry) {
        var required = entry.consumption();
        var use = required.use();
        Integer found;
        if (kind == FinancialResourceStore.Kind.INVOICE) {
            var invoice = Invoice.restore(json.read(original.state(), Invoice.State.class));
            found =
                    SqlRows.single(
                            sqlMapper.requireOriginalUse(
                                    original.tenantId(),
                                    original.id().toString(),
                                    invoice.facts().key().canonical(),
                                    use.reportId().toString(),
                                    use.roundNo(),
                                    use.lineNo()));
        } else {
            found =
                    SqlRows.single(
                            sqlMapper.requireOriginalUse2(
                                    original.tenantId(),
                                    kind.name(),
                                    original.id().toString(),
                                    required.sourceLine(),
                                    use.reportId().toString(),
                                    use.roundNo(),
                                    use.lineNo(),
                                    required.amount().value(),
                                    required.amount().currency()));
        }
        if (!Integer.valueOf(1).equals(found)) throw conflict();
    }

    private List<Entry> entries(FinancialResourceStore.Kind kind, FinancialResourceStore.Stored resource) {
        if (kind == FinancialResourceStore.Kind.INVOICE) return Invoice.restore(json.read(resource.state(), Invoice.State.class)).reversals().stream().map(value -> new Entry(
                new ExpenseResourceReversal.Consumption(ExpenseResourceReversal.Kind.INVOICE, resource.id(), 0, value.use(), null), value.adjustmentId(), value.reversedAt(), false)).toList();
        Map<Integer, ReservedAmount> balances = kind == FinancialResourceStore.Kind.ADVANCE
                ? Map.of(0, EmployeeAdvance.restore(json.read(resource.state(), EmployeeAdvance.State.class)).balance())
                : ExpenseRequest.restore(json.read(resource.state(), ExpenseRequest.State.class)).balances();
        var result = new ArrayList<Entry>();
        balances.forEach((line, balance) -> {
            balance.reversals().forEach(value -> result.add(new Entry(
                    new ExpenseResourceReversal.Consumption(ExpenseResourceReversal.Kind.valueOf(kind.name()), resource.id(), line, value.use(), value.amount()), value.adjustmentId(), value.reversedAt(), false)));
            // 部分冲回同样属于已核销资源的反向事实，不能借普通预留修改绕过独立调整凭据。
            balance.reductions().forEach(value -> result.add(new Entry(
                    new ExpenseResourceReversal.Consumption(ExpenseResourceReversal.Kind.valueOf(kind.name()), resource.id(), line, value.use(), value.amount()), value.adjustmentId(), value.reducedAt(), true)));
        });
        return List.copyOf(result);
    }

    private static DomainException conflict() {
        return new DomainException(
                "EXPENSE_CONSUMPTION_REVERSAL_UNAUTHORIZED",
                "Resource reversal requires an unchanged original consumption, accepted budget"
                    + " reversal and authorized adjustment");
    }

    /**
     * 仓储内部的精确反向事实；完整取消与部分差额不得互换，也不作为客户端可编辑输入。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Entry(
            ExpenseResourceReversal.Consumption consumption,
            UUID adjustmentId,
            Instant reversedAt,
            boolean partial) {}
}
