package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.ExpensePaymentReturnPort;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报销退回的当前账本与不可变修订，共用报销锁但不改写原结算输入。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePaymentReturnsRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    public JdbcExpensePaymentReturnsRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 首次只能保存无退回、无冻结的原始查询意图。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePaymentReturns value) {
        if (!value.equals(ExpensePaymentReturns.open(value.request(), value.createdAt()))) throw conflict();
        var command = value.request().command();
        jdbc.update("""
                INSERT INTO expense_payment_returns(tenant_id,report_id,payment_id,input_json,state_json,version,review_required,created_at,updated_at)
                VALUES(?,?,?,?,?,1,FALSE,?,?)
                """, command.tenantId(), command.binding().businessId().toString(), command.id().toString(), json.write(value.request()), json.write(value),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
        append(value);
    }
    /** 版本、原付款及创建时刻共同锁定；并发登记不能丢失此前入款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePaymentReturns value) {
        var command = value.request().command();
        int changed = jdbc.update("""
                UPDATE expense_payment_returns SET state_json=?,version=?,review_required=?,updated_at=?
                WHERE tenant_id=? AND report_id=? AND version=? AND input_json=? AND created_at=?
                """, json.write(value), value.version(), value.reviewRequired(), Timestamp.from(value.updatedAt()), command.tenantId(), command.binding().businessId().toString(),
                value.version() - 1, json.write(value.request()), Timestamp.from(value.createdAt()));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 当前状态仅按租户和原报销定位。 */
    public Optional<ExpensePaymentReturns> find(String tenant, UUID reportId) {
        return jdbc.query("SELECT * FROM expense_payment_returns WHERE tenant_id=? AND report_id=?", row(), tenant, reportId.toString()).stream().findFirst();
    }
    /** 财务决定必须可重放到已保存的前后修订。 */
    public ExpensePaymentReturns revision(String tenant, UUID reportId, long version) {
        return jdbc.query("SELECT state_json FROM expense_payment_returns_revision WHERE tenant_id=? AND report_id=? AND version=?",
                (row, index) -> json.read(row.getString("state_json"), ExpensePaymentReturns.class), tenant, reportId.toString(), version).stream().findFirst().orElseThrow(JdbcExpensePaymentReturnsRepository::conflict);
    }
    private RowMapper<ExpensePaymentReturns> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpensePaymentReturns.class); var command = value.request().command();
            if (!command.tenantId().equals(row.getString("tenant_id")) || !command.binding().businessId().toString().equals(row.getString("report_id"))
                    || !command.id().toString().equals(row.getString("payment_id")) || !value.request().equals(json.read(row.getString("input_json"), ExpensePaymentReturnPort.Request.class))
                    || value.version() != row.getLong("version") || value.reviewRequired() != row.getBoolean("review_required")
                    || !value.createdAt().equals(row.getTimestamp("created_at").toInstant()) || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) {
                throw new IllegalStateException("Persisted expense return ledger identity is inconsistent");
            }
            return value;
        };
    }
    private void append(ExpensePaymentReturns value) {
        var command = value.request().command();
        jdbc.update("INSERT INTO expense_payment_returns_revision(tenant_id,report_id,version,state_json) VALUES(?,?,?,?)", command.tenantId(), command.binding().businessId().toString(), value.version(), json.write(value));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense return ledger version or original payment changed"); }
}
