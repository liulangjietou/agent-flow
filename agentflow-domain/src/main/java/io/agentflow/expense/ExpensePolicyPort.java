package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 提交时按实际员工、法人和费用事实匹配制度，不能由客户端指定“已合规”。
 * @author owlzhangfq@gmail.com
 */
public interface ExpensePolicyPort {
    /** 只读判定不预留预算、不修改事前额度，也不替代后续审批。 */
    FinanceResult<Assessment> assess(String tenantId, Request request);

    /**
     * 制度服务所需的实际行、汇率和已查验票据，均由应用服务组装。
     * @author owlzhangfq@gmail.com
     */
    record Request(String employeeId, UUID legalEntityId, ExpenseContent.Type reportType, ExpenseLine line,
                   ExpenseExchangeRate exchangeRate, List<InvoiceEvidence> invoices) {
        /** 所有票据必须属于该行，金额判定基于同一原币与法人。 */
        public Request {
            if (StringUtils.isBlank(employeeId) || employeeId.length() > 128 || legalEntityId == null || reportType == null
                    || line == null || exchangeRate == null || !line.claimedGross().currency().equals(exchangeRate.fromCurrency())
                    || invoices == null || invoices.size() != line.invoiceIds().size() || invoices.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            var ids = new HashSet<UUID>();
            for (var invoice : invoices) if (!ids.add(invoice.invoiceId()) || !line.invoiceIds().contains(invoice.invoiceId())
                    || !legalEntityId.equals(invoice.facts().legalEntityId())) throw invalid();
            invoices = List.copyOf(invoices);
        }
    }

    /**
     * 本地票夹身份与真实查验事实的绑定。
     * @author owlzhangfq@gmail.com
     */
    record InvoiceEvidence(UUID invoiceId, Invoice.VerifiedFacts facts) {
        /** 不能用文件名或未查验的客户端票面代替事实。 */
        public InvoiceEvidence { if (invoiceId == null || facts == null) throw invalid(); }
    }

    /**
     * 核算制度快照及有限有效期；上限和可抵扣额均为本位币。
     * @author owlzhangfq@gmail.com
     */
    record Assessment(ExpensePolicySnapshot policy, Money deductibleTax, boolean priorRequestRequired, Instant validUntil) {
        /** 判定必须完整且与核算金额币种一致。 */
        public Assessment {
            if (policy == null || deductibleTax == null || validUntil == null || deductibleTax.compareTo(policy.assessedGross()) > 0) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_POLICY_REQUEST", "Expense policy facts are incomplete or inconsistent"); }
}
