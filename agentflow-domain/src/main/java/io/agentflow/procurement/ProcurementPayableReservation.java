package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 同一供应商应付的一张在途申请占用；只保护本地办理顺序，不代表外部已预留或已付款。
 * @author owlzhangfq@gmail.com
 */
public record ProcurementPayableReservation(UUID id, Source source, long version, Instant heldAt, Release release, Settlement settlement) {
    /** 释放或结算只追加第二版，二者互斥；原申请、金额和三单依据不能改写。 */
    public ProcurementPayableReservation {
        if (id == null || source == null || heldAt == null || heldAt.isBefore(source.round().submittedAt())
                || (release == null && settlement == null ? version != 1 : version != 2) || release != null && settlement != null
                || release != null && release.releasedAt().isBefore(heldAt) || settlement != null && settlement.completedAt().isBefore(heldAt)) throw invalid();
    }

    /** 只接收刚冻结、尚未批准的申请轮次，不能对旧已批准版本另行占用。 */
    public static ProcurementPayableReservation hold(UUID id, ProcurementPaymentRequest request, Instant now) {
        if (request.approval() != null || request.version() != request.currentRound().submittedRequestVersion() + 1) throw invalid();
        return new ProcurementPayableReservation(id, new Source(request.tenantId(), request.id(), request.applicationId(), request.employeeId(), request.version(), request.currentRound()), 1, now, null, null);
    }

    /** 应用服务核对原申请确已重提、拒绝或作废后，领域追加单次释放事实。 */
    public ProcurementPayableReservation release(ReleaseReason reason, String actor, Instant now) {
        if (!held()) throw new DomainException("PROCUREMENT_RESERVATION_RELEASED", "Original payable reservation is already released");
        return new ProcurementPayableReservation(id, source, 2, heldAt, new Release(reason, actor, now), null);
    }
    /** 只有同一原占用对应的完整 ERP 结算才能完成；重复调用不能重新消耗或重开原申请。 */
    public ProcurementPayableReservation settle(SupplierPayableSettlementOperation operation, Instant now) {
        if (!held() || operation == null || !operation.settled() || now == null || now.isBefore(operation.updatedAt())
                || !operation.command().payment().holdCommand().authorization().source().reservation().equals(this)) {
            throw new DomainException("PROCUREMENT_SETTLEMENT_SOURCE_CHANGED", "Completed original ERP settlement must match the held local payable reservation");
        }
        var command = operation.command();
        return new ProcurementPayableReservation(id, source, 2, heldAt, null, new Settlement(command.id(), operation.version(), command.payment().id(), now));
    }
    public boolean held() { return release == null && settlement == null; }

    /**
     * 结算完成引用实际 ERP 终态修订，保留原银行身份，不把已付事实伪装为取消释放。
     * @author owlzhangfq@gmail.com
     */
    public record Settlement(UUID operationId, long operationVersion, UUID paymentId, Instant completedAt) {
        /** 跨聚合持久编排还需核对实际结算修订及当前银行无争议状态。 */
        public Settlement {
            if (operationId == null || operationVersion < 1 || paymentId == null || completedAt == null) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_PROCUREMENT_RESERVATION", "Payable reservation must preserve a submitted request and a single explicit release or confirmed settlement"); }

    /**
     * 关联真实申请版本和冻结轮次，原应付币种、金额和身份从轮次取得。
     * @author owlzhangfq@gmail.com
     */
    public record Source(String tenantId, UUID requestId, UUID applicationId, String employeeId, long requestVersion, ProcurementPaymentRound round) {
        /** 不能将任意金额附到其他申请或已修改草稿上。 */
        public Source {
            if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || requestId == null || applicationId == null
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || round == null
                    || !employeeId.equals(round.submittedBy()) || requestVersion != round.submittedRequestVersion() + 1) throw invalid();
        }
    }

    /**
     * 目前仅允许未付款申请的生命周期释放，批准后不会因普通查询自动释放。
     * @author owlzhangfq@gmail.com
     */
    public enum ReleaseReason { RESUBMITTED, REJECTED, CANCELLED }

    /**
     * 原占用的具名释放事实，与后继占用或审批终止同事务保存。
     * @author owlzhangfq@gmail.com
     */
    public record Release(ReleaseReason reason, String releasedBy, Instant releasedAt) {
        /** 时间和处理人不可省略，也不能用自由文本伪造结束状态。 */
        public Release {
            Objects.requireNonNull(reason); Objects.requireNonNull(releasedAt);
            if (StringUtils.isBlank(releasedBy) || releasedBy.length() > 128) throw invalid();
        }
    }
}
