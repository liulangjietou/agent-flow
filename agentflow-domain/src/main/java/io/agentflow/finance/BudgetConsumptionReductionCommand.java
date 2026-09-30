package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 已核销预算的独立差额指令；上次结果必须由应用服务从真实完成调整恢复后提供。
 * @author owlzhangfq@gmail.com
 */
public record BudgetConsumptionReductionCommand(UUID id, UUID adjustmentId, BudgetCommand source, BudgetObservation consumed,
        BudgetConsumptionReductionObservation previous, List<BudgetPrecheckPort.Allocation> before, List<BudgetPrecheckPort.Allocation> after,
        AccountingPeriodPort.OpenPeriod period, String authorizedBy, String evidenceReference, String reason, Instant createdAt, Instant expiresAt) {
    public static final Duration MAX_AUTHORIZATION_AGE = Duration.ofMinutes(5);

    /** 先固定原消费，再逐项减少当前净额；期间、授权和前次结果不能在重发时更换。 */
    public BudgetConsumptionReductionCommand {
        if (id == null || adjustmentId == null || source == null || id.equals(source.id()) || source.action() != BudgetCommand.Action.CONSUME
                || consumed == null || consumed.status() != BudgetObservation.Status.APPLIED || createdAt == null
                || !consumed.matches(source, false, createdAt) || consumed.ledgerRevision() == Long.MAX_VALUE
                || !BudgetConsumptionReductionObservation.text(consumed.reference(), 128)
                || !BudgetConsumptionReductionObservation.validReduction(before, after) || period == null
                || !BudgetConsumptionReductionObservation.text(authorizedBy, 128) || authorizedBy.equals(source.position().employeeId())
                || !BudgetConsumptionReductionObservation.text(evidenceReference, 128) || !BudgetConsumptionReductionObservation.text(reason, 2000)
                || expiresAt == null || !expiresAt.isAfter(createdAt) || expiresAt.isAfter(createdAt.plus(MAX_AUTHORIZATION_AGE))
                || expiresAt.isAfter(period.validUntil()) || expiresAt.isAfter(period.observedAt().plus(MAX_AUTHORIZATION_AGE))) throw invalid();
        var original = source.position();
        if (before.size() != original.allocations().size()) throw invalid();
        for (int index = 0; index < before.size(); index++) {
            var position = original.allocations().get(index);
            if (!BudgetConsumptionReductionObservation.samePosition(position, before.get(index))
                    || before.get(index).cost().amount().compareTo(position.cost().amount()) > 0) throw invalid();
        }
        var request = period.request();
        if (!request.legalEntityId().equals(original.legalEntityId()) || !request.currency().equals(original.baseCurrency())
                || request.accountingDate().isBefore(original.accountingDate()) || !period.matches(request, createdAt)) throw invalid();
        if (previous == null) {
            if (!before.equals(original.allocations())) throw invalid();
        } else {
            if (previous.status() != BudgetConsumptionReductionObservation.Status.APPLIED || previous.operationId().equals(id)
                    || previous.adjustmentId().equals(adjustmentId) || previous.observedAt().isAfter(createdAt)) throw invalid();
            var posting = previous.posting();
            if (!posting.consumptionId().equals(source.id()) || !posting.consumptionDigest().equals(source.digest())
                    || !posting.consumptionReference().equals(consumed.reference()) || posting.ledgerRevision() <= consumed.ledgerRevision()
                    || posting.ledgerRevision() == Long.MAX_VALUE || posting.reference().equals(consumed.reference())
                    || posting.appliedAt().isBefore(consumed.appliedAt()) || !posting.afterDigest().equals(positionsDigest(before))
                    || !posting.reducedAmount().currency().equals(original.baseCurrency()) || posting.reducedAmount().compareTo(original.total()) > 0
                    || posting.accountingDate().isBefore(original.accountingDate()) || request.accountingDate().isBefore(posting.accountingDate())) throw invalid();
        }
        before = List.copyOf(before);
        after = List.copyOf(after);
    }

    /** 报销应用编排只采用已核验原财务来源派生的完整预算位置，不接收页面自行拼装的分摊。 */
    public static BudgetConsumptionReductionCommand forExpense(UUID id, UUID adjustmentId, ExpenseAdjustmentFinancialSource source,
            BudgetConsumptionReductionObservation previous, AccountingPeriodPort.OpenPeriod period, String actor, String evidence, String reason,
            Instant createdAt, Instant expiresAt) {
        if (source == null) throw invalid();
        return new BudgetConsumptionReductionCommand(id, adjustmentId, source.consumption().input().command(), source.consumption().observation(),
                previous, source.budgetBefore(), source.budgetAfter(), period, actor, evidence, reason, createdAt, expiresAt);
    }

    /** 外部按同一单据的原占用或前次独立结果进行版本比较，不能先释放再补写。 */
    public BudgetCommand.Expected expected() {
        return previous == null ? new BudgetCommand.Expected(consumed.ledgerRevision(), consumed.reference())
                : new BudgetCommand.Expected(previous.posting().ledgerRevision(), previous.posting().reference());
    }

    /** 差额按冻结本币完整分摊计算，不扣除借款抵扣或重新取汇率。 */
    public Money reducedAmount() {
        var amount = Money.zero(source.position().baseCurrency());
        for (int index = 0; index < before.size(); index++) amount = amount.plus(before.get(index).cost().amount().minus(after.get(index).cost().amount()));
        return amount;
    }

    /** 回执核对完整前值摘要；编码包括零位置、原次序、类别、成本对象、币种和整分金额。 */
    public String beforeDigest() { return positionsDigest(before); }

    /** 当前净额的固定摘要供此次回执及下次已完成依据使用，不以合计金额代替分摊身份。 */
    public String afterDigest() { return positionsDigest(after); }

    /** 固定摘要包括原消费、完整前后位置、前次结果及本次人工决定，不依赖 JSON 字段顺序。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-budget-consumption-reduction-1", id, adjustmentId, source.digest(), consumed.operationId(), consumed.commandDigest(),
                    consumed.status(), consumed.ledgerRevision(), consumed.reference(), consumed.appliedAt());
            addPositions(digest, before);
            addPositions(digest, after);
            add(digest, previous != null);
            if (previous != null) {
                var posting = previous.posting();
                add(digest, previous.operationId(), previous.adjustmentId(), previous.commandDigest(), previous.status(), previous.observedAt(),
                        posting.consumptionId(), posting.consumptionDigest(), posting.consumptionReference(), posting.ledgerRevision(), posting.reference(),
                        posting.beforeDigest(), posting.afterDigest(), posting.reducedAmount().currency(), posting.reducedAmount().value().toPlainString(),
                        posting.periodReference(), posting.accountingDate(), posting.appliedAt());
            }
            add(digest, period.request().legalEntityId(), period.request().currency(), period.request().accountingDate(), period.periodReference(), period.sourceVersion(),
                    period.startsOn(), period.endsOn(), period.observedAt(), period.validUntil(), authorizedBy, evidenceReference, reason, createdAt, expiresAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 授权过期只禁止发送，查询始终保留相同编号和摘要。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) throw new DomainException("BUDGET_REDUCTION_AUTHORIZATION_EXPIRED", "Budget reduction authorization and accounting evidence have expired");
    }

    static String positionsDigest(List<BudgetPrecheckPort.Allocation> positions) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-budget-consumption-position-1");
            addPositions(digest, positions);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void addPositions(MessageDigest digest, List<BudgetPrecheckPort.Allocation> positions) {
        add(digest, positions.size());
        for (var position : positions) add(digest, position.expenseLineNo(), position.allocationNo(), position.categoryCode(), position.cost().costCenter(),
                position.cost().projectCode(), position.cost().amount().currency(), position.cost().amount().value().toPlainString());
    }
    private static void add(MessageDigest digest, Object... values) {
        for (var value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_REDUCTION_COMMAND", "Budget reduction requires original consumption, exact remaining positions and independent finance authorization"); }
    @Override public String toString() { return "BudgetConsumptionReductionCommand[id=" + id + ", consumptionId=" + source.id() + "]"; }
}
