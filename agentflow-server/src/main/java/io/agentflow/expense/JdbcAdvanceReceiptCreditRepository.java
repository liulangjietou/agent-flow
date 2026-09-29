package io.agentflow.expense;

import io.agentflow.finance.AdvanceRepaymentPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 主动还款和放款退回共用入款防重，避免更换业务入口后再次冲减同一债权。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceReceiptCreditRepository {
    private final JdbcTemplate jdbc;
    /** 唯一键与原业务记录同事务提交，数据库承担跨借款并发互斥。 */
    public JdbcAdvanceReceiptCreditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 主动还款仍保留自己的原记录及对外错误码。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdvanceRepayment repayment) {
        var receipt = repayment.receipt(); var request = receipt.request();
        insert(repayment.tenantId(), request.legalEntityId(), request.advanceId(), receipt.funding(), receipt.posting(), repayment.id(), null);
    }
    /** 一个累计决定只登记本次新增的独立银行入款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdvanceDisbursementReturn decision, AdvanceDisbursementReturn.Entry entry) {
        var command = decision.receipt().request().command(); var proof = entry.proof();
        insert(decision.tenantId(), command.payee().legalEntityId(), command.binding().businessId(), proof.funding(), proof.posting(), null, decision.id());
    }
    private void insert(String tenant, UUID entity, UUID advanceId, AdvanceRepaymentPort.Funding funding, AdvanceRepaymentPort.Posting posting, UUID repaymentId, UUID decisionId) {
        jdbc.update("""
                INSERT INTO advance_receipt_credit(tenant_id,legal_entity_id,advance_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,repayment_id,disbursement_resolution_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, tenant, entity.toString(), advanceId.toString(), funding.channel().name(), funding.transactionReference(), posting.voucherReference(), posting.entryReference(),
                funding.amount().value(), funding.amount().currency(), repaymentId == null ? null : repaymentId.toString(), decisionId == null ? null : decisionId.toString());
    }
}
