package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 同一报销只形成一份核销账本，轮次和外部依据不能被重试替换。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseSettlementRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 资源、预算登记和结算修订共用原业务事务。 */
    public JdbcExpenseSettlementRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 仅接受未消费的新结算，不通过恢复对象伪造已经完成的预算事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseSettlement value) {
        if (!ExpenseSettlement.queue(value.input(), value.createdAt()).equals(value)) throw conflict();
        var input = value.input(); var source = input.source();
        jdbc.update("""
                INSERT INTO expense_settlement(tenant_id,report_id,application_id,round_no,application_version,financial_version,
                voucher_operation_id,payment_operation_id,input_json,state_json,version,status,resources_consumed,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,1,'QUEUED',FALSE,?,?)
                """, source.tenantId(), source.businessId().toString(), source.applicationId().toString(), source.roundNo(), source.applicationVersion(), source.businessVersion(),
                id(input.voucherOperationId()), input.payment() == null ? null : id(input.payment().operationId()), json.write(input), json.write(value),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
        append(value);
    }

    /** 前版本与全部原输入匹配后更新，追加证据失败会一并回滚资源和预算命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseSettlement value) {
        var source = value.input().source();
        int changed = jdbc.update("""
                UPDATE expense_settlement SET state_json=?,version=?,status=?,resources_consumed=?,budget_operation_id=?,issue=?,updated_at=?
                WHERE tenant_id=? AND report_id=? AND version=? AND input_json=? AND created_at=? AND (resources_consumed=FALSE OR ?=TRUE)
                """, json.write(value), value.version(), value.status().name(), value.resourcesConsumed(), id(value.budgetOperationId()), value.issue(), Timestamp.from(value.updatedAt()),
                source.tenantId(), source.businessId().toString(), value.version() - 1, json.write(value.input()), Timestamp.from(value.createdAt()), value.resourcesConsumed());
        if (changed != 1) throw conflict(); append(value);
    }

    /** 仓储总以独立租户列定位，不能凭输入 JSON 选择另一个租户。 */
    public Optional<ExpenseSettlement> find(String tenant, UUID reportId) {
        return jdbc.query("SELECT * FROM expense_settlement WHERE tenant_id=? AND report_id=?", row(), tenant, reportId.toString()).stream().findFirst();
    }

    /** 本地核销不含网络等待，消费者用原报销锁完成有界短事务。 */
    public List<Candidate> pending() {
        return jdbc.query("SELECT tenant_id,report_id,version FROM expense_settlement WHERE status='QUEUED' ORDER BY updated_at,tenant_id,report_id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("report_id")), row.getLong("version")));
    }

    /** 轮转游标避免未满足零应付条件的前十份凭证阻塞后续升级补登，进程重启可安全重扫。 */
    public List<RecoveryCandidate> recoveryCandidates(RecoveryCandidate after) {
        String sql = """
                SELECT f.* FROM (
                    SELECT 'PAYMENT' AS kind,p.tenant_id,a.business_id AS report_id,p.id FROM payment_operation p
                    JOIN payment_authorization a ON a.tenant_id=p.tenant_id AND a.id=p.id
                    WHERE a.purpose='EXPENSE_REIMBURSEMENT' AND p.status='SUCCEEDED'
                    UNION ALL
                    SELECT 'VOUCHER' AS kind,tenant_id,business_id AS report_id,id FROM voucher_operation
                    WHERE kind='EXPENSE_ACCRUAL' AND status='POSTED'
                    UNION ALL
                    SELECT 'ZERO' AS kind,tenant_id,business_id AS report_id,id FROM voucher_preparation
                    WHERE kind='EXPENSE_ACCRUAL' AND status='NOT_REQUIRED'
                ) f WHERE NOT EXISTS (SELECT 1 FROM expense_settlement s WHERE s.tenant_id=f.tenant_id AND s.report_id=f.report_id)
                """;
        var args = new java.util.ArrayList<Object>();
        if (after != null) {
            sql += " AND (f.kind>? OR (f.kind=? AND f.tenant_id>?) OR (f.kind=? AND f.tenant_id=? AND f.id>?))";
            args.addAll(List.of(after.kind().name(), after.kind().name(), after.tenantId(), after.kind().name(), after.tenantId(), after.id().toString()));
        }
        return jdbc.query(sql + " ORDER BY f.kind,f.tenant_id,f.id LIMIT 10", (row, index) -> new RecoveryCandidate(
                FundingKind.valueOf(row.getString("kind")), row.getString("tenant_id"), UUID.fromString(row.getString("report_id")), UUID.fromString(row.getString("id"))), args.toArray());
    }

    private RowMapper<ExpenseSettlement> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), ExpenseSettlement.class); var input = value.input(); var source = input.source();
            if (!input.equals(json.read(row.getString("input_json"), ExpenseSettlement.Input.class))
                    || !source.tenantId().equals(row.getString("tenant_id")) || !source.businessId().toString().equals(row.getString("report_id"))
                    || !source.applicationId().toString().equals(row.getString("application_id")) || !source.businessType().name().equals(row.getString("business_type"))
                    || source.roundNo() != row.getInt("round_no") || source.applicationVersion() != row.getLong("application_version") || source.businessVersion() != row.getLong("financial_version")
                    || !Objects.equals(id(input.voucherOperationId()), row.getString("voucher_operation_id"))
                    || !Objects.equals(input.payment() == null ? null : id(input.payment().operationId()), row.getString("payment_operation_id"))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || value.resourcesConsumed() != row.getBoolean("resources_consumed") || !Objects.equals(id(value.budgetOperationId()), row.getString("budget_operation_id"))
                    || !Objects.equals(value.issue(), row.getString("issue")) || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) throw new IllegalStateException("Persisted expense settlement identity is inconsistent");
            return value;
        };
    }
    private void append(ExpenseSettlement value) {
        var source = value.input().source();
        jdbc.update("INSERT INTO expense_settlement_revision(tenant_id,report_id,version,state_json) VALUES(?,?,?,?)",
                source.tenantId(), source.businessId().toString(), value.version(), json.write(value));
    }
    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense settlement input or version changed"); }

    /**
     * 调度候选不携带金额、回单或其他敏感数据。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID reportId, long version) { }

    /**
     * 仅指向已持久化的会计或资金依据。
     * @author owlzhangfq@gmail.com
     */
    public enum FundingKind { PAYMENT, VOUCHER, ZERO }

    /**
     * 补登扫描游标不携带财务金额或回单。
     * @author owlzhangfq@gmail.com
     */
    public record RecoveryCandidate(FundingKind kind, String tenantId, UUID reportId, UUID id) { }
}
