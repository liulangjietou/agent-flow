package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.*;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 独立授权只承接实际批准和原应付，不将审批、余额读取或管理员身份当作付款许可。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentAuthorizationTest {
    @Test void draftUnapprovedReleasedAndForeignReservationsCannotAuthorizePayment() {
        var request = submitted(); var hold = ProcurementPayableReservation.hold(UUID.randomUUID(), request, NOW);
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> ApprovedProcurementPayment.from(request, hold));
        request.approve(2, 1, 8, "manager", APPROVED_AT);
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> ApprovedProcurementPayment.from(request, hold.release(ProcurementPayableReservation.ReleaseReason.CANCELLED, "alice", APPROVED_AT)));
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> ApprovedProcurementPayment.from(request, approved().reservation()));
        var source = hold.source();
        var foreign = new ProcurementPayableReservation(hold.id(), new ProcurementPayableReservation.Source("other-tenant", source.requestId(), source.applicationId(), source.employeeId(), source.requestVersion(), source.round()), 1, hold.heldAt(), null);
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> ApprovedProcurementPayment.from(request, foreign));
        assertThat(ApprovedProcurementPayment.from(request, hold).approvedRequestVersion()).isEqualTo(3);
    }

    @Test void approvalMustFollowTheHeldSubmittedVersionAndTimestamp() {
        var source = approved();
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> new ApprovedProcurementPayment(source.reservation(), source.approval(), 4));
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> new ApprovedProcurementPayment(source.reservation(), new ProcurementPaymentRequest.Approval(2, 8, "manager", APPROVED_AT), 3));
        fails("PROCUREMENT_PAYMENT_SOURCE_CHANGED", () -> new ApprovedProcurementPayment(source.reservation(), new ProcurementPaymentRequest.Approval(1, 8, "manager", NOW.minusNanos(1)), 3));
    }

    @Test void newerBalanceMayReduceOnlyTheUnpaidRemainderButNeverIncreaseAuthorizedAmount() {
        var source = approved();
        source.requireCurrentPayable(current(source, "30", AUTHORIZED_AT), AUTHORIZED_AT);
        assertThat(source.amount()).isEqualTo(money("70"));
        source.requireCurrentPayable(current(source, "0", AUTHORIZED_AT), AUTHORIZED_AT);
        assertThat(source.amount()).isEqualTo(money("70"));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> source.requireCurrentPayable(current(source, "30.01", AUTHORIZED_AT), AUTHORIZED_AT));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> source.requireCurrentPayable(current(source, "100", AUTHORIZED_AT), AUTHORIZED_AT));
    }

    @Test void changedSupplierAccountMatchingOrAccountingFactsInvalidateTheApprovedTerms() {
        var source = approved(); var original = current(source, "30", AUTHORIZED_AT);
        var account = new SupplierAccountSnapshot(ENTITY, "supplier-1", "new-account", "****5678", "b".repeat(64), "v2");
        var changedLine = new ProcurementPayablePort.MatchedLine(1, 1, "new-receipt", original.lines().get(0).invoice(), 1, "b".repeat(64), "verified-2", "件",
                original.lines().get(0).orderedQuantity(), original.lines().get(0).acceptedQuantity(), original.lines().get(0).invoicedQuantity(), money("100"), money("100"), money("100"), money("0"));
        for (int variation = 0; variation < 10; variation++) {
            var changed = new ProcurementPayablePort.Payable(original.request(), "ap-v3", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(600), variation == 0 ? "另一供应商名称" : original.supplierName(),
                    variation == 1 ? account : original.account(), variation == 2 ? "other-contract" : original.contractReference(), variation == 3 ? "other-order" : original.orderReference(),
                    variation == 4 ? "other-match" : original.matchingReference(), variation == 5 ? "other-voucher" : original.accrualVoucherReference(),
                    variation == 6 ? "other-budget" : original.budgetRecognitionReference(), variation == 7 ? original.dueOn().plusDays(1) : original.dueOn(),
                    variation == 8 ? money("90") : original.gross(), original.settled(), variation == 8 ? List.of(line(1, 1, "90", "9")) : variation == 9 ? List.of(changedLine) : original.lines());
            fails("PROCUREMENT_PAYABLE_CHANGED", () -> source.requireCurrentPayable(changed, AUTHORIZED_AT));
        }
    }

    @Test void independentPeopleAndBoundedTimeAreRequiredForFinanceAndCashier() {
        var original = authorization();
        for (String person : List.of("alice", "", "finance\n", " finance")) {
            fails("INVALID_SUPPLIER_PAYMENT_AUTHORIZATION", () -> new SupplierPaymentAuthorization(AUTHORIZATION_ID, original.source(), original.payable(), person, AUTHORIZED_AT, original.expiresAt()));
        }
        for (var expiry : List.of(AUTHORIZED_AT, AUTHORIZED_AT.minusNanos(1), AUTHORIZED_AT.plusSeconds(86400).plusNanos(1))) {
            fails("INVALID_SUPPLIER_PAYMENT_AUTHORIZATION", () -> new SupplierPaymentAuthorization(AUTHORIZATION_ID, original.source(), original.payable(), "finance", AUTHORIZED_AT, expiry));
        }
        for (String person : List.of("alice", "finance", "", "cashier\n", " cashier")) {
            fails("PAYMENT_SEPARATION_REQUIRED", () -> original.requireCashier(person, AUTHORIZED_AT));
        }
        original.requireCashier("cashier", AUTHORIZED_AT);
        original.requireExecutionAt(original.expiresAt().minusNanos(1));
        fails("SUPPLIER_PAYMENT_AUTHORIZATION_EXPIRED", () -> original.requireExecutionAt(original.expiresAt()));
        fails("SUPPLIER_PAYMENT_AUTHORIZATION_EXPIRED", () -> original.requireExecutionAt(AUTHORIZED_AT.minusNanos(1)));
    }

    @Test void longAuthorizationCannotExtendFreshnessOrAcceptEvidenceFromTheFuture() {
        var authorization = authorization();
        authorization.requireReservationAt(AUTHORIZED_AT.plusSeconds(300).minusNanos(1));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> authorization.requireReservationAt(AUTHORIZED_AT.plusSeconds(300)));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> authorization.source().requireCurrentPayable(current(authorization.source(), "0", AUTHORIZED_AT.plusNanos(1)), AUTHORIZED_AT));
        fails("PROCUREMENT_PAYABLE_CHANGED", () -> authorization.source().requireCurrentPayable(current(authorization.source(), "0", NOW), NOW));
        assertThat(authorization.toString()).isEqualTo("SupplierPaymentAuthorization[id=" + AUTHORIZATION_ID + "]");
        assertThat(authorization.source().toString()).isEqualTo("ApprovedProcurementPayment[requestId=" + authorization.source().reservation().source().requestId() + "]");
    }

    private static void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
