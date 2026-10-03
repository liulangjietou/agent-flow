package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 应付读取与财务授权分别验证，旧余额、别人的读取或迟到结果不能被重复消费。
 * @author owlzhangfq@gmail.com
 */
class SupplierPayableReviewTest {
    @Test void successfulReadCanSupportOnlyOneExactAuthorizationByTheRequestingFinanceActor() {
        var queued = queued(); var running = queued.claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        var payable = current(queued.input().source(), "30", AUTHORIZED_AT);
        var ready = running.complete(new FinanceResult.Success<>(payable), AUTHORIZED_AT);
        assertThat(ready.status()).isEqualTo(SupplierPayableReview.Status.READY); assertThat(ready.consumedAuthorizationId()).isNull();
        var authorization = new SupplierPaymentAuthorization(UUID.randomUUID(), queued.input().source(), payable, "finance", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(60));
        var consumed = ready.consume(authorization, AUTHORIZED_AT);
        assertThat(consumed.supports(authorization)).isTrue(); assertThat(consumed.usable(AUTHORIZED_AT)).isFalse();
        assertThat(ready.supports(authorization)).isFalse(); assertThat(consumed.input()).isSameAs(queued.input());
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> consumed.consume(authorization, AUTHORIZED_AT));
        var otherFinance = new SupplierPaymentAuthorization(UUID.randomUUID(), queued.input().source(), payable, "other-finance", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(60));
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> ready.consume(otherFinance, AUTHORIZED_AT));
        var changed = new SupplierPaymentAuthorization(UUID.randomUUID(), queued.input().source(), current(queued.input().source(), "20", AUTHORIZED_AT), "finance", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(60));
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> ready.consume(changed, AUTHORIZED_AT));
        assertThat(consumed.supports(changed)).isFalse();
    }

    @Test void receiptTimeNeverExtendsAnOlderErpObservationWindow() {
        var queued = queued(); var running = queued.claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        var payable = current(queued.input().source(), "30", AUTHORIZED_AT.minusSeconds(250));
        var ready = running.complete(new FinanceResult.Success<>(payable), AUTHORIZED_AT.plusSeconds(10));
        assertThat(ready.usable(AUTHORIZED_AT.plusSeconds(49))).isTrue(); assertThat(ready.usable(AUTHORIZED_AT.plusSeconds(50))).isFalse();
        assertThat(ready.usable(AUTHORIZED_AT.plusSeconds(9))).isFalse();
        var authorization = new SupplierPaymentAuthorization(UUID.randomUUID(), queued.input().source(), payable, "finance", AUTHORIZED_AT.plusSeconds(49), AUTHORIZED_AT.plusSeconds(3600));
        var consumed = ready.consume(authorization, authorization.authorizedAt()); assertThat(consumed.supports(authorization)).isTrue();
        fails("SUPPLIER_PAYABLE_REVIEW_UNAVAILABLE", () -> ready.consume(authorization, AUTHORIZED_AT.plusSeconds(50)));
    }

    @Test void insufficientBalanceIsBlockedWhileFutureStaleAndRejectedResponsesNeverBecomeReady() {
        var running = queued().claim(AUTHORIZED_AT, Duration.ofSeconds(30)); var source = running.input().source();
        var blocked = running.complete(new FinanceResult.Success<>(current(source, "30.01", AUTHORIZED_AT)), AUTHORIZED_AT);
        assertThat(blocked.status()).isEqualTo(SupplierPayableReview.Status.BLOCKED); assertThat(blocked.issue()).isEqualTo(SupplierPayableReview.Issue.PAYABLE_CHANGED);
        for (var observedAt : java.util.List.of(AUTHORIZED_AT.plusSeconds(1), AUTHORIZED_AT.minusSeconds(300))) {
            var invalid = running.complete(new FinanceResult.Success<>(current(source, "30", observedAt)), AUTHORIZED_AT);
            assertThat(invalid.status()).isEqualTo(SupplierPayableReview.Status.UNAVAILABLE); assertThat(invalid.issue()).isEqualTo(SupplierPayableReview.Issue.INVALID_RESPONSE); assertThat(invalid.payable()).isNull();
        }
        var rejected = running.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.PROCUREMENT_PAYABLE_UNAVAILABLE), AUTHORIZED_AT);
        assertThat(rejected.status()).isEqualTo(SupplierPayableReview.Status.BLOCKED); assertThat(rejected.issue()).isEqualTo(SupplierPayableReview.Issue.PAYABLE_REJECTED);
        var timeout = running.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), AUTHORIZED_AT);
        assertThat(timeout.status()).isEqualTo(SupplierPayableReview.Status.UNAVAILABLE); assertThat(timeout.issue()).isEqualTo(SupplierPayableReview.Issue.TIMEOUT);
    }

    @Test void expiredReadLeaseDiscardsLateEvidenceAndKeepsTheOriginalInputForRetry() {
        var running = queued().claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        var expired = running.complete(new FinanceResult.Success<>(current(running.input().source(), "30", AUTHORIZED_AT)), AUTHORIZED_AT.plusSeconds(30));
        assertThat(expired.status()).isEqualTo(SupplierPayableReview.Status.QUEUED); assertThat(expired.payable()).isNull(); assertThat(expired.input()).isSameAs(running.input());
        var retry = expired.claim(expired.updatedAt(), Duration.ofSeconds(30)); assertThat(retry.attempts()).isEqualTo(2);
        assertThat(retry.complete(new FinanceResult.Success<>(current(retry.input().source(), "30", retry.updatedAt())), retry.updatedAt()).status()).isEqualTo(SupplierPayableReview.Status.READY);
        var stopped = retry.voidSource(retry.updatedAt()); assertThat(stopped.status()).isEqualTo(SupplierPayableReview.Status.VOIDED);
        fails("SUPPLIER_PAYABLE_REVIEW_STATE_CONFLICT", () -> stopped.complete(new FinanceResult.Success<>(current(stopped.input().source(), "30", stopped.updatedAt())), stopped.updatedAt()));
    }

    @Test void applicantAndReadBeforeActualApprovalAreRejectedAndLogsDoNotExpandFinancialFacts() {
        var source = approved();
        fails("INVALID_SUPPLIER_PAYABLE_REVIEW", () -> SupplierPayableReview.queue(UUID.randomUUID(), source, "alice", AUTHORIZED_AT));
        fails("INVALID_SUPPLIER_PAYABLE_REVIEW", () -> SupplierPayableReview.queue(UUID.randomUUID(), source, "finance", APPROVED_AT.minusNanos(1)));
        fails("INVALID_SUPPLIER_PAYABLE_REVIEW", () -> SupplierPayableReview.queue(UUID.randomUUID(), source, "finance\n", AUTHORIZED_AT));
        var queued = queued(); assertThat(queued.toString()).isEqualTo("SupplierPayableReview[id=" + queued.input().id() + ", status=QUEUED]");
        assertThat(queued.input().toString()).isEqualTo("SupplierPayableReviewInput[id=" + queued.input().id() + "]");
    }

    private static SupplierPayableReview queued() { return SupplierPayableReview.queue(UUID.randomUUID(), approved(), "finance", AUTHORIZED_AT); }
    private static void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
