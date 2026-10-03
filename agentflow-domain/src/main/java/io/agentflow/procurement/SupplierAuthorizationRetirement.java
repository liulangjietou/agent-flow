package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务明确结束未形成预留的原授权，绑定已保存的原操作修订，不能根据到期或查无直接另起付款。
 * @author owlzhangfq@gmail.com
 */
public record SupplierAuthorizationRetirement(UUID authorizationId, long operationVersion, SupplierPayableHoldOperation.RetirementBasis basis,
                                               String retiredBy, Instant retiredAt) {
    /** 结束必须保留版本、具名人员和时间，不接受自由文本伪造原因。 */
    public SupplierAuthorizationRetirement {
        if (authorizationId == null || operationVersion < 1 || basis == null || StringUtils.isBlank(retiredBy) || retiredBy.length() > 128
                || !retiredBy.equals(retiredBy.trim()) || retiredBy.chars().anyMatch(Character::isISOControl) || retiredAt == null) throw invalid();
    }

    /** 必须先停止可执行队列，再把安全结束依据与授权解除独占一起落库。 */
    public static SupplierAuthorizationRetirement from(SupplierPayableHoldOperation operation, String finance, Instant now) {
        if (operation == null || operation.retirementBasis() == null || operation.status() == SupplierPayableHoldOperation.Status.QUEUED) throw unsafe();
        var value = new SupplierAuthorizationRetirement(operation.command().id(), operation.version(), operation.retirementBasis(), finance, now);
        if (!value.matches(operation)) throw unsafe(); return value;
    }

    /** 读取或保存结束记录时，仍对照同一原授权、原修订以及独立财务身份。 */
    public boolean matches(SupplierPayableHoldOperation operation) {
        return operation != null && authorizationId.equals(operation.command().id()) && operationVersion == operation.version()
                && basis == operation.retirementBasis() && operation.status() != SupplierPayableHoldOperation.Status.QUEUED
                && !retiredAt.isBefore(operation.updatedAt()) && !retiredBy.equals(operation.command().authorization().source().reservation().source().employeeId());
    }

    /** 默认日志不展开财务处理人或资金来源。 */
    @Override public String toString() { return "SupplierAuthorizationRetirement[authorizationId=" + authorizationId + ", operationVersion=" + operationVersion + "]"; }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_AUTHORIZATION_RETIREMENT", "Supplier authorization retirement requires a named decision and original operation revision"); }
    private static DomainException unsafe() { return new DomainException("SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE", "Stopped original payable hold must prove no external reservation before replacement"); }
}
