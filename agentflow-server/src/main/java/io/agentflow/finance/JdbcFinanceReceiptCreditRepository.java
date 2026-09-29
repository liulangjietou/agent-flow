package io.agentflow.finance;

import io.agentflow.expense.AdvanceRepayment;
import io.agentflow.expense.AdvanceDisbursementReturn;
import io.agentflow.expense.ExpensePaymentReturn;
import io.agentflow.expense.ExpensePaymentReturns;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 借款与报销共用法人内入款及贷方分录防重，原业务记录继续独立保存。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcFinanceReceiptCreditRepository {
    private final JdbcTemplate jdbc;
    public JdbcFinanceReceiptCreditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 员工主动还款与其原始登记共用事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdvanceRepayment repayment) {
        var receipt = repayment.receipt(); var request = receipt.request(); var funds = receipt.funding(); var posting = receipt.posting();
        insert(repayment.tenantId(), request.legalEntityId(), request.advanceId(), funds.channel().name(), funds.transactionReference(), posting.voucherReference(),
                posting.entryReference(), funds.amount(), repayment.id(), null, null);
    }
    /** 累计借款退票只登记本次首次追加的实际入款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdvanceDisbursementReturn decision, AdvanceDisbursementReturn.Entry entry) {
        var command = decision.receipt().request().command(); var funds = entry.proof().funding(); var posting = entry.proof().posting();
        insert(decision.tenantId(), command.payee().legalEntityId(), command.binding().businessId(), funds.channel().name(), funds.transactionReference(),
                posting.voucherReference(), posting.entryReference(), funds.amount(), null, decision.id(), null);
    }
    /** 报销退回不能再次采用已被还款或借款退票使用的资金及分录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ExpensePaymentReturn decision, ExpensePaymentReturns.Entry entry) {
        var command = decision.receipt().request().command(); var funds = entry.proof().funding(); var posting = entry.proof().posting();
        insert(decision.tenantId(), command.payee().legalEntityId(), command.binding().businessId(), AdvanceRepaymentPort.Channel.BANK_TRANSFER.name(), funds.transactionReference(),
                posting.voucherReference(), posting.entryReference(), funds.amount(), null, null, decision.id());
    }
    private void insert(String tenant, UUID entity, UUID business, String channel, String transaction, String voucher, String entry, Money amount, UUID repayment, UUID disbursement, UUID expense) {
        jdbc.update("""
                INSERT INTO finance_receipt_credit(tenant_id,legal_entity_id,business_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency,repayment_id,disbursement_resolution_id,expense_registration_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant, entity.toString(), business.toString(), channel, transaction, voucher, entry, amount.value(), amount.currency(), id(repayment), id(disbursement), id(expense));
    }
    private static String id(UUID value) { return value == null ? null : value.toString(); }
}
