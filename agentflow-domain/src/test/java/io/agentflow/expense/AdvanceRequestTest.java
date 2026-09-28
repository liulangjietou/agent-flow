package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 借款围绕本人账户、法人日期、精确金额、冻结历史与批准隔离验证。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRequestTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");

    @Test void draftCannotApproveAndContentRejectsMissingOrNonPositiveTerms() {
        var request = request();
        fails("ADVANCE_NOT_SUBMITTED", () -> request.approve(1, 1, 2, "manager", NOW));
        fails("INVALID_ADVANCE_REQUEST", () -> content("0", "CNY", DATE));
        fails("INVALID_MONEY", () -> content("-0.01", "CNY", DATE));
        fails("INVALID_ADVANCE_REQUEST", () -> new AdvanceRequestContent(ENTITY, "借款", " ", money("100", "CNY"), DATE));
        assertThat(request.approval()).isNull(); assertThat(request.rounds()).isEmpty();
    }

    @Test void freezeKeepsActualAccountAndApprovalSealsTermsWithoutCreatingPaidBalance() {
        var request = request(); freeze(request);
        assertThat(request.currentRound().account()).isEqualTo(account("alice", ENTITY, NOW.plusSeconds(100)).snapshot());
        assertThat(request.approval()).isNull();
        request.approve(2, 1, 5, "manager", NOW.plusSeconds(10));
        assertThat(request.approval().applicationVersion()).isEqualTo(5); assertThat(request.version()).isEqualTo(3);
        fails("ADVANCE_ALREADY_APPROVED", () -> request.revise(3, content("200", "CNY", DATE)));
        fails("ADVANCE_ALREADY_APPROVED", () -> request.approve(3, 1, 6, "manager", NOW.plusSeconds(11)));
        assertThat(AdvanceRequest.restore(request.state()).state()).isEqualTo(request.state());
        assertThat(request.currentRound().toString()).doesNotContain("private-account", "客户出差", "1234");
    }

    @Test void changedApplicantLegalEntityOrExpiredAccountCannotFreezePartialState() {
        var request = request();
        for (var account : List.of(account("bob", ENTITY, NOW.plusSeconds(100)), account("alice", UUID.randomUUID(), NOW.plusSeconds(100)))) {
            fails("INVALID_ADVANCE_REQUEST_ROUND", () -> request.freeze(1, 1, catalog("Asia/Shanghai"), account, initiator(), NOW));
        }
        fails("ADVANCE_ACCOUNT_EXPIRED", () -> request.freeze(1, 1, catalog("Asia/Shanghai"), account("alice", ENTITY, NOW), initiator(), NOW));
        var c = catalog("Asia/Shanghai");
        var stale = new FinanceCatalog("alice", c.sourceVersion(), NOW, c.legalEntities(), c.categories(), c.costCenters(), c.projects(), c.cities());
        fails("ADVANCE_CATALOG_CHANGED", () -> request.freeze(1, 1, stale, account("alice", ENTITY, NOW.plusSeconds(1)), initiator(), NOW));
        assertThat(request.rounds()).isEmpty(); assertThat(request.version()).isEqualTo(1);
    }

    @Test void borrowingOnlyUsesBaseCurrencyWithoutInventedExchangeOrRepaymentExtension() {
        var request = request(); request.revise(1, content("100", "USD", DATE));
        fails("ADVANCE_BASE_CURRENCY_REQUIRED", () -> request.freeze(2, 1, catalog("Asia/Shanghai"), account("alice", ENTITY, NOW.plusSeconds(60)), initiator(), NOW));
        request.revise(2, content("100", "CNY", DATE.minusDays(1)));
        fails("ADVANCE_REPAYMENT_DATE_PASSED", () -> request.freeze(3, 1, catalog("Asia/Shanghai"), account("alice", ENTITY, NOW.plusSeconds(60)), initiator(), NOW));
        request.freeze(3, 1, catalog("America/New_York"), account("alice", ENTITY, NOW.plusSeconds(60)), initiator(), NOW);
        assertThat(request.currentRound().content().dueOn()).isEqualTo(DATE.minusDays(1));
    }

    @Test void revisedAndResubmittedRoundsKeepOriginalAmountAndAccount() {
        var request = request(); freeze(request); var old = request.currentRound();
        request.revise(2, content("50", "CNY", DATE.plusDays(1)));
        fails("INVALID_ADVANCE_REQUEST", () -> request.approve(3, 1, 6, "manager", NOW.plusSeconds(1)));
        var changed = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(ENTITY, "alice", "new-reference", "****5678", "b".repeat(64), "v2"), NOW.plusSeconds(100));
        request.freeze(3, 2, catalog("Asia/Shanghai"), changed, initiator(), NOW.plusSeconds(1));
        fails("INVALID_ADVANCE_REQUEST", () -> request.approve(4, 1, 8, "manager", NOW.plusSeconds(2)));
        request.approve(4, 2, 8, "manager", NOW.plusSeconds(2));
        assertThat(request.rounds().get(0)).isEqualTo(old); assertThat(old.content().amount()).isEqualTo(money("100", "CNY"));
        assertThat(request.currentRound().account()).isEqualTo(changed.snapshot());
        assertThat(request.currentRound().content().amount()).isEqualTo(money("50", "CNY"));
    }

    @Test void versionsAndAppointmentMustMatchBeforeFreezingOrApproval() {
        var request = request();
        fails("CONCURRENCY_CONFLICT", () -> request.revise(2, request.content()));
        var own = initiator();
        var other = new InitiatorContext(own.appointmentId(), own.personId(), "bob", own.directoryRevision(), ENTITY, "法人", own.departmentId(), "部门", own.positionId(), "岗位");
        fails("ADVANCE_INITIATOR_MISMATCH", () -> request.freeze(1, 1, catalog("Asia/Shanghai"), account("alice", ENTITY, NOW.plusSeconds(60)), other, NOW));
        freeze(request);
        fails("INVALID_ADVANCE_REQUEST", () -> request.approve(2, 1, 6, "manager", NOW.minusSeconds(1)));
        assertThat(request.approval()).isNull();
    }

    @Test void restoreRejectsTamperedHistoryContentApplicantAndApprovalBinding() {
        var request = request(); freeze(request); var state = request.state();
        fails("INVALID_ADVANCE_REQUEST", () -> AdvanceRequest.restore(new AdvanceRequest.State(state.id(), state.tenantId(), state.applicationId(), "bob", state.content(), state.rounds(), null, state.version())));
        fails("INVALID_ADVANCE_REQUEST", () -> AdvanceRequest.restore(new AdvanceRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), content("200", "CNY", DATE), state.rounds(), null, state.version())));
        fails("INVALID_ADVANCE_REQUEST", () -> AdvanceRequest.restore(new AdvanceRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), List.of(state.rounds().get(0), state.rounds().get(0)), null, 3)));
        fails("INVALID_ADVANCE_REQUEST", () -> AdvanceRequest.restore(new AdvanceRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), state.rounds(), new AdvanceRequest.Approval(2, 6, "manager", NOW), 3)));
        fails("INVALID_ADVANCE_REQUEST", () -> AdvanceRequest.restore(new AdvanceRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), state.rounds(), new AdvanceRequest.Approval(1, 6, "manager", NOW), 4)));
    }

    private static AdvanceRequest request() { return AdvanceRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content("100", "CNY", DATE)); }
    private static AdvanceRequestContent content(String value, String currency, LocalDate date) { return new AdvanceRequestContent(ENTITY, "出差借款", "客户出差", money(value, currency), date); }
    private static Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
    private static EmployeeAccountPort.Account account(String employee, UUID entity, Instant until) { return new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(entity, employee, "private-account", "****1234", "a".repeat(64), "v1"), until); }
    private static FinanceCatalog catalog(String zone) { return new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", zone)), List.of(), List.of(), List.of(), List.of()); }
    private static InitiatorContext initiator() { return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"); }
    private static void freeze(AdvanceRequest request) { request.freeze(1, 1, catalog("Asia/Shanghai"), account("alice", ENTITY, NOW.plusSeconds(100)), initiator(), NOW); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
