package io.agentflow.procurement;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批状态与应付占用之间的跨聚合规则；提交、重提、拒绝及作废共享原申请事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcurementPayableReservations {
    private final JdbcProcurementPayableReservationRepository reservations;

    /** 请求锁由业务入口先取得，唯一约束再防止其他申请并发占用同一应付。 */
    public ProcurementPayableReservations(JdbcProcurementPayableReservationRepository reservations) { this.reservations = reservations; }

    /** 刚冻结的新轮次替换旧占用；新占用失败会与旧释放一起回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Application application, ProcurementPaymentRequest request, String actor, Instant now) {
        requireBinding(application, request.tenantId(), request.id(), request.applicationId(), request.employeeId());
        if (!application.editable() || application.nextSubmissionRound() != request.currentRound().roundNo() || !request.employeeId().equals(actor)) throw mismatch();
        var next = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now);
        var old = reservations.active(request.tenantId(), request.id()).orElse(null);
        if (old != null) {
            if (old.source().equals(next.source())) return;
            if (old.source().round().roundNo() >= next.source().round().roundNo()) throw mismatch();
            reservations.release(old.release(ProcurementPayableReservation.ReleaseReason.RESUBMITTED, actor, now));
        }
        reservations.create(next);
    }

    /** 只有实际拒绝或作废才释放；退回、撤回和批准均保留原占用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseStopped(Application application, String actor, Instant now) {
        if (application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT) return;
        var reason = switch (application.status()) {
            case REJECTED -> ProcurementPayableReservation.ReleaseReason.REJECTED;
            case CANCELLED -> ProcurementPayableReservation.ReleaseReason.CANCELLED;
            default -> throw mismatch();
        };
        var old = reservations.active(application.tenantId(), application.businessReference().id()).orElse(null);
        if (old == null) return;
        var source = old.source(); requireBinding(application, source.tenantId(), source.requestId(), source.applicationId(), source.employeeId());
        reservations.release(old.release(reason, actor, now));
    }

    private static void requireBinding(Application application, String tenant, UUID request, UUID applicationId, String employee) {
        if (!application.tenantId().equals(tenant) || !application.id().equals(applicationId) || !application.createdBy().equals(employee)
                || application.businessReference() == null || application.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT
                || !application.businessReference().id().equals(request)) throw mismatch();
    }
    private static DomainException mismatch() { return new DomainException("PROCUREMENT_RESERVATION_CONTEXT_CHANGED", "Payable reservation must match the actual procurement application lifecycle"); }
}
