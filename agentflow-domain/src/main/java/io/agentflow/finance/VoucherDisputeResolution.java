package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 人工核对凭据关联原凭证前后修订，不允许凭说明制造 ERP 事实。
 * @author owlzhangfq@gmail.com
 */
public record VoucherDisputeResolution(UUID id, String tenantId, UUID operationId, long disputedVersion, long resolvedVersion,
        VoucherObservation observation, String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 对账终态和五分钟时效必须来自持久化原操作查询。 */
    public VoucherDisputeResolution {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || operationId == null || disputedVersion < 1
                || resolvedVersion != Math.incrementExact(disputedVersion) || observation == null || !operationId.equals(observation.operationId())
                || observation.status() != VoucherObservation.Status.POSTED && observation.status() != VoucherObservation.Status.FAILED && observation.status() != VoucherObservation.Status.REVERSED
                || StringUtils.isBlank(resolvedBy) || resolvedBy.length() > 128 || resolvedAt == null || resolvedAt.isBefore(observation.observedAt())
                || !resolvedAt.isBefore(observation.observedAt().plus(VoucherOperation.DISPUTE_EVIDENCE_LIFETIME))
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw new DomainException("INVALID_VOUCHER_DISPUTE_RESOLUTION", "Voucher decision requires recent original evidence and a named reviewer");
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }
    /** 申请人与原出纳不能裁决，结果只对应同一命令的精确连续修订。 */
    public boolean matches(VoucherOperation before, VoucherOperation after) {
        var command = before.input().command();
        return before.status() == VoucherOperation.Status.RECONCILING && before.version() == disputedVersion && after.version() == resolvedVersion
                && tenantId.equals(command.tenantId()) && operationId.equals(command.id()) && before.input().equals(after.input())
                && observation.equals(before.conflictingObservation()) && observation.equals(after.observation()) && after.conflictingObservation() == null
                && after.status().name().equals(observation.status().name()) && after.updatedAt().equals(resolvedAt) && independent(command, resolvedBy);
    }
    /** 财务角色和当前任职由服务核验；原职责身份从不可变命令核验。 */
    public static boolean independent(VoucherCommand command, String actor) {
        return !actor.equals(command.employeeId()) && (command.payment() == null || !actor.equals(command.payment().command().authorization().executedBy()));
    }
    /** 日志只定位记录，不展开人工材料或原凭证。 */
    @Override public String toString() { return "VoucherDisputeResolution[id=" + id + ", operationId=" + operationId + ", resolvedVersion=" + resolvedVersion + "]"; }
}
