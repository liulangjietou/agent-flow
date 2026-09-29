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
public record ProcurementPayableReservation(UUID id, Source source, long version, Instant heldAt, Release release) {
    /** 释放只追加第二版，原申请、金额和三单依据不能改写。 */
    public ProcurementPayableReservation {
        if (id == null || source == null || heldAt == null || heldAt.isBefore(source.round().submittedAt())
                || release == null && version != 1 || release != null && (version != 2 || release.releasedAt().isBefore(heldAt))) throw invalid();
    }

    /** 只接收刚冻结、尚未批准的申请轮次，不能对旧已批准版本另行占用。 */
    public static ProcurementPayableReservation hold(UUID id, ProcurementPaymentRequest request, Instant now) {
        if (request.approval() != null || request.version() != request.currentRound().submittedRequestVersion() + 1) throw invalid();
        return new ProcurementPayableReservation(id, new Source(request.tenantId(), request.id(), request.applicationId(), request.employeeId(), request.version(), request.currentRound()), 1, now, null);
    }

    /** 应用服务核对原申请确已重提、拒绝或作废后，领域追加单次释放事实。 */
    public ProcurementPayableReservation release(ReleaseReason reason, String actor, Instant now) {
        if (!held()) throw new DomainException("PROCUREMENT_RESERVATION_RELEASED", "Original payable reservation is already released");
        return new ProcurementPayableReservation(id, source, 2, heldAt, new Release(reason, actor, now));
    }
    public boolean held() { return release == null; }

    private static DomainException invalid() { return new DomainException("INVALID_PROCUREMENT_RESERVATION", "Payable reservation must preserve a submitted request and a single explicit release"); }

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
