package io.agentflow.expense.reporting;


import io.agentflow.common.CurrentActor;
import io.agentflow.expense.reporting.mapper.ExpenseReportingOperationsMapper;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.JdbcVoucherOperationRepository;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentOperation;
import io.agentflow.finance.VoucherOperation;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 首次真实到账与当前操作积压分别读取，后续退回不能覆盖原到账时刻。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReportingOperations {
    private final ExpenseReportingOperationsMapper sqlMapper;
    private final CurrentActor actors;
    private final ExpenseReportScope scope;
    private final JdbcPaymentOperationRepository payments;
    private final JdbcVoucherOperationRepository vouchers;

    /** 只依赖已经保存的原操作和修订，不触发付款、制证或重新查询。 */
    public ExpenseReportingOperations(
            ExpenseReportingOperationsMapper sqlMapper,
            CurrentActor actors,
            ExpenseReportScope scope,
            JdbcPaymentOperationRepository payments,
            JdbcVoucherOperationRepository vouchers) {
        this.sqlMapper = sqlMapper;
        this.actors = actors;
        this.scope = scope;
        this.payments = payments;
        this.vouchers = vouchers;
    }

    /** 零应付没有到账样本；存在成功修订时保留最早真实到账。 */
    public Arrival arrival(ExpenseReportScope.Expense source) {
        var expense = source.view();
        if (expense.financialRound().payable().value().signum() == 0)
            return new Arrival(ExpenseFinancialMetrics.Payment.NOT_REQUIRED, null);
        String tenant = actors.actor().tenantId();
        var ids =
                sqlMapper.arrival(
                        tenant,
                        expense.id().toString(),
                        expense.applicationId().toString(),
                        expense.roundNo());
        Instant first = null;
        boolean unknown = false;
        for (String id : ids) {
            var current =
                    payments.find(tenant, UUID.fromString(id))
                            .orElseThrow(ExpenseReportScope::unavailable);
            var command = current.input().command();
            var binding = command.binding();
            if (command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT
                    || !binding.businessId().equals(expense.id())
                    || !binding.applicationId().equals(expense.applicationId())
                    || binding.roundNo() != expense.roundNo()
                    || !command.amount().equals(expense.financialRound().payable()))
                throw ExpenseReportScope.unavailable();
            var paid = payments.firstSuccessfulRevision(tenant, UUID.fromString(id));
            if (paid.isPresent()) {
                if (!paid.get().input().equals(current.input()))
                    throw ExpenseReportScope.unavailable();
                Instant at = paid.get().observation().completedAt();
                if (first == null || at.isBefore(first)) first = at;
            }
            unknown |=
                    switch (current.status()) {
                        case UNKNOWN, RECONCILING, REVERSED, SUCCEEDED -> true;
                        default -> false;
                    };
        }
        return first != null
                ? new Arrival(ExpenseFinancialMetrics.Payment.CONFIRMED, first)
                : new Arrival(
                        unknown
                                ? ExpenseFinancialMetrics.Payment.UNKNOWN
                                : ExpenseFinancialMetrics.Payment.AWAITING,
                        null);
    }

    /** 积压与当前生成时点一致，不受提交日期窗口裁剪；原组织和类别筛选仍适用。 */
    public ExpenseFinancialReport.Backlog backlog(
            ExpenseReportQueryParameters.Query query, Instant now) {
        String tenant = actors.actor().tenantId();
        Map<String, Long> voucherCounts = new TreeMap<>(), paymentCounts = new TreeMap<>();
        Arrays.stream(VoucherOperation.Status.values())
                .filter(status -> status != VoucherOperation.Status.POSTED)
                .forEach(status -> voucherCounts.put(status.name(), 0L));
        Arrays.stream(PaymentOperation.Status.values())
                .filter(status -> status != PaymentOperation.Status.SUCCEEDED)
                .forEach(status -> paymentCounts.put(status.name(), 0L));
        var voucherRows =
                SqlRows.map(
                        sqlMapper.backlog(tenant),
                        row ->
                                new Candidate(
                                        UUID.fromString(row.getString("id")),
                                        row.getString("business_type"),
                                        UUID.fromString(row.getString("business_id")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getInt("round_no")));
        for (var candidate : voucherRows)
            if (allowed(candidate, query)) {
                var operation =
                        vouchers.find(tenant, candidate.id())
                                .orElseThrow(ExpenseReportScope::unavailable);
                voucherCounts.merge(operation.status().name(), 1L, Long::sum);
            }
        var paymentRows =
                SqlRows.map(
                        sqlMapper.backlog2(tenant),
                        row ->
                                new Candidate(
                                        UUID.fromString(row.getString("id")),
                                        row.getString("business_type"),
                                        UUID.fromString(row.getString("business_id")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getInt("round_no")));
        for (var candidate : paymentRows)
            if (allowed(candidate, query)) {
                var operation =
                        payments.find(tenant, candidate.id())
                                .orElseThrow(ExpenseReportScope::unavailable);
                var binding = operation.input().command().binding();
                if (!binding.businessId().equals(candidate.businessId())
                        || !binding.applicationId().equals(candidate.applicationId())
                        || binding.roundNo() != candidate.round())
                    throw ExpenseReportScope.unavailable();
                paymentCounts.merge(operation.status().name(), 1L, Long::sum);
            }
        return new ExpenseFinancialReport.Backlog(
                now, Map.copyOf(voucherCounts), Map.copyOf(paymentCounts));
    }

    private boolean allowed(Candidate candidate, ExpenseReportQueryParameters.Query query) {
        if (candidate.type().equals("EXPENSE")) return scope.expense(candidate.businessId(), candidate.round()).filter(value -> {
            if (!value.view().applicationId().equals(candidate.applicationId())) throw ExpenseReportScope.unavailable();
            return value.matches(query);
        }).isPresent();
        if (candidate.type().equals("ADVANCE_REQUEST")) return query.categoryCode() == null
                && scope.advance(candidate.businessId(), candidate.round()).filter(value -> {
                    if (!value.view().applicationId().equals(candidate.applicationId())) throw ExpenseReportScope.unavailable();
                    return ExpenseReportScope.organization(query, value.original());
                }).isPresent();
        throw ExpenseReportScope.unavailable();
    }

    /**
     * 尚未读取敏感正文的操作索引。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Candidate(
            UUID id, String type, UUID businessId, UUID applicationId, int round) {}

    /**
     * 原付款结论及可为空的首次到账时刻。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Arrival(ExpenseFinancialMetrics.Payment state, Instant at) {}
}
