package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 本轮借款约定与本人收款账户的不可变快照，账户引用仅供服务端结算使用。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRequestRound(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                  AdvanceRequestContent content, FinanceCatalog.LegalEntity legalEntity,
                                  String catalogVersion, EmployeeAccountSnapshot account) {
    /** 重建时核对币种、账户归属和提交日，不把其他法人或他人账户混入本轮。 */
    public AdvanceRequestRound {
        if (roundNo < 1 || submittedRequestVersion < 1 || StringUtils.isBlank(submittedBy) || submittedBy.length() > 128
                || submittedAt == null || content == null || legalEntity == null || !legalEntity.id().equals(content.legalEntityId())
                || StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128 || account == null
                || !account.employeeId().equals(submittedBy) || !account.legalEntityId().equals(legalEntity.id())) {
            throw new DomainException("INVALID_ADVANCE_REQUEST_ROUND", "Advance request round, applicant and account must match");
        }
        if (!content.amount().currency().equals(legalEntity.baseCurrency())) {
            throw new DomainException("ADVANCE_BASE_CURRENCY_REQUIRED", "Employee advances must use the legal entity base currency");
        }
        if (content.dueOn().isBefore(LocalDate.ofInstant(submittedAt, ZoneId.of(legalEntity.timeZone())))) {
            throw new DomainException("ADVANCE_REPAYMENT_DATE_PASSED", "Repayment date cannot precede the local submission date");
        }
    }

    /** 日志不能通过快照的默认字符串表示暴露账户引用或借款用途。 */
    @Override public String toString() { return "AdvanceRequestRound[roundNo=" + roundNo + ", submittedRequestVersion=" + submittedRequestVersion + "]"; }
}
