package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 账户读取与人工授权严格分离，原批准对象及证据的单次消费不可被新的账号快照绕过。
 * @author owlzhangfq@gmail.com
 */
class PaymentPayeeReviewTest {
    private static final Instant NOW = VoucherCommandTest.advanceCommand().createdAt().plusSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(20);
    private final VoucherOperation voucher = voucher();
    private final EmployeeAccountSnapshot originalAccount = account("original", "alice");
    private final PaymentAuthorization original = PaymentAuthorization.issue(UUID.randomUUID(), voucher, originalAccount, "finance", NOW, NOW.plusSeconds(3600));
    private final PaymentAuthorization ended = original.voidBeforeExecution("finance", "本人账户需要重新核对", NOW);

    @Test void onlyEndedOriginalAuthorizationCanProduceReviewForTheSameVoucherAndFinanceActor() {
        assertThatThrownBy(() -> PaymentPayeeReview.queue(UUID.randomUUID(), original, voucher, "finance", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentPayeeReview.queue(UUID.randomUUID(), ended, voucher, "alice", NOW)).isInstanceOf(DomainException.class);
        var queued = queue(); assertThat(queued.input().original()).isEqualTo(original.terms());
        assertThat(queued.matchesSource(ended, voucher, NOW)).isTrue(); assertThat(queued.matchesSource(original, voucher, NOW)).isFalse();
        assertThat(queued.toString()).doesNotContain("original", "alice", "finance");
    }

    @Test void validNewAccountIsOnlyReadyForFiveMinutesAndStillNeedsExplicitAuthorization() {
        var fresh = new EmployeeAccountPort.Account(account("new", "alice"), NOW.plusSeconds(3600));
        var ready = queue().claim(NOW, LEASE).complete(new FinanceResult.Success<>(fresh), NOW);
        assertThat(ready.status()).isEqualTo(PaymentPayeeReview.Status.READY); assertThat(ready.consumedAuthorizationId()).isNull();
        assertThat(ready.account().validUntil()).isEqualTo(NOW.plus(PaymentPayeeReview.MAX_EVIDENCE_AGE));
        assertThat(ready.usable(ready.account().validUntil().minusNanos(1))).isTrue(); assertThat(ready.usable(ready.account().validUntil())).isFalse();
        assertThat(original.terms().payee()).isEqualTo(originalAccount);
        var authorized = PaymentAuthorization.issue(UUID.randomUUID(), voucher, fresh.snapshot(), "finance", NOW, NOW.plusSeconds(3600));
        var consumed = ready.consume(authorized, NOW); assertThat(consumed.supports(authorized)).isTrue();
        assertThat(consumed.status()).isEqualTo(PaymentPayeeReview.Status.CONSUMED);
        assertThatThrownBy(() -> consumed.consume(authorized, NOW)).isInstanceOf(DomainException.class);
        assertThat(consumed.supports(original)).isFalse();
    }

    @Test void differentActorAccountOrBusinessCannotConsumeReadyEvidence() {
        var ready = ready();
        for (var authorization : new PaymentAuthorization[]{original,
                PaymentAuthorization.issue(UUID.randomUUID(), voucher, ready.account().snapshot(), "other-finance", NOW, NOW.plusSeconds(60)),
                PaymentAuthorization.issue(UUID.randomUUID(), voucher, account("third", "alice"), "finance", NOW, NOW.plusSeconds(60))}) {
            assertThatThrownBy(() -> ready.consume(authorization, NOW)).isInstanceOf(DomainException.class);
        }
        var atExpiry = PaymentAuthorization.issue(UUID.randomUUID(), voucher, ready.account().snapshot(), "finance", ready.account().validUntil(), NOW.plusSeconds(600));
        assertThatThrownBy(() -> ready.consume(atExpiry, ready.account().validUntil())).isInstanceOf(DomainException.class);
    }

    @Test void wrongOwnerExpiredAccountAndDependencyFailureNeverProduceUsableEvidence() {
        var running = queue().claim(NOW, LEASE);
        for (var account : new EmployeeAccountPort.Account[]{new EmployeeAccountPort.Account(account("other", "bob"), NOW.plusSeconds(60)),
                new EmployeeAccountPort.Account(account("expired", "alice"), NOW)}) {
            var bad = running.complete(new FinanceResult.Success<>(account), NOW);
            assertThat(bad.status()).isEqualTo(PaymentPayeeReview.Status.UNAVAILABLE); assertThat(bad.account()).isNull();
        }
        assertThat(running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.ACCOUNT_UNAVAILABLE), NOW).status()).isEqualTo(PaymentPayeeReview.Status.BLOCKED);
        assertThat(running.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), NOW).issue()).isEqualTo(PaymentPayeeReview.Issue.TIMEOUT);
    }

    @Test void expiredLeaseAndVoidedSourceDiscardLateAccountResults() {
        var running = queue().claim(NOW, LEASE); var result = new FinanceResult.Success<>(new EmployeeAccountPort.Account(account("new", "alice"), NOW.plusSeconds(60)));
        var expired = running.complete(result, running.leaseUntil());
        assertThat(expired.status()).isEqualTo(PaymentPayeeReview.Status.QUEUED); assertThat(expired.account()).isNull(); assertThat(expired.input()).isEqualTo(running.input());
        assertThat(expired.claim(running.leaseUntil(), LEASE).attempts()).isEqualTo(2);
        var voided = running.voidSource(NOW);
        assertThatThrownBy(() -> voided.complete(result, NOW)).isInstanceOf(DomainException.class);
    }

    @Test void restoredReadyAndConsumedRecordsMustHaveConsistentAccountAndTime() {
        var ready = ready();
        assertThatThrownBy(() -> new PaymentPayeeReview(ready.input(), ready.version(), ready.status(), ready.attempts(), ready.updatedAt(), null,
                ready.account(), null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentPayeeReview(ready.input(), ready.version(), PaymentPayeeReview.Status.CONSUMED, ready.attempts(), ready.updatedAt(), null,
                ready.account(), ready.checkedAt(), original.terms().id(), null)).isInstanceOf(DomainException.class);
    }

    private PaymentPayeeReview queue() { return PaymentPayeeReview.queue(UUID.randomUUID(), ended, voucher, "finance", NOW); }
    private PaymentPayeeReview ready() { return queue().claim(NOW, LEASE).complete(new FinanceResult.Success<>(new EmployeeAccountPort.Account(account("new", "alice"), NOW.plusSeconds(60))), NOW); }
    private EmployeeAccountSnapshot account(String reference, String employee) { return new EmployeeAccountSnapshot(voucher.input().command().legalEntityId(), employee, reference, "****5678", "b".repeat(64), reference + "-version"); }
    private static VoucherOperation voucher() {
        var command = VoucherCommandTest.advanceCommand(); var at = NOW.minusSeconds(2);
        return VoucherOperation.queue(new VoucherOperation.Input(command, "a".repeat(64)), at).claim(at, LEASE)
                .complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, NOW,
                        "posting-1", "voucher-1", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), NOW, null)), NOW);
    }
}
