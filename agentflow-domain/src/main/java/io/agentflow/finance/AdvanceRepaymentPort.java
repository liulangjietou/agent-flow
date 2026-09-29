package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 查询财务系统已经收款并冲减指定员工借款的原凭据，不发起扣款或代替 ERP 记账。
 * @author owlzhangfq@gmail.com
 */
public interface AdvanceRepaymentPort {
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);
    /** 查询固定原资金系统中的一笔还款凭据，查无及未入账不能解释为还款成功。 */
    FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request);

    /**
     * 业务及放款身份由后端生成，前端仅提供收款凭据编号。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID advanceId, UUID legalEntityId, String employeeId, String paymentReference, String currency, String receiptReference) {
        /** 一笔凭据必须明确冲减原法人、原员工及原放款的借款。 */
        public Request {
            if (advanceId == null || legalEntityId == null || !reference(employeeId) || !reference(paymentReference) || !reference(receiptReference)) throw invalid();
            Money.zero(currency);
        }
        /** 常规日志只定位借款，不展开人员或外部编号。 */
        @Override public String toString() { return "AdvanceRepaymentRequest[advanceId=" + advanceId + "]"; }
    }

    /**
     * 同时保留资金与会计事实；迟到撤销只形成异常依据，不自动恢复可用借款。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil, Funding funding, Posting posting) {
        /** 成功和撤销均必须指向完整原收款及原入账，禁止只返回成功布尔值。 */
        public Receipt {
            if (request == null || status == null || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)
                    || validUntil.isAfter(observedAt.plus(MAX_EVIDENCE_AGE)) || (status == Status.NOT_FOUND ? revision != 0 : revision < 1)) throw invalid();
            boolean terminal = status == Status.CONFIRMED || status == Status.REVERSED;
            if (terminal) {
                if (funding == null || posting == null || !funding.amount().currency().equals(request.currency()) || !funding.amount().equals(posting.amount())
                        || funding.receivedAt().isAfter(posting.postedAt()) || posting.postedAt().isAfter(observedAt)) throw invalid();
            } else if (funding != null || posting != null) throw invalid();
        }
        /** 结果必须绑定精确请求且仍有效，未来观测不能被提前消费。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil);
        }
        /** 再次查询只能增加观测版本，不能替换既有收款金额、资金编号或会计分录。 */
        public boolean sameSettlement(Receipt original) {
            return original != null && request.equals(original.request()) && funding != null && funding.equals(original.funding()) && posting.equals(original.posting());
        }
        /** 不把自由格式外部编号加入诊断信息。 */
        @Override public String toString() { return "AdvanceRepaymentReceipt[advanceId=" + request.advanceId() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 实际收到款项或已经执行的工资扣回，不包含完整银行账户。
     * @author owlzhangfq@gmail.com
     */
    record Funding(Channel channel, String transactionReference, Money amount, Instant receivedAt) {
        /** 每笔还款必须有独立的正金额资金凭据。 */
        public Funding {
            if (channel == null || !reference(transactionReference) || amount == null || amount.value().signum() <= 0 || receivedAt == null) throw invalid();
        }
    }
    /**
     * ERP 已入账的员工借款贷方分录，同一分录不得再次用于其他借款。
     * @author owlzhangfq@gmail.com
     */
    record Posting(String voucherReference, String entryReference, Money amount, LocalDate accountingDate, Instant postedAt) {
        /** 分录金额须与收款一致；未过账草稿凭证不构成还款依据。 */
        public Posting {
            if (!reference(voucherReference) || !reference(entryReference) || amount == null || amount.value().signum() <= 0 || accountingDate == null || postedAt == null) throw invalid();
        }
    }
    /**
     * 资金系统查无或处理中不会冲减余额。
     * @author owlzhangfq@gmail.com
     */
    enum Status { NOT_FOUND, PENDING, CONFIRMED, REVERSED }
    /**
     * 仅识别已执行的员工主动还款渠道，原放款退票使用独立调整流程。
     * @author owlzhangfq@gmail.com
     */
    enum Channel { BANK_TRANSFER, CASH, PAYROLL }

    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REPAYMENT_RECEIPT", "Repayment requires matching original loan, received funds and posted receivable entry"); }
}
