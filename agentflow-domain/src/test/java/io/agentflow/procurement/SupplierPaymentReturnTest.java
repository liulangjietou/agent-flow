package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPaymentReturnPort.Status.*;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际银行回款独立于原付款和 ERP 核销，覆盖累计、账户、时效与独立财务边界。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentReturnTest {
    private static final Instant PAID = AUTHORIZED_AT.plusSeconds(20);
    private static final Instant NOW = PAID.plusSeconds(172800);
    private final SupplierPaymentCommand command = payment();
    private final PaymentObservation original = observation(PaymentObservation.Status.SUCCEEDED, 1, PAID);
    private final SupplierPaymentReturnPort.Request request = new SupplierPaymentReturnPort.Request(command, original);

    @Test void partialAndFullFundsRemainIndependentAfterOriginalAuthorizationExpires() {
        assertThatThrownBy(() -> command.requireSendAt(NOW)).isInstanceOf(DomainException.class);
        var partial = receipt(PARTIALLY_RETURNED, 2, item("one", "20"));
        assertThat(partial.matches(request, NOW)).isTrue(); assertThat(partial.totalReturned()).isEqualTo(money("20"));
        var full = receipt(RETURNED, 3, item("one", "20"), item("two", "50"));
        assertThat(full.totalReturned()).isEqualTo(money("70")); assertThat(full.continues(partial)).isTrue();
        assertThat(request.original().status()).isEqualTo(PaymentObservation.Status.SUCCEEDED);
        assertThat(request.command().amount()).isEqualTo(money("70"));
    }

    @Test void fullReturnRequiresAllActualFundsAndOriginalBankReversal() {
        assertInvalid(() -> receipt(RETURNED, 2));
        assertInvalid(() -> receipt(RETURNED, 2, item("one", "69.99")));
        assertInvalid(() -> receipt(RETURNED, 2, item("one", "70.01")));
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, item("one", "70")));
        assertInvalid(() -> receipt(CONFIRMED, 2, item("one", "20")));
        assertInvalid(() -> receipt(UNRESOLVED, 2, item("one", "20")));
        var returned = receipt(RETURNED, 2, item("one", "70"));
        assertInvalid(() -> new SupplierPaymentReturnPort.Receipt(request, RETURNED, 2, NOW, NOW.plusSeconds(120), original, returned.returns()));
        assertInvalid(() -> new SupplierPaymentReturnPort.Request(command, returned.current()));
    }

    @Test void originalCompanyAccountCurrencyAndReceivedAtAreFixed() {
        var source = item("one", "20");
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, new SupplierPaymentReturnPort.BankReceipt(source.transactionReference(), "other-account", source.amount(), source.receivedAt())));
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, new SupplierPaymentReturnPort.BankReceipt(source.transactionReference(), source.creditAccountReference(),
                new io.agentflow.finance.Money(source.amount().value(), "USD"), source.receivedAt())));
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, new SupplierPaymentReturnPort.BankReceipt(source.transactionReference(), source.creditAccountReference(), source.amount(), PAID.minusNanos(1))));
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, new SupplierPaymentReturnPort.BankReceipt(source.transactionReference(), source.creditAccountReference(), source.amount(), NOW.plusNanos(1))));
        for (var reference : List.of(original.paymentReference(), original.receiptReference())) {
            assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, new SupplierPaymentReturnPort.BankReceipt(reference, source.creditAccountReference(), source.amount(), source.receivedAt())));
        }
    }

    @Test void duplicateAndExcessiveBankEntriesFailBeforeRegistration() {
        var one = item("one", "20");
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, one, one));
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, one, item("one", "21")));
        var many = java.util.stream.IntStream.range(0, 101).mapToObj(i -> item("row-" + i, "0.01")).toArray(SupplierPaymentReturnPort.BankReceipt[]::new);
        assertInvalid(() -> receipt(PARTIALLY_RETURNED, 2, many));
    }

    @Test void freshWindowIsExclusiveAndCannotOutliveTheCurrentBankEvidence() {
        var proof = receipt(PARTIALLY_RETURNED, 2, item("one", "20"));
        assertThat(proof.matches(request, NOW.minusNanos(1))).isFalse(); assertThat(proof.matches(request, proof.validUntil())).isFalse();
        assertInvalid(() -> new SupplierPaymentReturnPort.Receipt(request, proof.status(), 2, NOW, NOW.plusSeconds(301), proof.current(), proof.returns()));
        var stale = observation(PaymentObservation.Status.SUCCEEDED, 2, NOW.minusSeconds(300));
        assertInvalid(() -> new SupplierPaymentReturnPort.Receipt(request, CONFIRMED, 2, NOW, NOW.plusSeconds(1), stale, List.of()));
        assertInvalid(() -> new SupplierPaymentReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), proof, "finance", proof.validUntil(), "statement", "核对实际回款"));
    }

    @Test void cumulativeEvidencePreservesUnregisteredFundsAndRejectsVersionRegression() {
        var one = item("one", "20"); var two = item("two", "10");
        var first = receipt(PARTIALLY_RETURNED, 2, one);
        var next = receipt(PARTIALLY_RETURNED, 3, two, one);
        assertThat(next.continues(first)).isTrue(); assertThat(first.continues(next)).isFalse();
        assertThat(receipt(PARTIALLY_RETURNED, 3, one, two).sameReturns(next)).isTrue();
        assertThat(receipt(PARTIALLY_RETURNED, 2, one, two).continues(first)).isFalse();
        assertThat(receipt(PARTIALLY_RETURNED, 3, item("one", "21")).continues(first)).isFalse();
        assertThat(receipt(CONFIRMED, 4).continues(first)).isFalse();
        var lowerBank = new SupplierPaymentReturnPort.Receipt(request, next.status(), 4, NOW, NOW.plusSeconds(120), observation(PaymentObservation.Status.SUCCEEDED, 1, NOW), next.returns());
        assertThat(lowerBank.continues(first)).isFalse();
    }

    @Test void changedOriginalBankIdentityOrSuccessReceiptCannotSupportReturnedFunds() {
        var current = observation(PaymentObservation.Status.SUCCEEDED, 2, NOW);
        var changed = new PaymentObservation(command.id(), command.digest(), current.status(), 2L, NOW, "other-payment", command.amount(), command.payee().accountDigest(), PAID, "paid-receipt", null);
        assertInvalid(() -> new SupplierPaymentReturnPort.Receipt(request, PARTIALLY_RETURNED, 2, NOW, NOW.plusSeconds(120), changed, List.of(item("one", "20"))));
        var anotherReceipt = new PaymentObservation(command.id(), command.digest(), current.status(), 2L, NOW, current.paymentReference(), command.amount(), command.payee().accountDigest(), PAID, "other-receipt", null);
        assertInvalid(() -> new SupplierPaymentReturnPort.Receipt(request, PARTIALLY_RETURNED, 2, NOW, NOW.plusSeconds(120), anotherReceipt, List.of(item("one", "20"))));
        assertThat(receipt(PARTIALLY_RETURNED, 2, item("one", "20")).samePaymentFacts(current)).isTrue();
        assertThat(receipt(PARTIALLY_RETURNED, 2, item("one", "20")).samePaymentFacts(anotherReceipt)).isFalse();
    }

    @Test void newerReturnRevisionCannotRewriteTheSameBankReversalRevision() {
        var previous = receipt(RETURNED, 3, item("one", "70"));
        var bank = previous.current();
        var changed = new PaymentObservation(bank.authorizationId(), bank.commandDigest(), bank.status(), bank.revision(), bank.observedAt(),
                bank.paymentReference(), bank.paidAmount(), bank.accountDigest(), bank.completedAt(), "changed-reversal-receipt", null);
        var next = new SupplierPaymentReturnPort.Receipt(request, RETURNED, 4, NOW, NOW.plusSeconds(120), changed, previous.returns());
        assertThat(next.continues(previous)).isFalse();
    }

    @Test void independentFinanceCannotBeOriginalApplicantOrCashier() {
        var proof = receipt(PARTIALLY_RETURNED, 2, item("one", "20"));
        for (var actor : List.of("alice", "cashier", "finance\n", " finance")) assertInvalid(() -> decision(proof, actor));
        assertInvalid(() -> new SupplierPaymentReturn(UUID.randomUUID(), "other", UUID.randomUUID(), proof, "finance", NOW, "statement", "核对实际回款"));
        assertInvalid(() -> decision(receipt(UNRESOLVED, 2), "finance"));
        assertThat(decision(proof, "finance").registeredBy()).isEqualTo("finance");
    }

    @Test void ledgerKeepsFirstRegistrationAndOnlyAddsNewFunds() {
        var ledger = SupplierPaymentReturns.open(request, NOW.minusSeconds(1)).requireReview(NOW);
        assertThat(ledger.totalReturned()).isEqualTo(money("0"));
        ledger = ledger.register(decision(receipt(CONFIRMED, 2), "finance"));
        assertThat(ledger.reviewRequired()).isFalse();
        var first = decision(receipt(PARTIALLY_RETURNED, 3, item("one", "20")), "finance");
        var recorded = ledger.register(first);
        var repeated = recorded.register(decision(first.receipt(), "finance-two"));
        assertThat(repeated.entries()).isEqualTo(recorded.entries());
        var second = decision(receipt(RETURNED, 4, item("two", "50"), item("one", "20")), "finance-two");
        var cumulative = repeated.register(second);
        assertThat(cumulative.totalReturned()).isEqualTo(money("70")); assertThat(cumulative.reviewRequired()).isTrue();
        assertThat(cumulative.entries().get(0).registrationId()).isEqualTo(first.id());
        assertThat(cumulative.entries().get(1).registrationId()).isEqualTo(second.id());
        assertThat(cumulative.request()).isEqualTo(request);
        assertInvalid(() -> cumulative.register(decision(receipt(CONFIRMED, 5), "finance")));
        assertInvalid(() -> new SupplierPaymentReturns(request, cumulative.version(), cumulative.entries(), false, cumulative.createdAt(), cumulative.updatedAt()));
    }

    @Test void readLeaseAndDecisionAreSeparateAndSingleUse() {
        var queued = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), "demo", command.targetDigest(), 4, request, "finance", NOW));
        var running = queued.claim(NOW, Duration.ofSeconds(90));
        var checked = running.complete(new FinanceResult.Success<>(receipt(PARTIALLY_RETURNED, 2, item("one", "20"))), NOW.plusSeconds(1));
        assertThat(checked.status()).isEqualTo(SupplierPaymentReturnCheck.Status.CHECKED); assertThat(checked.resolutionId()).isNull();
        var decision = new SupplierPaymentReturn(UUID.randomUUID(), "demo", queued.input().id(), checked.receipt(), "finance", NOW.plusSeconds(2), "statement", "核对实际回款");
        var resolved = checked.resolve(decision, decision.registeredAt()); assertThat(resolved.resolutionId()).isEqualTo(decision.id());
        assertInvalid(() -> resolved.resolve(decision, decision.registeredAt()));
        var other = new SupplierPaymentReturn(UUID.randomUUID(), "demo", queued.input().id(), checked.receipt(), "finance-two", decision.registeredAt(), "statement", "核对实际回款");
        assertInvalid(() -> checked.resolve(other, other.registeredAt()));
        assertThat(running.complete(new FinanceResult.Success<>(checked.receipt()), NOW.plusSeconds(90)).issue()).isEqualTo(SupplierPaymentReturnCheck.Issue.TIMEOUT);
    }

    @Test void queuedReadRequiresOriginalTargetAndIndependentRequester() {
        assertInvalid(() -> new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), "demo", "a".repeat(64), 4, request, "finance", NOW));
        assertInvalid(() -> new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), "demo", command.targetDigest(), 4, request, "cashier", NOW));
        assertInvalid(() -> new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), "demo", command.targetDigest(), 4, request, "finance", PAID.minusNanos(1)));
    }

    @Test void logsDoNotExpandBankReferencesAccountsOrAmounts() {
        var proof = receipt(PARTIALLY_RETURNED, 2, item("one", "20"));
        var logged = request + " " + proof + " " + proof.returns().get(0) + " " + decision(proof, "finance") + " " + SupplierPaymentReturns.open(request, NOW);
        assertThat(logged).doesNotContain("debit-1", "bank-one", "paid-receipt", "20.00", "70.00", "supplier-1");
    }

    private SupplierPaymentReturn decision(SupplierPaymentReturnPort.Receipt proof, String actor) {
        return new SupplierPaymentReturn(UUID.randomUUID(), "demo", UUID.randomUUID(), proof, actor, NOW.plusSeconds(1), "bank-statement", "核对实际回款");
    }
    private SupplierPaymentReturnPort.Receipt receipt(SupplierPaymentReturnPort.Status status, long revision, SupplierPaymentReturnPort.BankReceipt... items) {
        return new SupplierPaymentReturnPort.Receipt(request, status, revision, NOW, NOW.plusSeconds(120),
                observation(status == RETURNED ? PaymentObservation.Status.REVERSED : PaymentObservation.Status.SUCCEEDED, revision, NOW), List.of(items));
    }
    private SupplierPaymentReturnPort.BankReceipt item(String id, String amount) {
        return new SupplierPaymentReturnPort.BankReceipt("bank-" + id, command.debitAccount().reference(), money(amount), NOW.minusSeconds(10));
    }
    private PaymentObservation observation(PaymentObservation.Status status, long revision, Instant at) {
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, "bank-payment", command.amount(), command.payee().accountDigest(),
                status == PaymentObservation.Status.REVERSED ? NOW.minusSeconds(1) : PAID, status == PaymentObservation.Status.REVERSED ? "reversal-receipt" : "paid-receipt", null);
    }
    private static SupplierPaymentCommand payment() {
        var hold = new SupplierPayableHoldCommand(authorization());
        var held = new SupplierPayableHoldObservation(hold.id(), hold.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, AUTHORIZED_AT.plusSeconds(3),
                "hold", "ledger", hold.authorization().source().amount(), hold.authorization().payable().account().accountDigest(), AUTHORIZED_AT.plusSeconds(3), null);
        return new SupplierPaymentCommand(hold, held, "cashier", new PaymentAccountsPort.DebitAccount("debit-1", "公司银行账户", "****5678", "CNY", "v1"), AUTHORIZED_AT.plusSeconds(4));
    }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(DomainException.class); }
}
