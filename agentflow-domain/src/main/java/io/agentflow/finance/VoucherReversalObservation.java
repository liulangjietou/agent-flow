package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 原冲销命令的权威执行状态；已过账必须同时提供原凭证冲销事实与完整反向凭证。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalObservation(UUID operationId, String commandDigest, Status status, long revision, Instant observedAt,
                                         String acceptanceReference, VoucherReversalPort.Receipt posting, Rejection rejection) {
    /** 查无必须明确且没有受理事实，失败只能使用绑定原命令的封闭原因。 */
    public VoucherReversalObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null || observedAt == null || revision < 0) throw invalid();
        if (status == Status.NOT_FOUND) {
            if (revision != 0 || acceptanceReference != null || posting != null || rejection != null) throw invalid();
        } else {
            if (revision == 0 || StringUtils.isBlank(acceptanceReference) || acceptanceReference.length() > 128
                    || (status == Status.POSTED) != (posting != null) || (status == Status.FAILED) != (rejection != null)) throw invalid();
            if (posting != null && (posting.status() != VoucherReversalPort.Status.VERIFIED || !posting.observedAt().equals(observedAt))) throw invalid();
        }
    }
    /** 新发送不接受查无，原编号查询即使授权到期也必须继续核对真实执行事实。 */
    public boolean matches(VoucherReversalCommand command, boolean query, Instant now) {
        return command != null && operationId.equals(command.id()) && commandDigest.equals(command.digest())
                && (query || status != Status.NOT_FOUND) && !observedAt.isBefore(command.createdAt()) && !observedAt.isAfter(now)
                && (posting == null || command.matchesPosting(posting, observedAt));
    }
    @Override public String toString() { return "VoucherReversalObservation[operationId=" + operationId + ", status=" + status + ", revision=" + revision + "]"; }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_OBSERVATION", "Reversal observation must bind its original command and preserve complete posted evidence"); }
    /**
     * 单独的反向凭证目前只支持查询其原执行状态，不能由新命令覆盖旧结果。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_FOUND, PENDING, POSTED, FAILED }
    /**
     * ERP 在原子过账前拒绝的明确原因；传输异常不能伪装成此类失败。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, ORIGINAL_NOT_POSTED, ORIGINAL_CHANGED, REVERSAL_ALREADY_EXISTS, LEGAL_ENTITY_UNAVAILABLE, ACCOUNT_UNAVAILABLE, AUTHORIZATION_REJECTED }
}
