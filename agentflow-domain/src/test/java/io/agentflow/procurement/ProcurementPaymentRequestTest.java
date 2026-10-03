package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 付款申请按本轮已匹配应付冻结，历史和批准保持，失败不留下部分新轮次。
 * @author owlzhangfq@gmail.com
 */
class ProcurementPaymentRequestTest {
    @Test void approvalSealsSupplierTermsWithoutProducingPaymentOrReusingEmployeeAccounts() {
        var request = draft("70"); freeze(request, 1, 1, source("30"), NOW);
        assertThat(request.approval()).isNull(); assertThat(request.currentRound().payable().account()).isEqualTo(account());
        request.approve(2, 1, 8, "manager", NOW.plusSeconds(5));
        assertThat(request.approval().applicationVersion()).isEqualTo(8); assertThat(request.version()).isEqualTo(3);
        fails("PROCUREMENT_ALREADY_APPROVED", () -> request.revise(3, content("60")));
        assertThat(ProcurementPaymentRequest.restore(request.state()).state()).isEqualTo(request.state());
        assertThat(request.currentRound().toString()).doesNotContain("supplier-account", "contract-1");
    }

    @Test void tooLargeOrExpiredPayableDoesNotAppendRoundAndZeroOutstandingCannotFundARequest() {
        var request = draft("70"); var before = request.state();
        fails("PROCUREMENT_AMOUNT_EXCEEDS_PAYABLE", () -> freeze(request, 1, 1, source("30.01"), NOW));
        fails("PROCUREMENT_AMOUNT_EXCEEDS_PAYABLE", () -> freeze(request, 1, 1, source("100"), NOW));
        fails("INVALID_PROCUREMENT_PAYMENT_ROUND", () -> freeze(request, 1, 1, source("0"), NOW.plusSeconds(300)));
        assertThat(request.state()).isEqualTo(before);
        freeze(request, 1, 1, source("30"), NOW);
        assertThat(request.currentRound().content().amount()).isEqualTo(money("70"));
    }

    @Test void originalSupplierAndTargetAreFrozenAgainForEachReturnedRound() {
        var request = draft("100"); freeze(request, 1, 1, source("0"), NOW); var first = request.currentRound();
        request.revise(2, content("60"));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> request.approve(3, 1, 9, "manager", NOW.plusSeconds(1)));
        freeze(request, 3, 2, source("40"), NOW.plusSeconds(2)); request.approve(4, 2, 11, "manager", NOW.plusSeconds(3));
        assertThat(request.rounds().get(0)).isEqualTo(first); assertThat(first.content().amount()).isEqualTo(money("100"));
        assertThat(request.currentRound().content().amount()).isEqualTo(money("60"));
        assertThat(ProcurementPaymentRequest.restore(request.state()).state()).isEqualTo(request.state());
    }

    @Test void sourceApplicantSupplierEntityAndAppointmentMustMatch() {
        var request = draft("100"); var own = initiator();
        var other = new InitiatorContext(own.appointmentId(), own.personId(), "bob", 1, ENTITY, "法人", own.departmentId(), "部门", own.positionId(), "岗位");
        fails("PROCUREMENT_INITIATOR_MISMATCH", () -> request.freeze(1, 1, catalog(), "a".repeat(64), source("0"), other, NOW));
        request.revise(1, new ProcurementPaymentContent(ENTITY, "采购付款", "已验收货物付款", "supplier-2", "payable-1", money("100")));
        fails("INVALID_PROCUREMENT_PAYMENT_ROUND", () -> freeze(request, 2, 1, source("0"), NOW));
        assertThat(request.rounds()).isEmpty();
        var correct = draft("100");
        fails("INVALID_PROCUREMENT_PAYMENT_ROUND", () -> correct.freeze(1, 1, catalog(), "arbitrary-url", source("0"), own, NOW));
        assertThat(correct.rounds()).isEmpty();
    }

    @Test void changedCurrencyOrExpiredCatalogNeverInventsAnExchangeRate() {
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice",
                new ProcurementPaymentContent(ENTITY, "采购付款", "付款", "supplier-1", "payable-1", new io.agentflow.finance.Money(new java.math.BigDecimal("100"), "USD")));
        fails("PROCUREMENT_BASE_CURRENCY_REQUIRED", () -> freeze(request, 1, 1, source("0"), NOW));
        var catalog = catalog(); var stale = new FinanceCatalog("alice", "v1", NOW, catalog.legalEntities(), List.of(), List.of(), List.of(), List.of());
        var correct = draft("100");
        fails("PROCUREMENT_CATALOG_CHANGED", () -> correct.freeze(1, 1, stale, "a".repeat(64), source("0"), initiator(), NOW));
        assertThat(correct.state().version()).isEqualTo(1);
    }

    @Test void staleVersionsDraftApprovalAndTamperedPersistedRoundsAreRejected() {
        var request = draft("100");
        fails("PROCUREMENT_NOT_SUBMITTED", () -> request.approve(1, 1, 5, "manager", NOW));
        fails("CONCURRENCY_CONFLICT", () -> request.revise(2, content("90")));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> draft("0"));
        freeze(request, 1, 1, source("0"), NOW); var state = request.state();
        fails("INVALID_PROCUREMENT_PAYMENT", () -> request.approve(2, 2, 5, "manager", NOW));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> ProcurementPaymentRequest.restore(new ProcurementPaymentRequest.State(state.id(), state.tenantId(), state.applicationId(), "bob", state.content(), state.rounds(), null, state.version())));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> ProcurementPaymentRequest.restore(new ProcurementPaymentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), content("90"), state.rounds(), null, state.version())));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> ProcurementPaymentRequest.restore(new ProcurementPaymentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), List.of(state.rounds().get(0), state.rounds().get(0)), null, 4)));
        fails("INVALID_PROCUREMENT_PAYMENT", () -> ProcurementPaymentRequest.restore(new ProcurementPaymentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), state.rounds(), new ProcurementPaymentRequest.Approval(2, 5, "manager", NOW), 3)));
        assertThat(request.state()).isEqualTo(state);
    }

    private static ProcurementPaymentContent content(String amount) { return new ProcurementPaymentContent(ENTITY, "采购付款", "已验收货物付款", "supplier-1", "payable-1", money(amount)); }
    private static ProcurementPaymentRequest draft(String amount) { return ProcurementPaymentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content(amount)); }
    private static ProcurementPayablePort.Payable source(String paid) { return payable(List.of(line(1, 1, "100", "10")), "100", paid); }
    private static FinanceCatalog catalog() { return new FinanceCatalog("alice", "v1", NOW.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of()); }
    private static InitiatorContext initiator() { return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"); }
    private static void freeze(ProcurementPaymentRequest request, long version, int round, ProcurementPayablePort.Payable payable, Instant now) { request.freeze(version, round, catalog(), "a".repeat(64), payable, initiator(), now); }
    private static void fails(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
