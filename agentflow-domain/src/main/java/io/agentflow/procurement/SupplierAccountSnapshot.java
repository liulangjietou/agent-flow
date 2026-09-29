package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 供应商对公收款账户由可信主数据固定，不能借用员工身份或保存完整银行账号。
 * @author owlzhangfq@gmail.com
 */
public record SupplierAccountSnapshot(UUID legalEntityId, String supplierReference, String accountReference,
                                      String maskedAccount, String accountDigest, String sourceVersion) {
    /** 法人、供应商、账户引用与版本共同构成本次付款对象。 */
    public SupplierAccountSnapshot {
        if (legalEntityId == null || invalid(supplierReference) || invalid(accountReference) || invalid(sourceVersion)
                || invalid(maskedAccount) || !maskedAccount.matches("[0-9*•xX -]+")
                || !maskedAccount.matches(".*(?:\\*{2,}|•{2,}|[xX]{2,}).*") || maskedAccount.matches(".*[0-9]{5,}.*")
                || maskedAccount.chars().filter(Character::isDigit).count() > 8
                || accountDigest == null || !accountDigest.matches("[a-f0-9]{64}")) {
            throw new DomainException("INVALID_SUPPLIER_ACCOUNT", "Supplier account requires an exact legal entity, supplier and masked versioned account");
        }
    }

    private static boolean invalid(String value) {
        return StringUtils.isBlank(value) || value.length() > 128 || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }

    /** 日志不展开收款方或账户资料。 */
    @Override public String toString() { return "SupplierAccountSnapshot[redacted]"; }
}
