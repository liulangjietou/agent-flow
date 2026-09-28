package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 支付命令摘要、岗位分离和到账证据边界，不把传输成功等同支付完成。
 * @author owlzhangfq@gmail.com
 */
class PaymentCommandTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BUSINESS = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID APPLICATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ENTITY = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void independentUtf8DigestVectorBindsEveryFinancialIdentityAndNormalizesMoney() {
        var command = command("100", "finance", "cashier");
        // Python hashlib 与 struct.pack('>i', UTF-8 字节长度) 独立生成，包含中文出款账户引用。
        assertThat(command.digest()).isEqualTo("34eb42a5e347d0e73bf068611838c69af2162fae5a9c74f9f93fae0be95250c0");
        assertThat(command("100.000", "finance", "cashier").digest()).isEqualTo(command.digest());
        assertThat(command("100.01", "finance", "cashier").digest()).isNotEqualTo(command.digest());
        assertThat(command("100", "other-finance", "cashier").digest()).isNotEqualTo(command.digest());
        assertThat(command("100", "finance", "other-cashier").digest()).isNotEqualTo(command.digest());
        var otherAccount = new EmployeeAccountSnapshot(ENTITY, "alice", "account-2", "****5678", "b".repeat(64), "account-v2");
        assertThat(new PaymentCommand(ID, "tenant-a", command.purpose(), command.binding(), command.amount(), command.debitAccountReference(),
                otherAccount, command.voucherReference(), command.authorization()).digest()).isNotEqualTo(command.digest());
        for (var binding : List.of(new PaymentCommand.Binding(BUSINESS, APPLICATION, 3, 7, 4), new PaymentCommand.Binding(BUSINESS, APPLICATION, 2, 8, 4),
                new PaymentCommand.Binding(BUSINESS, APPLICATION, 2, 7, 5))) {
            assertThat(new PaymentCommand(ID, "tenant-a", command.purpose(), binding, command.amount(), command.debitAccountReference(), command.payee(),
                    command.voucherReference(), command.authorization()).digest()).isNotEqualTo(command.digest());
        }
        assertThat(command.toString()).doesNotContain("出款账户", "employee-account", "alice", "voucher-1");
    }

    @Test
    void applicantAuthorizerAndExecutorAreDifferentAndZeroPaymentIsNotACommand() {
        for (String[] people : List.of(new String[]{"alice", "cashier"}, new String[]{"finance", "alice"}, new String[]{"finance", "finance"})) {
            assertThatThrownBy(() -> command("100", people[0], people[1])).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> command("0", "finance", "cashier")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentCommand.Binding(BUSINESS, APPLICATION, 0, 1, 1)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentCommand.Authorization("finance", "cashier", NOW, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void authorizationWindowIsExclusiveButLaterQueryCanConfirmAnAlreadySentPayment() {
        var command = command("100", "finance", "cashier");
        assertThatCode(() -> command.requireSendAt(NOW.minusSeconds(60))).doesNotThrowAnyException();
        assertThatThrownBy(() -> command.requireSendAt(NOW.minusSeconds(61))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> command.requireSendAt(NOW.plusSeconds(3600))).isInstanceOf(DomainException.class);
        var paid = paid(command, PaymentObservation.Status.SUCCEEDED, command.amount(), command.payee().accountDigest(), NOW.plusSeconds(7200));
        assertThat(paid.matches(command, true, NOW.plusSeconds(7200))).isTrue();
    }

    @Test
    void exactAmountCurrencyAccountAndOriginalAuthorizationAreRequiredToConfirmPayment() {
        var command = command("100", "finance", "cashier");
        assertThat(paid(command, PaymentObservation.Status.SUCCEEDED, command.amount(), command.payee().accountDigest(), NOW).matches(command, false, NOW)).isTrue();
        for (var amount : List.of(new Money(new BigDecimal("99.99"), "CNY"), new Money(new BigDecimal("100.00"), "USD"))) {
            assertThat(paid(command, PaymentObservation.Status.SUCCEEDED, amount, command.payee().accountDigest(), NOW).matches(command, true, NOW)).isFalse();
        }
        assertThat(paid(command, PaymentObservation.Status.SUCCEEDED, command.amount(), "b".repeat(64), NOW).matches(command, true, NOW)).isFalse();
        assertThat(paid(command, PaymentObservation.Status.SUCCEEDED, command.amount(), command.payee().accountDigest(), NOW.plusSeconds(1)).matches(command, true, NOW)).isFalse();
        assertThat(paid(command, PaymentObservation.Status.SUCCEEDED, command.amount(), command.payee().accountDigest(), NOW.minusSeconds(61)).matches(command, true, NOW)).isFalse();
        var previous = command("99", "finance", "cashier");
        assertThat(paid(previous, PaymentObservation.Status.SUCCEEDED, previous.amount(), previous.payee().accountDigest(), NOW).matches(command, true, NOW)).isFalse();
    }

    @Test
    void pendingFailureReversalAndMissingHaveDistinctEvidenceShapes() {
        var command = command("100", "finance", "cashier");
        var pending = new PaymentObservation(ID, command.digest(), PaymentObservation.Status.PENDING, 1L, NOW, "bank-1", null, null, null, null, null);
        var failed = new PaymentObservation(ID, command.digest(), PaymentObservation.Status.FAILED, 2L, NOW, "bank-1", null, null, null, null, PaymentObservation.Failure.ACCOUNT_UNAVAILABLE);
        var reversed = paid(command, PaymentObservation.Status.REVERSED, command.amount(), command.payee().accountDigest(), NOW);
        assertThat(pending.matches(command, false, NOW)).isTrue(); assertThat(failed.matches(command, true, NOW)).isTrue();
        assertThat(reversed.matches(command, true, NOW)).isTrue(); assertThat(reversed.status()).isNotEqualTo(PaymentObservation.Status.SUCCEEDED);
        var missing = new PaymentObservation(ID, command.digest(), PaymentObservation.Status.NOT_FOUND, 0L, NOW, null, null, null, null, null, null);
        assertThat(missing.matches(command, false, NOW)).isFalse(); assertThat(missing.matches(command, true, NOW)).isTrue();
        assertThatThrownBy(() -> paid(command, PaymentObservation.Status.PENDING, command.amount(), command.payee().accountDigest(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentObservation(ID, command.digest(), PaymentObservation.Status.FAILED, 1L, NOW, "bank-1", null, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentObservation(ID, command.digest(), PaymentObservation.Status.SUCCEEDED, 0L, NOW, "bank-1", command.amount(), command.payee().accountDigest(), NOW, "receipt-1", null)).isInstanceOf(DomainException.class);
    }

    private static PaymentCommand command(String amount, String authorizer, String executor) {
        return new PaymentCommand(ID, "tenant-a", PaymentCommand.Purpose.EMPLOYEE_ADVANCE, new PaymentCommand.Binding(BUSINESS, APPLICATION, 2, 7, 4),
                new Money(new BigDecimal(amount), "CNY"), "出款账户:1", new EmployeeAccountSnapshot(ENTITY, "alice", "employee-account-1", "****1234", "a".repeat(64), "account-v1"),
                "voucher-1", new PaymentCommand.Authorization(authorizer, executor, NOW.minusSeconds(60), NOW.plusSeconds(3600)));
    }
    private static PaymentObservation paid(PaymentCommand command, PaymentObservation.Status status, Money amount, String account, Instant at) {
        return new PaymentObservation(command.id(), command.digest(), status, 2L, at, "bank-1", amount, account, at, "receipt-1", null);
    }
}
