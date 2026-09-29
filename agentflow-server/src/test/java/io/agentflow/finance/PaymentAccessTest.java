package io.agentflow.finance;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 付款岗位与法人范围独立授权，财务和管理员不能绕过当轮敏感字段策略。
 * @author owlzhangfq@gmail.com
 */
class PaymentAccessTest {
    private final CurrentActor actors = new CurrentActor();
    private final VoucherAccess financial = mock(VoucherAccess.class);
    private final JdbcPaymentAuthorizationRepository authorizations = mock(JdbcPaymentAuthorizationRepository.class);
    private final JdbcVoucherOperationRepository vouchers = mock(JdbcVoucherOperationRepository.class);
    private final PaymentPersonnel personnel = mock(PaymentPersonnel.class);
    private final PaymentAccess access = new PaymentAccess(actors, financial, authorizations, vouchers, personnel);
    private final PaymentAuthorization authorization = authorization();
    @AfterEach void clearActor() { actors.clear(); }

    @Test void administratorAndFinanceRolesDoNotImplicitlyGrantCashierReadOrExecution() {
        for (var roles : java.util.List.of(Set.of("ADMIN"), Set.of("FINANCE"), Set.of("ADMIN", "FINANCE"))) {
            actors.set(new Actor("tenant", "admin", roles));
            assertThatThrownBy(() -> access.requireCashier(authorization.terms().id())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        }
        verifyNoInteractions(authorizations, personnel);
    }
    @Test void cashierReadUsesAuthenticatedTenantAndRequiresCurrentAppointmentInOriginalEntity() {
        actors.set(new Actor("tenant", "cashier", Set.of("CASHIER"))); when(authorizations.find("tenant", authorization.terms().id())).thenReturn(Optional.of(authorization));
        assertThatThrownBy(() -> access.requireCashier(authorization.terms().id())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        when(personnel.eligible("tenant", "cashier", authorization.terms().payee().legalEntityId())).thenReturn(true);
        assertThat(access.requireCashier(authorization.terms().id())).isEqualTo(authorization); verifyNoInteractions(financial);
        actors.set(new Actor("foreign", "cashier", Set.of("CASHIER")));
        assertThatThrownBy(() -> access.requireCashier(authorization.terms().id())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
    }
    @Test void dualRoleApplicantAndAuthorizerStillCannotExecuteTheirOwnPayment() {
        when(authorizations.find("tenant", authorization.terms().id())).thenReturn(Optional.of(authorization));
        for (String user : java.util.List.of("alice", "finance")) {
            actors.set(new Actor("tenant", user, Set.of("FINANCE", "CASHIER", "ADMIN")));
            when(personnel.eligible("tenant", user, authorization.terms().payee().legalEntityId())).thenReturn(true);
            assertThatThrownBy(() -> access.requireExecution(authorization.terms().id())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        }
        actors.set(new Actor("tenant", "cashier", Set.of("CASHIER"))); when(personnel.eligible("tenant", "cashier", authorization.terms().payee().legalEntityId())).thenReturn(true);
        assertThat(access.requireExecution(authorization.terms().id())).isEqualTo(authorization);
    }
    @Test void financeAuthorizationActionsContinueToEnforceOriginalRoundFieldPermission() {
        actors.set(new Actor("tenant", "admin", Set.of("ADMIN", "FINANCE")));
        when(authorizations.find("tenant", authorization.terms().id())).thenReturn(Optional.of(authorization));
        var binding = authorization.terms().binding();
        when(financial.requireFinance(binding.applicationId(), binding.roundNo())).thenThrow(new DomainException("FORBIDDEN", "Sensitive financial fields are unavailable"));
        assertThatThrownBy(() -> access.requireFinanceAuthorization(authorization.terms().id())).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
        verify(financial).requireFinance(binding.applicationId(), binding.roundNo()); verifyNoInteractions(personnel);
    }
    private static PaymentAuthorization authorization() {
        var now = Instant.parse("2026-09-28T12:00:00Z");
        var terms = new PaymentAuthorization.Terms(UUID.randomUUID(), "tenant", PaymentCommand.Purpose.EMPLOYEE_ADVANCE, new PaymentCommand.Binding(UUID.randomUUID(), UUID.randomUUID(), 2, 5, 3),
                new Money(new BigDecimal("100"), "CNY"), new EmployeeAccountSnapshot(UUID.randomUUID(), "alice", "account-1", "****1234", "a".repeat(64), "v1"),
                UUID.randomUUID(), "b".repeat(64), 1, "voucher-1", "c".repeat(64));
        return new PaymentAuthorization(terms, new PaymentAuthorization.Decision("finance", now, now.plusSeconds(60)), 1, PaymentAuthorization.Status.AUTHORIZED, now, null, null);
    }
}
