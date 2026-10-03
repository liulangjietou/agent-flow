package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 采购付款申请只填写原应付引用和本次正额付款意图，三单匹配和账户由财务系统提供。
 * @author owlzhangfq@gmail.com
 */
public record ProcurementPaymentContent(UUID legalEntityId, String title, String purpose, String supplierReference,
                                        String payableReference, Money amount) {
    /** 不允许以负额表示退款，也不接受修改原应付金额的隐式调整。 */
    public ProcurementPaymentContent {
        if (legalEntityId == null || invalid(title, 256) || invalid(purpose, 2000) || invalid(supplierReference, 128)
                || invalid(payableReference, 128) || amount == null || amount.value().signum() <= 0) {
            throw new DomainException("INVALID_PROCUREMENT_PAYMENT", "Procurement payment requires a legal entity, supplier, original payable and positive amount");
        }
    }

    /** 生成原应付读取范围，申请人由当前业务归属取得。 */
    public ProcurementPayablePort.Request payableRequest(String employeeId) {
        return new ProcurementPayablePort.Request(legalEntityId, employeeId, supplierReference, payableReference);
    }

    /** 对象未知字段直接拒绝，前端不能自报匹配通过或注入账户与原始已付金额。 */
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown procurement payment content field"); }

    private static boolean invalid(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
}
