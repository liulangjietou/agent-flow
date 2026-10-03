package io.agentflow.procurement;

import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static io.agentflow.procurement.ProcurementPayableTest.*;

/**
 * 供应商资金测试使用真实冻结、占用、批准过程，避免直接拼装不存在的批准状态。
 * @author owlzhangfq@gmail.com
 */
final class SupplierPaymentTestData {
    static final Instant APPROVED_AT = NOW.plusSeconds(1);
    static final Instant AUTHORIZED_AT = NOW.plusSeconds(2);
    static final UUID AUTHORIZATION_ID = UUID.fromString("ded74f33-5055-4097-8d73-f52d49f78703");

    private SupplierPaymentTestData() { }

    static ProcurementPaymentRequest submitted() {
        var content = new ProcurementPaymentContent(ENTITY, "采购付款", "已验收货物付款", "supplier-1", "payable-1", money("70"));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "catalog-v1", NOW.plusSeconds(600),
                List.of(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "entity-v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        request.freeze(1, 1, catalog, "f".repeat(64), payable(List.of(line(1, 1, "100", "10")), "100", "0"), initiator, NOW);
        return request;
    }

    static ApprovedProcurementPayment approved() {
        var request = submitted(); var reservation = ProcurementPayableReservation.hold(UUID.randomUUID(), request, NOW);
        request.approve(2, 1, 8, "manager", APPROVED_AT);
        return ApprovedProcurementPayment.from(request, reservation);
    }

    static ProcurementPayablePort.Payable current(ApprovedProcurementPayment source, String paid, Instant observedAt) {
        var original = source.reservation().source().round().payable();
        return new ProcurementPayablePort.Payable(original.request(), "ap-v2", observedAt, observedAt.plusSeconds(600), original.supplierName(), original.account(),
                original.contractReference(), original.orderReference(), original.matchingReference(), original.accrualVoucherReference(), original.budgetRecognitionReference(),
                original.dueOn(), original.gross(), money(paid), original.lines());
    }

    static SupplierPaymentAuthorization authorization() {
        var source = approved();
        return new SupplierPaymentAuthorization(AUTHORIZATION_ID, source, current(source, "30", AUTHORIZED_AT), "finance", AUTHORIZED_AT, AUTHORIZED_AT.plusSeconds(86400));
    }
}
