package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 本次调整只能使用原付款的完整已登记回款，历史消费及付款会计证据同时核对。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAdjustmentFundingSourceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T18:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String TARGET = "a".repeat(64);

    @Test void firstAdjustmentUsesExactRegisteredEntryAndPreservesOtherPendingReturns() {
        var fixture = fixture(List.of("20", "30")); var source = source(fixture, change(fixture, "80", "4"), List.of(), List.of(fixture.returns().entries().get(0)));
        assertThat(source.selectedReturns()).hasSize(1); assertThat(source.unusedReturns()).containsExactly(fixture.returns().entries().get(1));
        assertThat(source.returns().totalReturned()).isEqualTo(money("50")); assertThat(source.financial().change().bankReturn()).isEqualTo(money("20"));
        assertThatCode(() -> source.requireAuthorization("independent-finance", NOW.plusSeconds(2))).doesNotThrowAnyException();
        invalid(() -> source.requireAuthorization("alice", NOW.plusSeconds(2))); invalid(() -> source.requireAuthorization("cashier", NOW.plusSeconds(2)));
        invalid(() -> source.requireAuthorization("finance", NOW));
        assertThat(source.toString()).doesNotContain("alice", "original-account", "bank-0", "50.00");
        assertThatThrownBy(() -> source.selectedReturns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void laterAdjustmentAccountsForPreviousReturnsAndNormalizesOnlyNewEntriesToLedgerOrder() {
        var fixture = fixture(List.of("20", "30", "20")); var first = change(fixture, "80", "4");
        var next = first.after().reduce(List.of(target("20", "1"))); var entries = fixture.returns().entries();
        var source = source(fixture, next, List.of(entries.get(0)), List.of(entries.get(2), entries.get(1)));
        assertThat(source.selectedReturns()).containsExactly(entries.get(1), entries.get(2)); assertThat(source.unusedReturns()).isEmpty();
        assertThat(source.financial().change().bankReturn()).isEqualTo(money("50"));
        assertThat(source.financial().change().advanceReversals()).extracting(AdvanceOffset::amount).containsExactly(money("10"));
    }

    @Test void reusedOmittedOrPartiallyInventedReceiptCannotSupplyTheNextAdjustment() {
        var fixture = fixture(List.of("20", "30")); var entries = fixture.returns().entries(); var first = change(fixture, "80", "4");
        var next = first.after().reduce(List.of(target("60", "3")));
        invalid(() -> source(fixture, next, List.of(entries.get(0)), List.of(entries.get(0))));
        invalid(() -> source(fixture, next, List.of(), List.of(entries.get(0))));
        invalid(() -> source(fixture, first, List.of(), List.of(entries.get(0), entries.get(0))));
        invalid(() -> source(fixture, first, List.of(), List.of(entries.get(1))));
        var original = entries.get(1); var proof = original.proof();
        var split = new ExpensePaymentReturns.Entry(original.registrationId(), new ExpensePaymentReturnPort.ReturnItem(
                new ExpensePaymentReturnPort.BankReceipt(proof.fundsIdentity(), money("20"), proof.funding().receivedAt()),
                new ExpensePaymentReturnPort.PayableCredit(proof.posting().voucherReference(), proof.posting().entryReference(), proof.posting().accountCode(), money("20"), DATE, proof.posting().postedAt())));
        invalid(() -> source(fixture, next, List.of(entries.get(0)), List.of(split)));
        var changedOwner = new ExpensePaymentReturns.Entry(UUID.randomUUID(), entries.get(0).proof());
        invalid(() -> source(fixture, first, List.of(), List.of(changedOwner)));
    }

    @Test void bankQueryUncertaintyAndAnUnregisteredNewCumulativeReceiptBlockPreparation() {
        var fixture = fixture(List.of("20", "30")); var change = change(fixture, "80", "4"); var selected = List.of(fixture.returns().entries().get(0));
        var querying = fixture.payment().requestQuery(NOW.plusSeconds(2));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(fixture, change), fixture.returns(), fixture.registration(), querying, fixture.voucher(), null, List.of(), selected));
        var receipt = fixture.registration().receipt();
        var different = new ExpensePaymentReturnPort.Receipt(receipt.request(), receipt.status(), receipt.revision() + 1, NOW, NOW.plusSeconds(60), receipt.current(), List.of(receipt.returns().get(0)));
        var decision = new ExpensePaymentReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), different, "finance", NOW.plusSeconds(1), "other-proof", "新的累计核验尚未登记");
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(fixture, change), fixture.returns(), decision, fixture.payment(), fixture.voucher(), null, List.of(), selected));
    }

    @Test void matchingAmountFromAnotherPaymentAndMissingPaymentVoucherRemainInvalid() {
        var fixture = fixture(List.of("20")); var foreign = fixture(List.of("20")); var financial = financial(fixture, change(fixture, "80", "4"));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial, foreign.returns(), foreign.registration(), foreign.payment(), foreign.voucher(), null, List.of(), foreign.returns().entries()));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial, fixture.returns(), fixture.registration(), fixture.payment(), null, null, List.of(), fixture.returns().entries()));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial, fixture.returns(), fixture.registration(), fixture.payment(), fixture.voucher().requestQuery(NOW.plusSeconds(2)), null, List.of(), fixture.returns().entries()));
        var original = fixture.voucher().input().command(); var mapping = original.mapping();
        var changedMapping = new AccountMappingPort.Mapping(mapping.request(), mapping.sourceVersion(), mapping.observedAt(), mapping.validUntil(),
                mapping.entries().stream().map(value -> value.key().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE ? new AccountMappingPort.Entry(value.key(), "replacement-payable") : value).toList());
        var wrong = new VoucherCommand(original.id(), original.tenantId(), original.kind(), original.binding(), original.legalEntityId(), original.employeeId(), original.accountingDate(), original.totals(), original.period(), changedMapping, original.lines(), original.payment(), original.createdAt(), original.expiresAt());
        invalid(() -> new ExpenseAdjustmentFundingSource(financial, fixture.returns(), fixture.registration(), fixture.payment(), posted(wrong), null, List.of(), fixture.returns().entries()));
    }

    @Test void zeroOriginalPayableUsesNoBankOrPaymentVoucherEvidence() {
        var fixture = ExpenseAdjustmentFinancialSourceTest.fixture("100"); var change = ExpenseAdjustmentAmounts.from(fixture.report()).reduce(List.of(target("80", "4")));
        var financial = new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), fixture.accrual());
        var source = new ExpenseAdjustmentFundingSource(financial, null, null, null, null, null, List.of(), List.of());
        assertThat(source.unusedReturns()).isEmpty(); assertThat(source.financial().change().bankReturn()).isEqualTo(money("0"));
        assertThatCode(() -> source.requireAuthorization("finance", NOW)).doesNotThrowAnyException(); invalid(() -> source.requireAuthorization("alice", NOW));
        var other = fixture(List.of("20"));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial, other.returns(), other.registration(), other.payment(), other.voucher(), null, List.of(), List.of()));
    }

    @Test void laterAdvanceOnlyAdjustmentRetainsAllPreviouslyUsedBankReceipts() {
        var fixture = fixture(List.of("20", "30", "20")); var first = change(fixture, "30", "2");
        var next = first.after().reduce(List.of(target("10", "1")));
        var source = source(fixture, next, fixture.returns().entries(), List.of());
        assertThat(source.financial().change().bankReturn()).isEqualTo(money("0")); assertThat(source.unusedReturns()).isEmpty();
        assertThat(source.financial().change().advanceReversals()).extracting(AdvanceOffset::amount).containsExactly(money("20"));
        invalid(() -> source(fixture, next, List.of(), List.of()));
    }

    @Test void reversedPaymentVoucherRequiresTheSameRegisteredPayableCredit() {
        var fixture = fixture(List.of("70"));
        var record = reversal(fixture.voucher());
        var reversed = fixture.voucher().requestQuery(NOW).claim(NOW, Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(record.receipt().current()), NOW);
        var change = change(fixture, "20", "1");
        var original = fixture;
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(original, change), original.returns(), original.registration(),
                original.payment(), reversed, record, List.of(), original.returns().entries()));

        var posting = record.receipt().reversal();
        var credit = posting.lines().stream().filter(value -> value.side() == VoucherCommand.Side.CREDIT).findFirst().orElseThrow();
        var proof = new ExpensePaymentReturnPort.ReturnItem(fixture.returns().entries().get(0).proof().funding(),
                new ExpensePaymentReturnPort.PayableCredit(posting.voucherReference(), credit.entryReference(), credit.accountCode(),
                        credit.amount(), posting.accountingDate(), posting.postedAt()));
        var matching = withProof(fixture, proof);
        var source = new ExpenseAdjustmentFundingSource(financial(matching, change), matching.returns(), matching.registration(),
                matching.payment(), reversed, record, List.of(), matching.returns().entries());
        assertThat(source.financial().change().bankReturn()).isEqualTo(money("70"));
        assertThat(source.financial().change().advanceReversals()).extracting(AdvanceOffset::amount).containsExactly(money("10"));
        assertThatCode(() -> source.requireAuthorization("independent-finance", NOW.plusSeconds(2))).doesNotThrowAnyException();
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(matching, change), matching.returns(), matching.registration(),
                matching.payment(), reversed, null, List.of(), matching.returns().entries()));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(matching, change), matching.returns(), matching.registration(),
                matching.payment(), reversed, reversal(fixture(List.of("70")).voucher()), List.of(), matching.returns().entries()));
        invalid(() -> new ExpenseAdjustmentFundingSource(financial(matching, change), matching.returns(), matching.registration(),
                matching.payment(), matching.voucher(), record, List.of(), matching.returns().entries()));
    }

    @Test void stillPostedPaymentVoucherCannotAlsoBeClaimedAsAReturnVoucher() {
        var fixture = fixture(List.of("20"));
        var entry = fixture.returns().entries().get(0).proof();
        var proof = new ExpensePaymentReturnPort.ReturnItem(entry.funding(), new ExpensePaymentReturnPort.PayableCredit(
                fixture.voucher().observation().voucherReference(), "invented-credit", entry.posting().accountCode(),
                entry.posting().amount(), DATE, entry.posting().postedAt()));
        var overlapping = withProof(fixture, proof);
        invalid(() -> source(overlapping, change(overlapping, "80", "4"), List.of(), overlapping.returns().entries()));
    }

    private static Fixture withProof(Fixture fixture, ExpensePaymentReturnPort.ReturnItem proof) {
        var old = fixture.registration().receipt();
        var receipt = new ExpensePaymentReturnPort.Receipt(old.request(), old.status(), old.revision(), old.observedAt(),
                old.validUntil(), old.current(), List.of(proof));
        var registration = new ExpensePaymentReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance",
                NOW.plusSeconds(1), "same-posting-proof", "核对原付款的同一笔反向分录");
        var returns = ExpensePaymentReturns.open(receipt.request(), NOW.minusSeconds(5)).register(registration);
        return new Fixture(fixture.original(), fixture.settlement(), returns, registration, fixture.payment(), fixture.voucher());
    }

    private static VoucherReversalRecord reversal(VoucherOperation voucher) {
        var command = voucher.input().command();
        var original = voucher.observation();
        var current = new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.REVERSED, 2L, NOW,
                original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(),
                original.debitTotal(), original.creditTotal(), original.postedAt(), null);
        var lines = command.lines().stream().map(line -> new VoucherReversalPort.Line("reverse-" + line.lineNo(), line.lineNo(),
                command.mapping().account(line.account()), line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT,
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var posting = new VoucherReversalPort.Posting("reverse-payment-posting", "reverse-payment-voucher", "2026-09", DATE, NOW.minusSeconds(1), lines);
        var receipt = new VoucherReversalPort.Receipt(new VoucherReversalPort.Request(command, original), VoucherReversalPort.Status.VERIFIED,
                1, NOW, NOW.plusSeconds(60), current, posting);
        return new VoucherReversalRecord(UUID.randomUUID(), "demo", UUID.randomUUID(), 4, receipt, "finance",
                NOW.plusSeconds(1), "reverse-proof", "核对实际付款反向凭证");
    }

    static Fixture fixture(List<String> returned) {
        var original = ExpenseAdjustmentFinancialSourceTest.fixture("30"); var accrual = original.accrual().input().command(); var binding = accrual.binding(); var amount = original.settlement().input().payable();
        var command = new PaymentCommand(original.settlement().input().payment().operationId(), "demo", PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT,
                new PaymentCommand.Binding(binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion()), amount, "original-debit", original.report().currentRound().account(), "voucher",
                new PaymentCommand.Authorization("finance", "cashier", NOW.minusSeconds(30), NOW.plusSeconds(300)));
        var paid = new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 1L, NOW.minusSeconds(14), "bank", amount, command.payee().accountDigest(), NOW.minusSeconds(15), "receipt", null);
        var request = new ExpensePaymentReturnPort.Request(command, paid, "old-EMPLOYEE_PAYABLE");
        var items = new java.util.ArrayList<ExpensePaymentReturnPort.ReturnItem>();
        for (int index = 0; index < returned.size(); index++) items.add(new ExpensePaymentReturnPort.ReturnItem(new ExpensePaymentReturnPort.BankReceipt("bank-" + index, money(returned.get(index)), NOW.minusSeconds(2)),
                new ExpensePaymentReturnPort.PayableCredit("return-voucher-" + index, "credit", request.payableAccountCode(), money(returned.get(index)), DATE, NOW.minusSeconds(1))));
        var total = items.stream().map(value -> value.funding().amount()).reduce(money("0"), Money::plus); boolean full = total.equals(amount);
        var observed = new PaymentObservation(command.id(), command.digest(), full ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, 2L, NOW, "bank", amount, command.payee().accountDigest(), full ? NOW : paid.completedAt(), full ? "reversed-receipt" : "receipt", null);
        var receipt = new ExpensePaymentReturnPort.Receipt(request, full ? ExpensePaymentReturnPort.Status.RETURNED : ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, 2, NOW, NOW.plusSeconds(60), observed, items);
        var registration = new ExpensePaymentReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), receipt, "finance", NOW.plusSeconds(1), "return-proof", "已核对完整真实回款");
        var returns = ExpensePaymentReturns.open(request, NOW.minusSeconds(5)).register(registration);
        var debit = new PaymentAccountsPort.DebitAccount("original-debit", "原公司账户", "****3456", "CNY", "v1");
        var payment = new PaymentOperation(new PaymentOperation.Input(command, TARGET, debit), 4, full ? PaymentOperation.Status.REVERSED : PaymentOperation.Status.SUCCEEDED, 1, 1, NOW.minusSeconds(20), NOW, null, null, null, observed, null, 2, null);
        var old = original.settlement(); var input = old.input();
        var settlement = new ExpenseSettlement(new ExpenseSettlement.Input(input.source(), input.gross(), input.offsets(), input.voucherOperationId(), input.voucherDigest(),
                new ExpenseSettlement.Payment(command.id(), command.digest(), amount, "bank", "receipt", paid.completedAt()), paid.completedAt()), old.version(), old.status(), old.resourcesConsumed(), old.budgetOperationId(), null, old.createdAt(), old.updatedAt()).requireReview("EXPENSE_PAYMENT_RETURNED", NOW.plusSeconds(1));
        var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""); var bank = new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "original-debit");
        var mappingRequest = new AccountMappingPort.Request(accrual.legalEntityId(), "CNY", List.of(payable, bank));
        var mapping = new AccountMappingPort.Mapping(mappingRequest, "original-pay-map", NOW.minusSeconds(14), NOW.plusSeconds(300), List.of(new AccountMappingPort.Entry(payable, request.payableAccountCode()), new AccountMappingPort.Entry(bank, "original-bank")));
        var voucher = new VoucherCommand(UUID.randomUUID(), "demo", VoucherCommand.Kind.PAYMENT, binding, accrual.legalEntityId(), "alice", DATE,
                new VoucherCommand.Totals(amount, money("0"), money("0")), accrual.period(), mapping,
                List.of(new VoucherCommand.Line(1, payable, VoucherCommand.Side.DEBIT, amount, 0, null, null, null), new VoucherCommand.Line(2, bank, VoucherCommand.Side.CREDIT, amount, 0, null, null, null)),
                new VoucherCommand.PaymentProof(command, paid), NOW.minusSeconds(13), NOW.plusSeconds(120));
        return new Fixture(original, settlement, returns, registration, payment, posted(voucher));
    }
    private static VoucherOperation posted(VoucherCommand command) {
        var at = NOW.minusSeconds(12);
        return VoucherOperation.queue(new VoucherOperation.Input(command, TARGET), command.createdAt()).claim(command.createdAt(), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, at, "payment-posting", "payment-voucher", "2026-09", DATE, command.totals().gross(), command.totals().gross(), at, null)), at);
    }
    private static ExpenseAdjustmentFinancialSource financial(Fixture fixture, ExpenseAdjustmentAmounts.Change change) { return new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.original().budget(), fixture.original().accrual()); }
    static ExpenseAdjustmentFundingSource source(Fixture fixture, ExpenseAdjustmentAmounts.Change change, List<ExpensePaymentReturns.Entry> previous, List<ExpensePaymentReturns.Entry> selected) {
        return new ExpenseAdjustmentFundingSource(financial(fixture, change), fixture.returns(), fixture.registration(), fixture.payment(), fixture.voucher(), null, previous, selected);
    }
    static ExpenseAdjustmentAmounts.Change change(Fixture fixture, String gross, String tax) { return ExpenseAdjustmentAmounts.from(fixture.original().report()).reduce(List.of(target(gross, tax))); }
    private static ExpenseReport.Reduction target(String gross, String tax) { return new ExpenseReport.Reduction(1, money(gross), money(tax)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
    /**
     * 真实形状的原付款、付款凭证与累计回款，独立选择部分用于调整。
     * @author owlzhangfq@gmail.com
     */
    record Fixture(ExpenseAdjustmentFinancialSourceTest.Fixture original, ExpenseSettlement settlement, ExpensePaymentReturns returns,
                           ExpensePaymentReturn registration, PaymentOperation payment, VoucherOperation voucher) { }
}
