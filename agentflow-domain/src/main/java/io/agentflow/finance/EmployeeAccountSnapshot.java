package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;

/**
 * 外部主数据确认的员工本人收款账户快照；本系统不保存完整卡号。
 * @author owlzhangfq@gmail.com
 */
public record EmployeeAccountSnapshot(UUID legalEntityId, String employeeId, String accountReference,
                                      String maskedAccount, String accountDigest, String sourceVersion) {
    /** 账户引用供可信支付端口使用，摘要绑定付款授权，掩码仅用于核对。 */
    public EmployeeAccountSnapshot {
        if (legalEntityId == null || invalidText(employeeId) || invalidText(accountReference)
                || invalidText(maskedAccount) || !isMask(maskedAccount) || invalidText(sourceVersion) || accountDigest == null
                || !accountDigest.matches("[a-f0-9]{64}")) {
            throw new DomainException("INVALID_EMPLOYEE_ACCOUNT", "A versioned employee account reference and digest are required");
        }
    }

    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }

    private static boolean isMask(String value) {
        // 展示字段只保留数字片段和掩码，最多露出首尾各四位；账户名称由主数据另行展示。
        return value.matches("[0-9*•xX -]+") && value.matches(".*(?:\\*{2,}|•{2,}|[xX]{2,}).*")
                && !value.matches(".*[0-9]{5,}.*") && value.chars().filter(Character::isDigit).count() <= 8;
    }
}
