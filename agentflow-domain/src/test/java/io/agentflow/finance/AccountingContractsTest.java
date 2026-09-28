package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 开放期间与科目映射必须完整、同源且仍有效，禁止用缺省财务事实放行。
 * @author owlzhangfq@gmail.com
 */
class AccountingContractsTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");

    @Test
    void periodCoversExactOriginalDateAndBothTemporalBoundaries() {
        var request = new AccountingPeriodPort.Request(ENTITY, "CNY", DATE);
        var period = new AccountingPeriodPort.OpenPeriod(request, "2026-09", "v1", DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), NOW, NOW.plusSeconds(60));
        assertThat(period.matches(request, NOW)).isTrue(); assertThat(period.matches(request, NOW.plusSeconds(60))).isFalse();
        assertThat(period.matches(request, NOW.minusSeconds(1))).isFalse();
        assertThat(period.matches(new AccountingPeriodPort.Request(ENTITY, "CNY", DATE.plusDays(1)), NOW)).isFalse();
        assertThat(period.matches(new AccountingPeriodPort.Request(ENTITY, "USD", DATE), NOW)).isFalse();
        assertThat(period.matches(new AccountingPeriodPort.Request(UUID.randomUUID(), "CNY", DATE), NOW)).isFalse();
        assertThatThrownBy(() -> new AccountingPeriodPort.OpenPeriod(request, "2026-10", "v1", DATE.plusDays(3), DATE.plusDays(33), NOW, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AccountingPeriodPort.OpenPeriod(request, "2026-09", "v1", DATE, DATE, NOW, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void mappingsAreCanonicalImmutableAndContainEveryRequestedKeyExactlyOnce() {
        var expense = new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE");
        var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        var keys = new ArrayList<>(List.of(expense, payable));
        var request = new AccountMappingPort.Request(ENTITY, "CNY", keys); keys.clear();
        var entries = new ArrayList<>(List.of(new AccountMappingPort.Entry(expense, "6602"), new AccountMappingPort.Entry(payable, "2241")));
        var value = new AccountMappingPort.Mapping(request, "v1", NOW, NOW.plusSeconds(60), entries); entries.clear();
        var reordered = new AccountMappingPort.Request(ENTITY, "CNY", List.of(payable, expense));
        assertThat(value.matches(reordered, NOW)).isTrue(); assertThat(value.account(expense)).isEqualTo("6602");
        assertThat(value.matches(reordered, NOW.plusSeconds(60))).isFalse(); assertThat(value.matches(reordered, NOW.minusSeconds(1))).isFalse();
        assertThat(value.matches(new AccountMappingPort.Request(ENTITY, "USD", reordered.keys()), NOW)).isFalse();
        assertThatThrownBy(() -> new AccountMappingPort.Mapping(request, "v1", NOW, NOW.plusSeconds(60), List.of(new AccountMappingPort.Entry(expense, "6602")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AccountMappingPort.Mapping(request, "v1", NOW, NOW.plusSeconds(60), List.of(new AccountMappingPort.Entry(expense, "6602"), new AccountMappingPort.Entry(expense, "6603")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AccountMappingPort.Request(ENTITY, "CNY", List.of(expense, expense))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> value.account(new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "unrequested"))).isInstanceOf(DomainException.class);
    }

    @Test
    void mappingSelectorsCannotDefaultBankAccountOrExpenseCategory() {
        for (var role : List.of(AccountMappingPort.Role.BANK, AccountMappingPort.Role.EXPENSE)) {
            assertThatThrownBy(() -> new AccountMappingPort.Key(role, "")).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> new AccountMappingPort.Key(role, " ")).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "another-employee")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AccountMappingPort.Entry(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), " ")).isInstanceOf(DomainException.class);
    }
}
