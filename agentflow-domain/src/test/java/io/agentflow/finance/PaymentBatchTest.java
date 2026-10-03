package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 批次只组合原始执行意图，不能通过组批修改资金对象或跳过单笔确认。
 * @author owlzhangfq@gmail.com
 */
class PaymentBatchTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test void preservesOriginalRequestsAndExactAmountsWithoutInventingPaymentStatus() {
        var first = registration(); var second = registration(); var choices = new ArrayList<>(List.of(first, second));
        var batch = PaymentBatch.submitted(UUID.randomUUID(), " 已逐笔核对 ", choices, NOW);
        choices.clear(); assertThat(batch.items()).hasSize(2); assertThat(batch.comment()).isEqualTo("已逐笔核对");
        assertThat(batch.total()).isEqualTo("200.02"); assertThat(batch.legalEntityId()).isEqualTo(ENTITY);
        assertThat(batch.items().get(0).authorizationId()).isEqualTo(first.authorization().terms().id());
        assertThat(batch.items().get(1).requestId()).isEqualTo(second.request().input().id());
        assertThat(first.authorization().execution()).isNull(); assertThat(first.request().status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED);
        assertThatThrownBy(() -> batch.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(batch.toString()).doesNotContain("cashier", "debit-original", "已逐笔核对", "200.02");
    }

    @Test void groupMustShareOriginalTenantLegalEntityCurrencyAndGateway() {
        var first = registration();
        for (var authorization : List.of(authorization("other", ENTITY, "CNY", "a", "100.01"), authorization("demo", UUID.randomUUID(), "CNY", "a", "100.01"),
                authorization("demo", ENTITY, "USD", "a", "100.01"), authorization("demo", ENTITY, "CNY", "b", "100.01"))) {
            var other = registration(authorization, "cashier", "debit-original", "v1");
            assertThatThrownBy(() -> batch(List.of(first, other))).isInstanceOf(DomainException.class);
        }
    }

    @Test void cashierAndBothAccountIdentifiersStayIdenticalAcrossTheWholeGroup() {
        var first = registration(); var authorization = authorization("demo", ENTITY, "CNY", "a", "100.01");
        for (var other : List.of(registration(authorization, "other-cashier", "debit-original", "v1"), registration(authorization, "cashier", "another-account", "v1"),
                registration(authorization, "cashier", "debit-original", "v2"))) {
            assertThatThrownBy(() -> batch(List.of(first, other))).isInstanceOf(DomainException.class);
        }
    }

    @Test void duplicateUnknownStartedAndFutureRequestsCannotBecomeNewBatchMembers() {
        var first = registration(); var second = registration();
        assertThatThrownBy(() -> batch(List.of(first, first))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> batch(List.of(new PaymentBatch.Registration(first.authorization(), second.request())))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> batch(List.of(new PaymentBatch.Registration(first.authorization(), first.request().claim(NOW, Duration.ofSeconds(15)))))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentBatch.submitted(UUID.randomUUID(), "核对", List.of(first), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> batch(Collections.nCopies(PaymentBatch.MAX_ITEMS + 1, first))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> batch(List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> batch(Collections.singletonList(null))).isInstanceOf(DomainException.class);
    }

    @Test void totalCanExceedTheSinglePaymentLimitWithoutRoundingOrOverflow() {
        var choices = List.of(registration(authorization("demo", ENTITY, "CNY", "a", Money.MAX_VALUE.toPlainString()), "cashier", "debit-original", "v1"),
                registration(authorization("demo", ENTITY, "CNY", "a", Money.MAX_VALUE.toPlainString()), "cashier", "debit-original", "v1"));
        assertThat(batch(choices).total()).isEqualTo("1999999999999999.98");
    }

    private static PaymentBatch batch(List<PaymentBatch.Registration> choices) { return PaymentBatch.submitted(UUID.randomUUID(), "核对", choices, NOW); }
    private static PaymentBatch.Registration registration() { return registration(authorization("demo", ENTITY, "CNY", "a", "100.01"), "cashier", "debit-original", "v1"); }
    private static PaymentBatch.Registration registration(PaymentAuthorization authorization, String cashier, String account, String version) {
        return new PaymentBatch.Registration(authorization, PaymentExecutionRequest.queue(UUID.randomUUID(), authorization, cashier, account, version, NOW));
    }
    private static PaymentAuthorization authorization(String tenant, UUID entity, String currency, String target, String amount) {
        var payee = new EmployeeAccountSnapshot(entity, "alice", "original-payee", "****1234", "c".repeat(64), "v1");
        var terms = new PaymentAuthorization.Terms(UUID.randomUUID(), tenant, PaymentCommand.Purpose.EMPLOYEE_ADVANCE,
                new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 1, 5, 3), new Money(new BigDecimal(amount), currency), payee,
                UUID.randomUUID(), "d".repeat(64), 1, "original-voucher", target.repeat(64));
        return new PaymentAuthorization(terms, new PaymentAuthorization.Decision("finance", NOW, NOW.plusSeconds(60)), 1, PaymentAuthorization.Status.AUTHORIZED, NOW, null, null);
    }
}
