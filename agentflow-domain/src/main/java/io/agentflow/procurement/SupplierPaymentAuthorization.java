package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立财务人员对供应商付款的具名授权，固定已批准来源与授权时复核的原应付版本。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentAuthorization(UUID id, ApprovedProcurementPayment source, ProcurementPayablePort.Payable payable,
                                           String authorizedBy, Instant authorizedAt, Instant expiresAt) {
    public static final Duration MAX_VALIDITY = Duration.ofHours(24);

    /** 授权时必须已有新鲜原应付依据；此记录本身不会预留 ERP 余额或向银行发送指令。 */
    public SupplierPaymentAuthorization {
        if (id == null || source == null || StringUtils.isBlank(authorizedBy) || authorizedBy.length() > 128
                || !authorizedBy.equals(authorizedBy.trim()) || authorizedBy.chars().anyMatch(Character::isISOControl)
                || authorizedBy.equals(source.reservation().source().employeeId()) || authorizedAt == null || expiresAt == null
                || !expiresAt.isAfter(authorizedAt) || Duration.between(authorizedAt, expiresAt).compareTo(MAX_VALIDITY) > 0) {
            throw new DomainException("INVALID_SUPPLIER_PAYMENT_AUTHORIZATION", "Supplier payment requires a bounded independent financial authorization");
        }
        source.requireCurrentPayable(payable, authorizedAt);
    }

    /** 创建原应付预留时，固定版本的读取依据也必须有效，不能仅凭二十四小时授权重复利用旧余额。 */
    public void requireReservationAt(Instant now) {
        requireExecutionAt(now); source.requireCurrentPayable(payable, now);
    }

    /** 到期阻止新的资金执行，后续原命令查询和实际结算不依赖此新发送窗口。 */
    public void requireExecutionAt(Instant now) {
        if (now == null || now.isBefore(authorizedAt) || !now.isBefore(expiresAt)) {
            throw new DomainException("SUPPLIER_PAYMENT_AUTHORIZATION_EXPIRED", "Supplier payment authorization is outside its execution window");
        }
    }

    /** 资金办理与原申请人、财务授权人三方分离，不能以供应商不是员工为由省略内部岗位分离。 */
    public void requireCashier(String cashier, Instant now) {
        requireExecutionAt(now);
        if (StringUtils.isBlank(cashier) || cashier.length() > 128 || !cashier.equals(cashier.trim()) || cashier.chars().anyMatch(Character::isISOControl)
                || cashier.equals(authorizedBy) || cashier.equals(source.reservation().source().employeeId())) {
            throw new DomainException("PAYMENT_SEPARATION_REQUIRED", "Procurement applicant, financial authorizer and cashier must be different people");
        }
    }

    /** 授权对象只在受控持久事实中保存完整财务材料。 */
    @Override public String toString() { return "SupplierPaymentAuthorization[id=" + id + "]"; }
}
