package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 出款账户目录的身份、时效、唯一引用和掩码边界，不能用默认账户填补空结果。
 * @author owlzhangfq@gmail.com
 */
class PaymentAccountsPortTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final UUID entity = UUID.randomUUID();
    private final PaymentAccountsPort.Request request = new PaymentAccountsPort.Request(entity, "CNY", "cashier");
    private final PaymentAccountsPort.DebitAccount account = new PaymentAccountsPort.DebitAccount("account-1", "业务结算账户", "****1234", "CNY", "v1");

    @Test
    void selectionRequiresOriginalCashierEntityCurrencyAndExclusiveValidity() {
        var directory = directory(List.of(account));
        assertThat(directory.matches(request, NOW)).isTrue(); assertThat(directory.account("account-1", NOW)).isEqualTo(account);
        for (var changed : List.of(new PaymentAccountsPort.Request(entity, "CNY", "another"), new PaymentAccountsPort.Request(entity, "USD", "cashier"),
                new PaymentAccountsPort.Request(UUID.randomUUID(), "CNY", "cashier"))) assertThat(directory.matches(changed, NOW)).isFalse();
        assertThat(directory.matches(request, NOW.minusSeconds(1))).isFalse();
        assertThat(directory.matches(request, NOW.plusSeconds(60))).isFalse();
        assertThatThrownBy(() -> directory.account("account-1", NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> directory.account("other", NOW)).isInstanceOf(DomainException.class);
        assertThat(directory.toString()).doesNotContain("cashier", "account-1", "1234"); assertThat(account.toString()).doesNotContain("account-1", "1234");
    }

    @Test
    void emptyIsNotDefaultAndDuplicateOrUnboundedDirectoriesFailClosed() {
        var empty = directory(List.of()); assertThat(empty.matches(request, NOW)).isTrue();
        assertThatThrownBy(() -> empty.account("default", NOW)).isInstanceOf(DomainException.class);
        for (var accounts : List.of(List.of(account, account), List.of(new PaymentAccountsPort.DebitAccount("account-2", "外币账户", "****1234", "USD", "v1")),
                java.util.stream.IntStream.rangeClosed(0, PaymentAccountsPort.MAX_DEBIT_ACCOUNTS).mapToObj(i -> new PaymentAccountsPort.DebitAccount("account-" + i, "业务账户", "****1234", "CNY", "v1")).toList())) {
            assertThatThrownBy(() -> directory(accounts)).isInstanceOf(DomainException.class);
        }
        var mutable = new ArrayList<>(List.of(account)); var frozen = directory(mutable); mutable.clear(); assertThat(frozen.accounts()).containsExactly(account);
        assertThatThrownBy(() -> frozen.accounts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fullAccountNumbersAndIncompleteIdentitiesCannotEnterPaymentEvidence() {
        for (var mask : List.of("6212345678901234", "****12345678", "1234****5678 90", "not-masked")) {
            assertThatThrownBy(() -> new PaymentAccountsPort.DebitAccount("account-1", "业务账户", mask, "CNY", "v1")).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new PaymentAccountsPort.Request(entity, "CNY", " ")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentAccountsPort.PayeeRequest(null, "alice")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentAccountsPort.Directory(request, "v1", NOW, NOW, List.of(account))).isInstanceOf(DomainException.class);
    }
    private PaymentAccountsPort.Directory directory(List<PaymentAccountsPort.DebitAccount> values) {
        return new PaymentAccountsPort.Directory(request, "directory-v1", NOW, NOW.plusSeconds(60), values);
    }
}
