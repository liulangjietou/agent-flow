package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 原挂账不变，本次只过账独立反向差额；前次依据必须由应用服务从真实完成调整恢复。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAccrualReductionCommand(UUID id, UUID adjustmentId, VoucherReversalPort.Request source,
        ExpenseAccrualReductionObservation previous, List<VoucherReversalCommand.Line> before, List<VoucherReversalCommand.Line> after,
        AccountingPeriodPort.OpenPeriod period, String authorizedBy, String evidenceReference, String reason, Instant createdAt, Instant expiresAt) {
    private static final int MAX_POSITIONS = ExpenseContent.MAX_LINES * ExpenseLine.MAX_ALLOCATIONS * 2 + ExpenseContent.MAX_ADVANCES + 2;

    /** 原科目、原方向、完整余额和新期间固定在授权中，不能用完整冲销命令代替部分差额。 */
    public ExpenseAccrualReductionCommand {
        if (id == null || adjustmentId == null || source == null || id.equals(source.command().id())
                || source.command().kind() != VoucherCommand.Kind.EXPENSE_ACCRUAL || source.original().status() != VoucherObservation.Status.POSTED
                || !validReduction(before, after) || period == null || !text(authorizedBy, 128) || authorizedBy.equals(source.command().employeeId())
                || !text(evidenceReference, 128) || !text(reason, 2000) || createdAt == null || expiresAt == null
                || !expiresAt.isAfter(createdAt) || createdAt.isBefore(source.original().observedAt())
                || expiresAt.isAfter(source.original().observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE))
                || expiresAt.isAfter(period.observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE))
                || expiresAt.isAfter(period.validUntil()) || expiresAt.isAfter(createdAt.plus(VoucherReversalPort.MAX_EVIDENCE_AGE))) throw invalid();
        var original = originalPositions(source.command());
        if (original.size() != before.size()) throw invalid();
        for (int index = 0; index < original.size(); index++) {
            if (!samePosition(original.get(index), before.get(index)) || before.get(index).amount().compareTo(original.get(index).amount()) > 0) throw invalid();
        }
        var request = period.request();
        if (!request.legalEntityId().equals(source.command().legalEntityId()) || !request.currency().equals(source.command().totals().gross().currency())
                || request.accountingDate().isBefore(source.command().accountingDate()) || !period.matches(request, createdAt)) throw invalid();
        if (previous == null) {
            if (!before.equals(original)) throw invalid();
        } else {
            if (previous.status() != ExpenseAccrualReductionObservation.Status.POSTED || previous.operationId().equals(id)
                    || previous.adjustmentId().equals(adjustmentId) || previous.observedAt().isAfter(createdAt)
                    || previous.posting().adjustmentRevision() == Long.MAX_VALUE || !previous.posting().afterDigest().equals(positionsDigest(before))
                    || !new VoucherReversalPort.Request(source.command(), previous.posting().original()).matchesOriginal(source.original())
                    || request.accountingDate().isBefore(previous.posting().voucher().accountingDate())) throw invalid();
        }
        before = List.copyOf(before);
        after = List.copyOf(after);
    }

    /** 只从已核验的冻结财务来源构建前后位置；回款和完整完成的跨聚合核对仍由调整应用编排承担。 */
    public static ExpenseAccrualReductionCommand forExpense(UUID id, UUID adjustmentId, ExpenseAdjustmentFinancialSource financial,
            ExpenseAccrualReductionObservation previous, AccountingPeriodPort.OpenPeriod period, String actor, String evidence, String reason,
            Instant at, Instant expires) {
        if (financial == null) throw invalid();
        return new ExpenseAccrualReductionCommand(id, adjustmentId,
                new VoucherReversalPort.Request(financial.accrual().input().command(), financial.accrual().observation()), previous,
                financial.voucherBefore(), financial.voucherAfter(), period, actor, evidence, reason, at, expires);
    }

    /** 独立累计版本从零开始，原凭证自身修订不承担部分调整累计语义。 */
    public long expectedAdjustmentRevision() { return previous == null ? 0 : previous.posting().adjustmentRevision(); }

    /** 回执逐项绑定完整前值，避免重复回传大单的整份辅助核算。 */
    public String beforeDigest() { return positionsDigest(before); }

    /** 零位置仍进入摘要；后续调整必须从此前已完成的同一净额继续。 */
    public String afterDigest() { return positionsDigest(after); }

    /** 只发送正差额的反向分录，保留原行号、科目和辅助核算；零差额不产生分录。 */
    public List<VoucherReversalCommand.Line> lines() {
        var lines = new ArrayList<VoucherReversalCommand.Line>();
        for (int index = 0; index < before.size(); index++) {
            var original = before.get(index);
            var delta = original.amount().minus(after.get(index).amount());
            if (delta.value().signum() > 0) lines.add(new VoucherReversalCommand.Line(original.originalLineNo(), original.accountCode(),
                    original.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT,
                    delta, original.sourceLineNo(), original.costCenter(), original.projectCode(), original.advanceId()));
        }
        return List.copyOf(lines);
    }

    /** 原借方的减少等于本次含税核减；借款及银行回款分别对应原贷方减少。 */
    public Money reducedAmount() {
        return lines().stream().filter(line -> line.side() == VoucherCommand.Side.CREDIT).map(VoucherReversalCommand.Line::amount)
                .reduce(Money.zero(source.command().totals().gross().currency()), Money::plus);
    }

    /** 核对新凭证与所有分录，原挂账必须仍为同一有效过账；ERP 不得静默换期间或重用旧调整。 */
    public boolean matchesPosting(ExpenseAccrualReductionObservation.Posting posting) {
        if (posting == null || !source.matchesOriginal(posting.original()) || posting.adjustmentRevision() != expectedAdjustmentRevision() + 1
                || !posting.beforeDigest().equals(beforeDigest()) || !posting.afterDigest().equals(afterDigest())) return false;
        var voucher = posting.voucher();
        if (!voucher.periodReference().equals(period.periodReference()) || !voucher.accountingDate().equals(period.request().accountingDate())
                || voucher.postedAt().isBefore(createdAt) || previous != null && (voucher.postingReference().equals(previous.posting().voucher().postingReference())
                    || voucher.voucherReference().equals(previous.posting().voucher().voucherReference()))) return false;
        var expected = lines();
        if (voucher.lines().size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            var line = voucher.lines().get(index);
            var actual = new VoucherReversalCommand.Line(line.originalLineNo(), line.accountCode(), line.side(), line.amount(),
                    line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId());
            if (!actual.equals(expected.get(index))) return false;
        }
        return true;
    }

    /** 固定摘要保留前次独立原件、前后完整余额、当前原挂账与独立决定，不递归复制全部历史。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-expense-accrual-reduction-1", id, adjustmentId, source.command().digest());
            addOriginal(digest, source.original());
            addPositions(digest, before);
            addPositions(digest, after);
            add(digest, previous != null);
            if (previous != null) {
                add(digest, previous.operationId(), previous.adjustmentId(), previous.commandDigest(), previous.status(), previous.revision(), previous.observedAt(), previous.acceptanceReference());
                var posting = previous.posting();
                addOriginal(digest, posting.original());
                add(digest, posting.adjustmentRevision(), posting.beforeDigest(), posting.afterDigest());
                var voucher = posting.voucher();
                add(digest, voucher.postingReference(), voucher.voucherReference(), voucher.periodReference(), voucher.accountingDate(), voucher.postedAt(), voucher.lines().size());
                for (var line : voucher.lines()) add(digest, line.entryReference(), line.originalLineNo(), line.accountCode(), line.side(), line.amount().currency(),
                        line.amount().value().toPlainString(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId());
            }
            add(digest, period.request().legalEntityId(), period.request().currency(), period.request().accountingDate(), period.periodReference(), period.sourceVersion(),
                    period.startsOn(), period.endsOn(), period.observedAt(), period.validUntil(), authorizedBy, evidenceReference, reason, createdAt, expiresAt);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /** 新过账要求授权有效；过期后仍可读取同一指令的实际结果。 */
    public void requireSendAt(Instant now) {
        if (now == null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) throw new DomainException("EXPENSE_ACCRUAL_REDUCTION_EXPIRED", "Expense accrual reduction authorization or original evidence has expired");
    }

    static String positionsDigest(List<VoucherReversalCommand.Line> positions) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-expense-accrual-position-1");
            addPositions(digest, positions);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    static boolean validReduction(List<VoucherReversalCommand.Line> before, List<VoucherReversalCommand.Line> after) {
        if (!validPositions(before) || !validPositions(after) || before.size() != after.size()) return false;
        boolean reduced = false;
        for (int index = 0; index < before.size(); index++) {
            if (!samePosition(before.get(index), after.get(index)) || after.get(index).amount().compareTo(before.get(index).amount()) > 0) return false;
            reduced |= after.get(index).amount().compareTo(before.get(index).amount()) < 0;
        }
        return reduced;
    }

    private static boolean validPositions(List<VoucherReversalCommand.Line> positions) {
        if (CollectionUtils.isEmpty(positions) || positions.size() > MAX_POSITIONS || positions.stream().anyMatch(Objects::isNull)
                || positions.get(0).amount() == null) return false;
        var currency = positions.get(0).amount().currency();
        var debit = Money.zero(currency);
        var credit = Money.zero(currency);
        for (int index = 0; index < positions.size(); index++) {
            var line = positions.get(index);
            if (line.originalLineNo() != index + 1 || !text(line.accountCode(), 128) || line.side() == null || line.amount() == null
                    || !currency.equals(line.amount().currency()) || line.amount().value().signum() < 0
                    || line.sourceLineNo() < 0 || line.sourceLineNo() > ExpenseContent.MAX_LINES
                    || line.costCenter() != null && !text(line.costCenter(), 128) || line.projectCode() != null && !text(line.projectCode(), 128)) return false;
            if (line.side() == VoucherCommand.Side.DEBIT) debit = debit.plus(line.amount());
            else credit = credit.plus(line.amount());
        }
        return debit.equals(credit);
    }
    private static boolean samePosition(VoucherReversalCommand.Line left, VoucherReversalCommand.Line right) {
        return left.originalLineNo() == right.originalLineNo() && left.accountCode().equals(right.accountCode()) && left.side() == right.side()
                && left.sourceLineNo() == right.sourceLineNo() && Objects.equals(left.costCenter(), right.costCenter())
                && Objects.equals(left.projectCode(), right.projectCode()) && Objects.equals(left.advanceId(), right.advanceId())
                && left.amount().currency().equals(right.amount().currency());
    }
    private static List<VoucherReversalCommand.Line> originalPositions(VoucherCommand source) {
        return source.lines().stream().map(line -> new VoucherReversalCommand.Line(line.lineNo(), source.mapping().account(line.account()), line.side(),
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
    }
    private static void addOriginal(MessageDigest digest, VoucherObservation original) {
        add(digest, original.operationId(), original.commandDigest(), original.status(), original.revision(), original.observedAt(), original.postingReference(), original.voucherReference(),
                original.periodReference(), original.accountingDate(), original.debitTotal().currency(), original.debitTotal().value().toPlainString(), original.creditTotal().value().toPlainString(), original.postedAt());
    }
    private static void addPositions(MessageDigest digest, List<VoucherReversalCommand.Line> positions) {
        add(digest, positions.size());
        for (var line : positions) add(digest, line.originalLineNo(), line.accountCode(), line.side(), line.amount().currency(), line.amount().value().toPlainString(),
                line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId());
    }
    private static void add(MessageDigest digest, Object... values) {
        for (var value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }
    static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.length() <= max && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ACCRUAL_REDUCTION_COMMAND", "Expense accrual reduction requires fresh original posting, exact remaining positions and independent finance authorization"); }
    @Override public String toString() { return "ExpenseAccrualReductionCommand[id=" + id + ", originalId=" + source.command().id() + "]"; }
}
