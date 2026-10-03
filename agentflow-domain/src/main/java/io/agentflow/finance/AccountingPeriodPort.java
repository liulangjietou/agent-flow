package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ERP 会计期间只读端口，关闭期间明确阻断，不自动移动记账日期。
 * @author owlzhangfq@gmail.com
 */
public interface AccountingPeriodPort {
    /** 按持久化的财务目标核对原记账日期。 */
    FinanceResult<OpenPeriod> period(String tenantId, String targetDigest, Request request);

    /**
     * 法人、本位币和记账日期共同确定待检查期间。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID legalEntityId, String currency, LocalDate accountingDate) {
        /** 不允许由 ERP 默认为其他法人、币种或日期。 */
        public Request {
            if (legalEntityId == null || accountingDate == null) throw invalid();
            Money.zero(currency);
        }
    }

    /**
     * 期间开放是带版本和时效的事实，实际推送时 ERP 仍须原子复核。
     * @author owlzhangfq@gmail.com
     */
    record OpenPeriod(Request request, String periodReference, String sourceVersion, LocalDate startsOn, LocalDate endsOn,
                      Instant observedAt, Instant validUntil) {
        /** 只接受包含原记账日期的开放期间及非空版本。 */
        public OpenPeriod {
            if (request == null || invalidText(periodReference) || invalidText(sourceVersion) || startsOn == null || endsOn == null
                    || endsOn.isBefore(startsOn) || request.accountingDate().isBefore(startsOn) || request.accountingDate().isAfter(endsOn)
                    || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)) throw invalid();
        }
        /** 未来事实和到期事实均不能成为新的推送依据。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && !observedAt.isAfter(now) && validUntil.isAfter(now);
        }
    }

    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_ACCOUNTING_PERIOD", "An open accounting period must cover the original date with versioned evidence"); }
}
