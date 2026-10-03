package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseAdjustmentAmounts;
import io.agentflow.expense.ExpenseReport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 挂账差额只反向本次金额，原凭证和上次已完成的剩余位置不得被新的同额分录替换。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAccrualReductionTest {
    static final Instant NOW = Instant.parse("2026-09-30T18:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Test void firstIndependentPostingUsesOriginalAccountsAndOnlyIncrementalOppositeLines() {
        var financial = financial("30", "80", "4");
        var command = command(financial, null, NOW);
        assertThat(command.lines()).isEqualTo(financial.voucherLines());
        assertThat(command.before()).hasSize(6);
        assertThat(command.after()).hasSize(6);
        assertThat(command.lines()).extracting(VoucherReversalCommand.Line::originalLineNo).containsExactly(1, 2, 3, 4, 6);
        assertThat(command.reducedAmount()).isEqualTo(money("20"));
        assertThat(command.expectedAdjustmentRevision()).isZero();
        assertThat(command.source().original().status()).isEqualTo(VoucherObservation.Status.POSTED);
        assertThat(command.source().command().totals().gross()).isEqualTo(money("100"));
        assertThat(command.toString()).doesNotContain("alice", "finance", "material", "100.00");
        assertThatThrownBy(() -> command.after().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void originallyZeroPayableAndCompleteCancellationRetainZeroPositionsWithoutBankLines() {
        var financial = financial("100", "0", "0");
        var command = command(financial, null, NOW);
        assertThat(command.reducedAmount()).isEqualTo(money("100"));
        assertThat(command.after()).allSatisfy(line -> assertThat(line.amount()).isEqualTo(money("0")));
        assertThat(command.lines()).noneSatisfy(line -> assertThat(line.accountCode()).isEqualTo("old-EMPLOYEE_PAYABLE"));
        assertThat(posted(command, NOW.plusSeconds(1), "first").matches(command, false, NOW.plusSeconds(1))).isTrue();
    }

    @Test void laterAdjustmentRequiresExactPriorOriginalAndRemainingPositions() {
        var financial = financial("30", "80", "4");
        var first = command(financial, null, NOW);
        var previous = posted(first, NOW.plusSeconds(1), "first");
        var nextChange = financial.change().after().reduce(List.of(new ExpenseReport.Reduction(1, money("20"), money("1"))));
        var nextFinancial = new ExpenseAdjustmentFinancialSource(nextChange, financial.settlement(), financial.consumption(), financial.accrual());
        var next = command(nextFinancial, previous, NOW.plusSeconds(2));
        assertThat(next.expectedAdjustmentRevision()).isEqualTo(1);
        assertThat(next.reducedAmount()).isEqualTo(money("60"));
        assertThat(next.beforeDigest()).isEqualTo(previous.posting().afterDigest());
        assertThat(posted(next, NOW.plusSeconds(3), "second").matches(next, false, NOW.plusSeconds(3))).isTrue();
        invalid(() -> command(nextFinancial, null, NOW.plusSeconds(2)));
        invalid(() -> command(nextFinancial, posted(command(financial("30", "80", "4"), null, NOW), NOW.plusSeconds(1), "foreign"), NOW.plusSeconds(2)));
        invalid(() -> new ExpenseAccrualReductionCommand(first.id(), UUID.randomUUID(), next.source(), previous, next.before(), next.after(), next.period(),
                "finance", "material", "重用旧号", next.createdAt(), next.expiresAt()));
        invalid(() -> new ExpenseAccrualReductionCommand(next.id(), first.adjustmentId(), next.source(), previous, next.before(), next.after(), next.period(),
                "finance", "material", "重用旧调整", next.createdAt(), next.expiresAt()));
        invalid(() -> command(nextFinancial, outcome(first, ExpenseAccrualReductionObservation.Status.PENDING, NOW.plusSeconds(1)), NOW.plusSeconds(2)));
    }

    @Test void balancedTotalsCannotHideChangedAccountsDimensionsOrIncreasedOriginalPositions() {
        var value = command(financial("30", "80", "4"), null, NOW);
        var lines = new ArrayList<>(value.after());
        var original = lines.get(0);
        lines.set(0, new VoucherReversalCommand.Line(original.originalLineNo(), "another-account", original.side(), original.amount(),
                original.sourceLineNo(), original.costCenter(), original.projectCode(), original.advanceId()));
        invalid(() -> copy(value, lines, value.source(), value.period(), "finance", "material", value.createdAt(), value.expiresAt()));
        lines.set(0, new VoucherReversalCommand.Line(original.originalLineNo(), original.accountCode(), original.side(), original.amount(),
                original.sourceLineNo(), "another-center", original.projectCode(), original.advanceId()));
        invalid(() -> copy(value, lines, value.source(), value.period(), "finance", "material", value.createdAt(), value.expiresAt()));
        invalid(() -> copy(value, value.before(), value.source(), value.period(), "finance", "material", value.createdAt(), value.expiresAt()));
        invalid(() -> copy(value, value.after().subList(0, value.after().size() - 1), value.source(), value.period(), "finance", "material", value.createdAt(), value.expiresAt()));
        lines.set(0, amount(original, "57"));
        lines.set(2, amount(lines.get(2), "19"));
        invalid(() -> copy(value, lines, value.source(), value.period(), "finance", "material", value.createdAt(), value.expiresAt()));
    }

    @Test void onlyFreshPostedExpenseAccrualAndIndependentFinanceCanBeAuthorized() {
        var value = command(financial("30", "80", "4"), null, NOW);
        invalid(() -> copy(value, value.after(), value.source(), value.period(), "alice", "material", NOW, value.expiresAt()));
        invalid(() -> copy(value, value.after(), value.source(), value.period(), "finance\n", "material", NOW, value.expiresAt()));
        invalid(() -> copy(value, value.after(), value.source(), value.period(), "finance", "material", NOW, NOW.plusSeconds(301)));
        var old = value.source();
        var reversed = observation(old.original(), VoucherObservation.Status.REVERSED, old.original().revision() + 1, NOW);
        invalid(() -> copy(value, value.after(), new VoucherReversalPort.Request(old.command(), reversed), value.period(), "finance", "material", NOW, value.expiresAt()));
        var stale = new VoucherReversalPort.Request(old.command(), old.original());
        var later = period(old.command().legalEntityId(), DATE, NOW.plusSeconds(600));
        invalid(() -> copy(value, value.after(), stale, later, "finance", "material", NOW.plusSeconds(601), NOW.plusSeconds(700)));
        invalid(() -> copy(value, value.after(), old, period(UUID.randomUUID(), DATE, NOW), "finance", "material", NOW, value.expiresAt()));
        invalid(() -> copy(value, value.after(), old, period(old.command().legalEntityId(), DATE.minusDays(1), NOW), "finance", "material", NOW, value.expiresAt()));
        assertThatCode(() -> value.requireSendAt(NOW)).doesNotThrowAnyException();
        invalid(() -> value.requireSendAt(value.expiresAt()));
    }

    @Test void returnedPostingMustContainExactIncrementalLinesAndDistinctOriginalIdentity() {
        var command = command(financial("30", "80", "4"), null, NOW);
        var result = posted(command, NOW.plusSeconds(1), "first");
        assertThat(result.matches(command, false, NOW.plusSeconds(1))).isTrue();
        var posting = result.posting();
        var voucher = posting.voucher();
        var wrongDate = new VoucherReversalPort.Posting(voucher.postingReference(), voucher.voucherReference(), voucher.periodReference(), DATE.plusDays(1), voucher.postedAt(), voucher.lines());
        assertThat(replacePosting(result, wrongDate, posting.adjustmentRevision(), command.after()).matches(command, false, result.observedAt())).isFalse();
        assertThat(replacePosting(result, voucher, posting.adjustmentRevision() + 1, command.after()).matches(command, false, result.observedAt())).isFalse();
        var lines = new ArrayList<>(voucher.lines());
        var first = lines.get(0);
        lines.set(0, new VoucherReversalPort.Line(first.entryReference(), first.originalLineNo(), "wrong-account", first.side(), first.amount(), first.sourceLineNo(), first.costCenter(), first.projectCode(), first.advanceId()));
        var wrongAccount = new VoucherReversalPort.Posting(voucher.postingReference(), voucher.voucherReference(), voucher.periodReference(), DATE, voucher.postedAt(), lines);
        assertThat(replacePosting(result, wrongAccount, posting.adjustmentRevision(), command.after()).matches(command, false, result.observedAt())).isFalse();
        var originalVoucher = new VoucherReversalPort.Posting(voucher.postingReference(), command.source().original().voucherReference(), voucher.periodReference(), DATE, voucher.postedAt(), voucher.lines());
        invalid(() -> replacePosting(result, originalVoucher, posting.adjustmentRevision(), command.after()));
        assertThat(result.matches(command, false, NOW)).isFalse();
    }

    @Test void stableDigestBindsAmountsPriorProofAndExplicitDecisionWithoutTextDelimiterCollisions() {
        var value = command(financial("30", "80", "4"), null, NOW);
        assertThat(value.digest()).matches("[a-f0-9]{64}").isEqualTo(copy(value, value.after(), value.source(), value.period(), "finance", "material", NOW, value.expiresAt()).digest());
        assertThat(copy(value, value.after(), value.source(), value.period(), "other-finance", "material", NOW, value.expiresAt()).digest()).isNotEqualTo(value.digest());
        assertThat(copy(value, value.after(), value.source(), value.period(), "finance", "another-proof", NOW, value.expiresAt()).digest()).isNotEqualTo(value.digest());
        var one = new ExpenseAccrualReductionCommand(value.id(), value.adjustmentId(), value.source(), null, value.before(), value.after(), value.period(), "finance", "a|b", "c", NOW, value.expiresAt());
        var two = new ExpenseAccrualReductionCommand(value.id(), value.adjustmentId(), value.source(), null, value.before(), value.after(), value.period(), "finance", "a", "b|c", NOW, value.expiresAt());
        assertThat(one.digest()).isNotEqualTo(two.digest());
    }

    @Test void queryOnlyNotFoundAndFailureCannotCarryPostedFacts() {
        var command = command(financial("30", "80", "4"), null, NOW);
        var missing = outcome(command, ExpenseAccrualReductionObservation.Status.NOT_FOUND, NOW.plusSeconds(500));
        assertThat(missing.matches(command, true, NOW.plusSeconds(501))).isTrue();
        assertThat(missing.matches(command, false, NOW.plusSeconds(501))).isFalse();
        var valid = posted(command, NOW.plusSeconds(1), "first");
        invalid(() -> new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), ExpenseAccrualReductionObservation.Status.PENDING,
                1, NOW.plusSeconds(1), "accepted", valid.posting(), null));
    }

    static ExpenseAdjustmentFinancialSource financial(String offset, String gross, String tax) {
        var fixture = ExpenseAdjustmentFinancialSourceTest.fixture(offset);
        var change = ExpenseAdjustmentAmounts.from(fixture.report()).reduce(List.of(new ExpenseReport.Reduction(1, money(gross), money(tax))));
        return new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), fixture.accrual());
    }
    static ExpenseAccrualReductionCommand command(ExpenseAdjustmentFinancialSource financial, ExpenseAccrualReductionObservation previous, Instant at) {
        if (previous != null) {
            var observed = observation(financial.accrual().observation(), VoucherObservation.Status.POSTED, financial.accrual().observation().revision(), at);
            var current = financial.accrual().requestQuery(at).claim(at, Duration.ofSeconds(30)).complete(new FinanceResult.Success<>(observed), at);
            financial = new ExpenseAdjustmentFinancialSource(financial.change(), financial.settlement(), financial.consumption(), current);
        }
        return ExpenseAccrualReductionCommand.forExpense(UUID.randomUUID(), UUID.randomUUID(), financial, previous,
                period(financial.accrual().input().command().legalEntityId(), DATE, at), "finance", "material", "减少已核定费用", at, at.plusSeconds(120));
    }
    static ExpenseAccrualReductionObservation posted(ExpenseAccrualReductionCommand command, Instant at, String reference) {
        var lines = command.lines().stream().map(line -> new VoucherReversalPort.Line("entry-" + line.originalLineNo(), line.originalLineNo(), line.accountCode(), line.side(),
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var voucher = new VoucherReversalPort.Posting(reference + "-posting", reference + "-voucher", command.period().periodReference(), command.period().request().accountingDate(), at, lines);
        var current = observation(command.source().original(), VoucherObservation.Status.POSTED, command.source().original().revision(), at);
        var posting = new ExpenseAccrualReductionObservation.Posting(current, command.expectedAdjustmentRevision() + 1, command.beforeDigest(), command.afterDigest(), voucher);
        return new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), ExpenseAccrualReductionObservation.Status.POSTED, 2, at, "accepted", posting, null);
    }
    static ExpenseAccrualReductionObservation outcome(ExpenseAccrualReductionCommand command, ExpenseAccrualReductionObservation.Status status, Instant at) {
        return new ExpenseAccrualReductionObservation(command.id(), command.adjustmentId(), command.digest(), status,
                status == ExpenseAccrualReductionObservation.Status.NOT_FOUND ? 0 : 1, at, status == ExpenseAccrualReductionObservation.Status.NOT_FOUND ? null : "accepted", null, null);
    }
    private static ExpenseAccrualReductionObservation replacePosting(ExpenseAccrualReductionObservation result, VoucherReversalPort.Posting voucher, long revision, List<VoucherReversalCommand.Line> after) {
        var posting = new ExpenseAccrualReductionObservation.Posting(result.posting().original(), revision, result.posting().beforeDigest(), ExpenseAccrualReductionCommand.positionsDigest(after), voucher);
        return new ExpenseAccrualReductionObservation(result.operationId(), result.adjustmentId(), result.commandDigest(), result.status(), result.revision(), result.observedAt(), result.acceptanceReference(), posting, result.rejection());
    }
    private static VoucherObservation observation(VoucherObservation original, VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(original.operationId(), original.commandDigest(), status, revision, at, original.postingReference(), original.voucherReference(), original.periodReference(),
                original.accountingDate(), original.debitTotal(), original.creditTotal(), original.postedAt(), null);
    }
    private static ExpenseAccrualReductionCommand copy(ExpenseAccrualReductionCommand value, List<VoucherReversalCommand.Line> after, VoucherReversalPort.Request source,
            AccountingPeriodPort.OpenPeriod period, String actor, String evidence, Instant at, Instant expires) {
        return new ExpenseAccrualReductionCommand(value.id(), value.adjustmentId(), source, value.previous(), value.before(), after, period, actor, evidence, value.reason(), at, expires);
    }
    private static AccountingPeriodPort.OpenPeriod period(UUID entity, LocalDate date, Instant now) {
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "reduction-period", "v1", date, date, now.minusSeconds(1), now.plusSeconds(300));
    }
    private static VoucherReversalCommand.Line amount(VoucherReversalCommand.Line line, String value) {
        return new VoucherReversalCommand.Line(line.originalLineNo(), line.accountCode(), line.side(), money(value), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId());
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
