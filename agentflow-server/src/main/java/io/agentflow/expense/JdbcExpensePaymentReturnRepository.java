package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立财务登记必须匹配消费后的查询、相邻退回修订及当前结算事实。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePaymentReturnRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcExpensePaymentReturnCheckRepository checks;
    private final JdbcExpensePaymentReturnsRepository ledgers;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcFinanceReceiptCreditRepository credits;
    public JdbcExpensePaymentReturnRepository(JdbcTemplate jdbc, JsonUtil json, JdbcExpensePaymentReturnCheckRepository checks,
            JdbcExpensePaymentReturnsRepository ledgers, JdbcExpenseSettlementRepository settlements, JdbcFinanceReceiptCreditRepository credits) {
        this.jdbc = jdbc; this.json = json; this.checks = checks; this.ledgers = ledgers; this.settlements = settlements; this.credits = credits;
    }
    /** 决定、防重与账本同事务提交，多笔入款任一冲突则全部回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePaymentReturn decision, ExpensePaymentReturns after) {
        var tenant = decision.tenantId(); var reportId = after.request().command().binding().businessId();
        var check = checks.find(tenant, decision.checkId()).orElseThrow(JdbcExpensePaymentReturnRepository::changed);
        if (check.status() != ExpensePaymentReturnCheck.Status.RESOLVED || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                || !decision.registeredBy().equals(check.input().requestedBy()) || !decision.registeredAt().equals(check.updatedAt())
                || !ledgers.revision(tenant, reportId, after.version() - 1).register(decision).equals(after)
                || !ledgers.revision(tenant, reportId, after.version()).equals(after)) throw changed();
        var settlement = settlements.find(tenant, reportId).orElseThrow(JdbcExpensePaymentReturnRepository::changed);
        if (!decision.applyTo(settlement).equals(settlement)) throw changed();
        jdbc.update("""
                INSERT INTO expense_payment_return_registration(tenant_id,id,report_id,return_version,settlement_version,check_id,check_version,outcome,registered_by,observed_at,registered_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant, decision.id().toString(), reportId.toString(), after.version(), settlement.version(), decision.checkId().toString(), check.version(),
                decision.receipt().status().name(), decision.registeredBy(), Timestamp.from(decision.receipt().observedAt()), Timestamp.from(decision.registeredAt()), json.write(decision));
        try {
            for (var entry : after.entries()) if (entry.registrationId().equals(decision.id())) credits.record(decision, entry);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("EXPENSE_PAYMENT_RETURN_ALREADY_RECORDED", "Bank receipt or payable credit already belongs to another repayment or payment return");
        }
    }
    /** 人工历史按退回账本版本排序，不依赖时间戳相同情况下的随机编号。 */
    public List<ExpensePaymentReturn> history(String tenant, UUID reportId) {
        return jdbc.query("SELECT * FROM expense_payment_return_registration WHERE tenant_id=? AND report_id=? ORDER BY return_version", (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpensePaymentReturn.class); var command = value.receipt().request().command();
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                    || !command.binding().businessId().toString().equals(row.getString("report_id")) || !value.checkId().toString().equals(row.getString("check_id"))
                    || !value.receipt().status().name().equals(row.getString("outcome")) || !value.registeredBy().equals(row.getString("registered_by"))
                    || !value.receipt().observedAt().equals(row.getTimestamp("observed_at").toInstant()) || !value.registeredAt().equals(row.getTimestamp("registered_at").toInstant())) {
                throw new IllegalStateException("Persisted expense return registration identity is inconsistent");
            }
            return value;
        }, tenant, reportId.toString());
    }
    private static DomainException changed() { return new DomainException("EXPENSE_PAYMENT_RETURN_SOURCE_CHANGED", "Expense return registration must match its consumed check and ledger revisions"); }
}
