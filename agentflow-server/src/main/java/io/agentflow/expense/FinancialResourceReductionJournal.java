package io.agentflow.expense;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.FinancialResourceReductionJournalMapper;
import io.agentflow.finance.ReservedAmount;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * 部分调整的资源差额只接受真实两侧成功及原净核销，明细与资源修订同事务保存。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class FinancialResourceReductionJournal {
    private final FinancialResourceReductionJournalMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpenseResourceReduction rules = new ExpenseResourceReduction();

    /** 采用实际调整仓储核对接受事实，不能从传入资源快照制造财务完成。 */
    public FinancialResourceReductionJournal(
            FinancialResourceReductionJournalMapper sqlMapper,
            JsonUtil json,
            JdbcExpensePartialAdjustmentRepository adjustments) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.adjustments = adjustments;
    }

    /** 逐笔核对原额、事前净额和此次差额；发票只有整行实际取消才解除占用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireEffect(
            FinancialResourceStore.Kind kind,
            FinancialResourceStore.Stored before,
            FinancialResourceReversalJournal.Entry entry) {
        var adjustment =
                adjustments
                        .find(before.tenantId(), entry.adjustmentId())
                        .orElseThrow(FinancialResourceReductionJournal::conflict);
        adjustments.requireCompletionSource(adjustment);
        var change = adjustment.input().basis().funding().financial().change();
        var report = change.before().original();
        if (entry.reversedAt().isBefore(adjustment.updatedAt())
                || !report.employeeId().equals(before.ownerId())) throw conflict();
        var required =
                rules.requirements(change).stream()
                        .filter(value -> value.effect().equals(entry.consumption()))
                        .findFirst()
                        .orElseThrow(FinancialResourceReductionJournal::conflict);
        var effect = required.effect();
        var use = effect.use();
        Integer found;
        if (kind == FinancialResourceStore.Kind.INVOICE) {
            var invoice = Invoice.restore(json.read(before.state(), Invoice.State.class));
            if (invoice.facts() == null
                    || !invoice.facts().legalEntityId().equals(report.content().legalEntityId()))
                throw conflict();
            found =
                    SqlRows.single(
                            sqlMapper.requireEffect(
                                    before.tenantId(),
                                    before.id().toString(),
                                    invoice.facts().key().canonical(),
                                    use.reportId().toString(),
                                    use.roundNo(),
                                    use.lineNo()));
        } else {
            ReservedAmount balance;
            if (kind == FinancialResourceStore.Kind.PRIOR_REQUEST) {
                var request =
                        ExpenseRequest.restore(
                                json.read(before.state(), ExpenseRequest.State.class));
                if (!request.legalEntityId().equals(report.content().legalEntityId()))
                    throw conflict();
                balance = request.balance(effect.sourceLine());
            } else {
                var advance =
                        EmployeeAdvance.restore(
                                json.read(before.state(), EmployeeAdvance.State.class));
                if (!advance.legalEntityId().equals(report.content().legalEntityId()))
                    throw conflict();
                balance = advance.balance();
            }
            if (!balance.consumedAmountFor(use).equals(required.originalAmount())
                    || !balance.netConsumedAmountFor(use).equals(required.beforeAmount()))
                throw conflict();
            found =
                    SqlRows.single(
                            sqlMapper.requireEffect2(
                                    before.tenantId(),
                                    kind.name(),
                                    before.id().toString(),
                                    effect.sourceLine(),
                                    use.reportId().toString(),
                                    use.roundNo(),
                                    use.lineNo(),
                                    required.originalAmount().value(),
                                    required.originalAmount().currency()));
        }
        if (!Objects.equals(found, 1)) throw conflict();
    }

    /** 每个调整对同一原归属只追加一次，相邻资源修订及原报销归属由外键固定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            FinancialResourceStore.Kind kind,
            FinancialResourceStore.Stored after,
            FinancialResourceReversalJournal.Entry entry) {
        var effect = entry.consumption();
        var use = effect.use();
        var amount = effect.amount();
        sqlMapper.append(
                after.tenantId(),
                entry.adjustmentId().toString(),
                kind.name(),
                after.id().toString(),
                effect.sourceLine(),
                use.reportId().toString(),
                use.roundNo(),
                use.lineNo(),
                after.version() - 1,
                after.version(),
                amount == null ? null : amount.value(),
                amount == null ? null : amount.currency(),
                Timestamp.from(entry.reversedAt()));
    }

    private static DomainException conflict() {
        return new DomainException(
                "EXPENSE_CONSUMPTION_REVERSAL_UNAUTHORIZED",
                "Partial resource effect requires actual accepted finance, original consumption and"
                    + " exact remaining amount");
    }
}
