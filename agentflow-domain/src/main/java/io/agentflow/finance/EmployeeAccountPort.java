package io.agentflow.finance;

import java.time.Instant;
import java.util.UUID;

/**
 * 员工本人收款主数据只读端口，不能接受客户端完整银行卡号。
 * @author owlzhangfq@gmail.com
 */
public interface EmployeeAccountPort {
    /** 账户必须属于本次法人及真实申请人。 */
    FinanceResult<Account> primaryAccount(String tenantId, String employeeId, UUID legalEntityId);

    /**
     * 账户可用性带有效期，付款前需要重新核对绑定摘要。
     * @author owlzhangfq@gmail.com
     */
    record Account(EmployeeAccountSnapshot snapshot, Instant validUntil) {
        /** 外部账户引用必须完整且有明确时效。 */
        public Account { if (snapshot == null || validUntil == null) throw new io.agentflow.common.DomainException("INVALID_EMPLOYEE_ACCOUNT", "An account snapshot with validity is required"); }
    }
}
