package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import io.agentflow.notification.ExpenseReturnNotice;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 报销退回守住原净付款、独立应付贷方、只追加原件与已核销资源边界。
 * @author owlzhangfq@gmail.com
 */
class ExpensePaymentReturnTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z"), PAID = NOW.minusSeconds(100);
    private static final String PAYABLE_ACCOUNT = "2241-EMPLOYEE";
    private final PaymentCommand command = new PaymentCommand(UUID.randomUUID(), "tenant-a", PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT,
            new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 1, 3, 2), money("100"), "company-bank",
            new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "employee-account", "****1234", "a".repeat(64), "v1"), "accrual-voucher",
            new PaymentCommand.Authorization("finance", "cashier", PAID.minusSeconds(10), PAID.plusSeconds(60)));
    private final PaymentObservation original = observation(PaymentObservation.Status.SUCCEEDED, 1, PAID.plusSeconds(1));
    private final ExpensePaymentReturnPort.Request request = new ExpensePaymentReturnPort.Request(command, original, PAYABLE_ACCOUNT);

    @Test
    void partialAndFullReturnsRetainOriginalNetPaymentAndConsumedResources() {
        var settlement = settlement().consumed(UUID.randomUUID(), PAID.plusSeconds(10));
        settlement = settlement.budgetResolved(settlement.budgetOperationId(), true, null, PAID.plusSeconds(11));
        var partial = decision(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20")), "finance");
        var frozen = partial.applyTo(settlement);
        assertThat(frozen.status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(frozen.resourcesConsumed()).isTrue(); assertThat(frozen.budgetOperationId()).isEqualTo(settlement.budgetOperationId());
        assertThat(frozen.input()).isEqualTo(settlement.input()); assertThat(frozen.input().gross()).isEqualTo(money("150"));
        assertThat(frozen.input().offsets()).isEqualTo(money("50")); assertThat(frozen.input().payable()).isEqualTo(money("100"));
        var full = decision(receipt(ExpensePaymentReturnPort.Status.RETURNED, item("one", "20"), item("two", "80")), "finance");
        assertThat(full.receipt().totalReturned()).isEqualTo(money("100")); assertThat(full.applyTo(frozen)).isEqualTo(frozen);
    }

    @Test
    void confirmationCannotClearExistingFinancialReviewOrConsumePendingResources() {
        var queued = settlement();
        var confirmed = decision(receipt(ExpensePaymentReturnPort.Status.CONFIRMED), "finance");
        assertThat(confirmed.applyTo(queued)).isSameAs(queued);
        var review = queued.requireReview("VOUCHER_REVERSED", NOW.minusSeconds(1));
        assertThat(confirmed.applyTo(review)).isSameAs(review);
        var returned = decision(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20")), "finance").applyTo(queued);
        assertThat(returned.resourcesConsumed()).isFalse(); assertThat(returned.budgetOperationId()).isNull();
    }

    @Test
    void cumulativeEvidenceCannotDropOrChangePriorBankOrPostingFacts() {
        var one = item("one", "20"); var two = item("two", "30");
        var old = receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, one);
        var latest = receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, one, two);
        assertThat(latest.preservesReturns(old)).isTrue(); assertThat(old.preservesReturns(latest)).isFalse();
        assertThat(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, two, one).sameReturns(latest)).isTrue();
        assertThat(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "21")).preservesReturns(old)).isFalse();
        var altered = new ExpensePaymentReturnPort.ReturnItem(one.funding(), new ExpensePaymentReturnPort.PayableCredit("another-voucher", "credit", PAYABLE_ACCOUNT,
                one.posting().amount(), one.posting().accountingDate(), one.posting().postedAt()));
        assertThat(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, altered).preservesReturns(old)).isFalse();
    }

    @Test
    void rejectsOverpaymentWrongStatusDuplicatesAndWrongAccount() {
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "100")));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.RETURNED, item("one", "99")));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.RETURNED, item("one", "101")));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.CONFIRMED, item("one", "20")));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.UNRESOLVED, item("one", "20")));
        var one = item("one", "20");
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, one, one));
        var wrong = new ExpensePaymentReturnPort.ReturnItem(one.funding(), new ExpensePaymentReturnPort.PayableCredit("new-voucher", "credit", "1221-LOAN",
                money("20"), LocalDate.of(2026, 9, 29), NOW.minusSeconds(5)));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, wrong));
        var repeatedPosting = new ExpensePaymentReturnPort.ReturnItem(item("two", "20").funding(), one.posting());
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, one, repeatedPosting));
    }

    @Test
    void freshnessAndReceivedFundsCannotBeInventedFromOriginalPayment() {
        var proof = receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20"));
        assertThat(proof.matches(request, NOW.minusNanos(1))).isFalse(); assertThat(proof.matches(request, proof.validUntil())).isFalse();
        assertInvalid(() -> new ExpensePaymentReturnPort.Receipt(request, proof.status(), 2, NOW, NOW.plusSeconds(301), proof.current(), proof.returns()));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, new ExpensePaymentReturnPort.ReturnItem(
                new ExpensePaymentReturnPort.BankReceipt("premature", money("20"), PAID.minusSeconds(1)), item("one", "20").posting())));
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, new ExpensePaymentReturnPort.ReturnItem(
                new ExpensePaymentReturnPort.BankReceipt(original.paymentReference(), money("20"), NOW.minusSeconds(10)), item("one", "20").posting())));
        assertInvalid(() -> new ExpensePaymentReturnPort.Receipt(request, ExpensePaymentReturnPort.Status.RETURNED, 2, NOW, NOW.plusSeconds(60),
                observation(PaymentObservation.Status.REVERSED, 1, NOW), List.of(item("one", "100"))));
        var many = java.util.stream.IntStream.range(0, 101).mapToObj(index -> item("row-" + index, "0.01")).toArray(ExpensePaymentReturnPort.ReturnItem[]::new);
        assertInvalid(() -> receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, many));
    }

    @Test
    void independentActorAndExactSettlementAreRequiredEvenAfterOriginalAuthorizationExpires() {
        var proof = receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20"));
        assertInvalid(() -> decision(proof, "alice")); assertInvalid(() -> decision(proof, "cashier"));
        var decision = decision(proof, "finance"); assertThat(decision.applyTo(settlement()).status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        var another = new ExpensePaymentReturnTest().settlement(); assertInvalid(() -> decision.applyTo(another));
        assertInvalid(() -> decision(receipt(ExpensePaymentReturnPort.Status.UNRESOLVED), "finance"));
        var loan = new PaymentCommand(command.id(), command.tenantId(), PaymentCommand.Purpose.EMPLOYEE_ADVANCE, command.binding(), command.amount(),
                command.debitAccountReference(), command.payee(), command.voucherReference(), command.authorization());
        assertInvalid(() -> new ExpensePaymentReturnPort.Request(loan, original, PAYABLE_ACCOUNT));
    }

    @Test
    void persistedReadLeaseAndSingleRegistrationDoNotApplyMoneyAutomatically() {
        var queued = ExpensePaymentReturnCheck.queue(new ExpensePaymentReturnCheck.Input(UUID.randomUUID(), "tenant-a", "b".repeat(64), 3, request, "finance", NOW));
        var running = queued.claim(NOW, Duration.ofSeconds(90));
        var checked = running.complete(new FinanceResult.Success<>(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20"))), NOW.plusSeconds(1));
        assertThat(checked.status()).isEqualTo(ExpensePaymentReturnCheck.Status.CHECKED); assertThat(checked.resolutionId()).isNull();
        var decision = new ExpensePaymentReturn(UUID.randomUUID(), "tenant-a", checked.input().id(), checked.receipt(), "finance", NOW.plusSeconds(2), "material", "登记银行退回");
        var resolved = checked.resolve(decision, decision.registeredAt()); assertThat(resolved.resolutionId()).isEqualTo(decision.id());
        assertInvalid(() -> resolved.resolve(decision, decision.registeredAt()));
        var otherActor = new ExpensePaymentReturn(UUID.randomUUID(), "tenant-a", checked.input().id(), checked.receipt(), "finance-two", decision.registeredAt(), "material", "登记银行退回");
        assertInvalid(() -> checked.resolve(otherActor, otherActor.registeredAt()));
        assertThat(running.complete(new FinanceResult.Success<>(checked.receipt()), NOW.plusSeconds(90)).issue()).isEqualTo(ExpensePaymentReturnCheck.Issue.TIMEOUT);
    }

    @Test
    void ledgerOnlyAppendsNewFundsAndNeverClearsActualReturnsWithAnotherConfirmation() {
        var ledger = ExpensePaymentReturns.open(request, NOW.minusSeconds(1)).requireReview(NOW);
        assertThat(ledger.reviewRequired()).isTrue(); assertThat(ledger.totalReturned()).isEqualTo(money("0"));
        ledger = ledger.register(decision(receipt(ExpensePaymentReturnPort.Status.CONFIRMED), "finance"));
        assertThat(ledger.reviewRequired()).isFalse();
        var first = decision(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("one", "20")), "finance");
        var recorded = ledger.register(first);
        var repeated = recorded.register(decision(first.receipt(), "finance-two"));
        assertThat(repeated.entries()).isEqualTo(recorded.entries()); assertThat(repeated.totalReturned()).isEqualTo(money("20"));
        var second = decision(receipt(ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, item("two", "30"), item("one", "20")), "finance-two");
        var cumulative = repeated.register(second);
        assertThat(cumulative.totalReturned()).isEqualTo(money("50")); assertThat(cumulative.reviewRequired()).isTrue();
        assertThat(cumulative.entries().get(0).registrationId()).isEqualTo(first.id());
        assertThat(cumulative.entries().get(1).registrationId()).isEqualTo(second.id());
        assertInvalid(() -> cumulative.register(decision(receipt(ExpensePaymentReturnPort.Status.CONFIRMED), "finance")));
        assertInvalid(() -> new ExpensePaymentReturns(request, cumulative.version(), cumulative.entries(), false, cumulative.createdAt(), cumulative.updatedAt()));
    }

    @Test void returnNoticesDistinguishQueriesFromActualRegistrationsAndKeepOriginalFact() {
        for (var outcome : List.of(ExpensePaymentReturnPort.Status.CONFIRMED, ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED, ExpensePaymentReturnPort.Status.RETURNED)) {
            var queued = ExpensePaymentReturnCheck.queue(new ExpensePaymentReturnCheck.Input(UUID.randomUUID(), "tenant-a", "b".repeat(64), 3, request, "finance", NOW));
            var running = queued.claim(NOW, Duration.ofSeconds(90));
            assertThat(ExpenseReturnNotice.from(queued)).isEmpty(); assertThat(ExpenseReturnNotice.from(running)).isEmpty();
            var proof = outcome == ExpensePaymentReturnPort.Status.CONFIRMED ? receipt(outcome)
                    : receipt(outcome, item("notice", outcome == ExpensePaymentReturnPort.Status.RETURNED ? "100" : "20"));
            var checked = running.complete(new FinanceResult.Success<>(proof), NOW.plusSeconds(1));
            assertThat(ExpenseReturnNotice.from(checked)).isEqualTo(outcome == ExpensePaymentReturnPort.Status.CONFIRMED
                    ? java.util.Optional.empty() : java.util.Optional.of(ExpenseReturnNotice.RETURN_REVIEW));
            assertThat(ExpenseReturnNotice.RECORDED.presentIn(checked)).isFalse();
            var decision = new ExpensePaymentReturn(UUID.randomUUID(), "tenant-a", checked.input().id(), proof, "finance", NOW.plusSeconds(2), "notice-proof", "明确登记原件");
            var resolved = checked.resolve(decision, decision.registeredAt()); assertThat(ExpenseReturnNotice.from(resolved)).contains(ExpenseReturnNotice.RECORDED);
            assertThat(ExpenseReturnNotice.RETURN_REVIEW.presentIn(resolved)).isEqualTo(outcome != ExpensePaymentReturnPort.Status.CONFIRMED);
        }
    }
    @Test void unresolvedOrUnavailableReturnEvidenceNeverClaimsRegistration() {
        var queued = ExpensePaymentReturnCheck.queue(new ExpensePaymentReturnCheck.Input(UUID.randomUUID(), "tenant-a", "b".repeat(64), 3, request, "finance", NOW));
        var running = queued.claim(NOW, Duration.ofSeconds(90));
        var unresolved = running.complete(new FinanceResult.Success<>(receipt(ExpensePaymentReturnPort.Status.UNRESOLVED)), NOW.plusSeconds(1));
        assertThat(ExpenseReturnNotice.from(unresolved)).contains(ExpenseReturnNotice.UNRESOLVED);
        assertThat(ExpenseReturnNotice.RECORDED.presentIn(unresolved)).isFalse();
        assertThat(ExpenseReturnNotice.from(running.fail(ExpensePaymentReturnCheck.Issue.TIMEOUT, NOW.plusSeconds(91)))).contains(ExpenseReturnNotice.UNAVAILABLE);
        assertThat(ExpenseReturnNotice.from(running.voidSource(NOW.plusSeconds(1)))).contains(ExpenseReturnNotice.SOURCE_CHANGED);
    }
    @Test void returnNoticeKeysRequireCanonicalQueryIdentityAndKnownFact() {
        var id = UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef");
        for (var fact : ExpenseReturnNotice.values()) assertThat(ExpenseReturnNotice.source(fact.eventKey(id))).contains(new ExpenseReturnNotice.Source(id, fact));
        for (String key : List.of("supplier-return:" + id + ":RECORDED", "expense-return:1-1-1-1-1:RECORDED", "expense-return:" + id + ":CONFIRMED",
                "expense-return:" + id + ":RECORDED:2", "expense-return:" + id.toString().toUpperCase() + ":RECORDED")) assertThat(ExpenseReturnNotice.source(key)).isEmpty();
        assertThat(ExpenseReturnNotice.source(null)).isEmpty();
    }

    private ExpenseSettlement settlement() {
        var binding = command.binding();
        var source = new VoucherPreparation.Source("tenant-a", BusinessReference.Type.EXPENSE, binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), "alice");
        return ExpenseSettlement.queue(new ExpenseSettlement.Input(source, money("150"), money("50"), UUID.randomUUID(), "c".repeat(64),
                new ExpenseSettlement.Payment(command.id(), command.digest(), command.amount(), original.paymentReference(), original.receiptReference(), PAID), PAID), PAID.plusSeconds(2));
    }
    private ExpensePaymentReturn decision(ExpensePaymentReturnPort.Receipt receipt, String actor) {
        return new ExpensePaymentReturn(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), receipt, actor, NOW.plusSeconds(1), "material", "登记银行退回");
    }
    private ExpensePaymentReturnPort.Receipt receipt(ExpensePaymentReturnPort.Status status, ExpensePaymentReturnPort.ReturnItem... items) {
        return new ExpensePaymentReturnPort.Receipt(request, status, 2, NOW, NOW.plusSeconds(120),
                observation(status == ExpensePaymentReturnPort.Status.RETURNED ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, 2, NOW), List.of(items));
    }
    private ExpensePaymentReturnPort.ReturnItem item(String id, String amount) {
        return new ExpensePaymentReturnPort.ReturnItem(new ExpensePaymentReturnPort.BankReceipt("bank-" + id, money(amount), NOW.minusSeconds(10)),
                new ExpensePaymentReturnPort.PayableCredit("voucher-" + id, "credit", PAYABLE_ACCOUNT, money(amount), LocalDate.of(2026, 9, 29), NOW.minusSeconds(5)));
    }
    private PaymentObservation observation(PaymentObservation.Status status, long revision, Instant at) {
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, "original-payment", command.amount(), command.payee().accountDigest(), PAID, "original-receipt", null);
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(DomainException.class); }
}
