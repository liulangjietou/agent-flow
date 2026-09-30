package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立预算调整读取原台账额度，不复用报销冻结预检，也不在读取时预留或修改预算。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetLedgerPort {
    int MAX_POSITIONS = 2;
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    /** 按申请人读取指定法人与预算期间的原台账，始终绑定已选择的财务目的地。 */
    FinanceResult<Snapshot> read(String tenantId, String targetDigest, Request request);

    /**
     * 调整只涉及一个额度，或者同次调拨的两个额度；预算引用由外部台账解释其成本维度。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID legalEntityId, String employeeId, LocalDate accountingDate, List<String> budgetReferences) {
        /** 不接受重复预算、空引用或通过请求附带自报余额。 */
        public Request {
            if (legalEntityId == null || invalidText(employeeId, 128) || accountingDate == null
                    || budgetReferences == null || budgetReferences.isEmpty() || budgetReferences.size() > MAX_POSITIONS
                    || budgetReferences.stream().anyMatch(reference -> invalidText(reference, 128))
                    || new HashSet<>(budgetReferences).size() != budgetReferences.size()) throw invalid();
            budgetReferences = List.copyOf(budgetReferences);
        }
    }

    /**
     * 本次读取的完整事实；每个预算保留独立版本，后续写入必须由原系统原子校验。
     * @author owlzhangfq@gmail.com
     */
    record Snapshot(Request request, String sourceVersion, Instant observedAt, Instant validUntil, List<Position> positions) {
        /** 返回的预算必须恰好覆盖请求，不能混入另一个法人、日期或未申请的额度。 */
        public Snapshot {
            if (request == null || invalidText(sourceVersion, 128) || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || positions == null || positions.size() != request.budgetReferences().size()
                    || positions.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            positions = List.copyOf(positions);
            var references = new HashSet<String>();
            for (var position : positions) {
                if (!references.add(position.reference()) || !request.budgetReferences().contains(position.reference())
                        || !position.legalEntityId().equals(request.legalEntityId())
                        || request.accountingDate().isBefore(position.periodStart())
                        || request.accountingDate().isAfter(position.periodEnd())) throw invalid();
            }
        }

        /** 新读取要求五分钟内且尚未过期；历史轮次应传原提交时间而非当前时间。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && !observedAt.isAfter(now) && validUntil.isAfter(now)
                    && observedAt.plus(MAX_EVIDENCE_AGE).isAfter(now);
        }

        /** 按稳定引用取回已核对的预算，返回顺序不改变调出和调入含义。 */
        public Position position(String reference) {
            return positions.stream().filter(position -> position.reference().equals(reference)).findFirst().orElseThrow(BudgetLedgerPort::invalid);
        }

        /** 不在默认对象日志中展开预算额度和成本对象。 */
        @Override public String toString() { return "BudgetLedgerSnapshot[positionCount=" + positions.size() + "]"; }
    }

    /**
     * 已占用和已使用是原预算台账的两个互斥余额，不从本系统报销记录推算。
     * @author owlzhangfq@gmail.com
     */
    record Position(UUID legalEntityId, String reference, String name, String version, String periodReference,
                    LocalDate periodStart, LocalDate periodEnd, PeriodStatus periodStatus, Money limit, Money committed, Money consumed) {
        /** 超预算可能是实际历史事实，仍可读取并追加额度，不能伪造零占用来使校验通过。 */
        public Position {
            if (legalEntityId == null || invalidText(reference, 128) || invalidText(name, 256) || invalidText(version, 128)
                    || invalidText(periodReference, 128) || periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)
                    || periodStatus == null || limit == null || committed == null || consumed == null) throw invalid();
            limit.sameCurrency(committed);
            limit.sameCurrency(consumed);
        }

        /** 可调减额只来自未占用部分，超预算时没有可调减额。 */
        public Money available() {
            return new Money(limit.value().subtract(committed.value()).subtract(consumed.value()).max(BigDecimal.ZERO), limit.currency());
        }

        /** 不在默认日志中打印预算维度和实际额度。 */
        @Override public String toString() { return "BudgetPosition[redacted]"; }
    }

    /**
     * 期间关闭允许读取历史，但禁止新调整提交及执行。
     * @author owlzhangfq@gmail.com
     */
    enum PeriodStatus { OPEN, CLOSED }

    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_BUDGET_LEDGER", "Budget ledger must preserve requested identities, periods and exact balances");
    }
}
