package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 完成记录只引用实际不可变修订，恢复时重放账本变化，不依赖当前操作的查询状态。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierAdjustmentCompletions {
    private static final String SELECT_PROOF = """
            SELECT c.*,o.state_json AS operation_json,b.state_json AS bank_json,
                prior.state_json AS before_json,afterward.state_json AS after_json
            FROM supplier_adjustment_completion c
            JOIN supplier_payable_adjustment_revision o ON o.tenant_id=c.tenant_id AND o.operation_id=c.operation_id AND o.version=c.operation_version
            JOIN supplier_payment_revision b ON b.tenant_id=c.tenant_id AND b.operation_id=c.payment_id AND b.version=c.payment_version
            JOIN supplier_payment_returns_revision prior ON prior.tenant_id=c.tenant_id AND prior.payment_id=c.payment_id AND prior.version=c.before_return_version
            JOIN supplier_payment_returns_revision afterward ON afterward.tenant_id=c.tenant_id AND afterward.payment_id=c.payment_id AND afterward.version=c.return_version
            """;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 直接读取已固定的修订，避免账本、占用及完成仓储之间形成循环依赖。 */
    public JdbcSupplierAdjustmentCompletions(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 上层先锁原申请与银行并追加后继账本修订，完成证据与本地余额变更在同一事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierAdjustmentCompletion proof) {
        var operation = proof.operation(); var command = operation.command(); var bank = proof.bank(); var before = proof.before();
        int current = jdbc.queryForObject("""
                SELECT COUNT(*) FROM supplier_payable_adjustment_operation o
                JOIN supplier_payment_operation b ON b.tenant_id=o.tenant_id AND b.id=o.payment_id
                JOIN supplier_payment_returns f ON f.tenant_id=o.tenant_id AND f.payment_id=o.payment_id
                WHERE o.tenant_id=? AND o.id=? AND o.version=? AND o.status='ADJUSTED' AND o.retired_version IS NULL
                    AND o.completed_version IS NULL AND o.active_payment_id=o.payment_id
                    AND o.command_json=? AND o.command_digest=? AND o.state_json=?
                    AND b.version=? AND b.state_json=? AND b.command_json=? AND b.command_digest=? AND b.status IN ('SUCCEEDED','REVERSED')
                    AND f.version=? AND f.state_json=?
                """, Integer.class, command.tenantId(), command.id().toString(), operation.version(), json.write(command), command.digest(), json.write(operation),
                bank.version(), json.write(bank), json.write(bank.command()), bank.command().digest(), before.version(), json.write(before));
        if (current != 1) throw changed();
        jdbc.update("""
                INSERT INTO supplier_adjustment_completion(tenant_id,operation_id,operation_version,payment_id,payment_version,bank_status,reservation_id,
                    before_return_version,return_version,accounted_entry_count,proof_json,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, command.tenantId(), command.id().toString(), operation.version(), bank.command().id().toString(), bank.version(),
                bank.status().name(), bank.command().holdCommand().authorization().source().reservation().id().toString(), before.version(), proof.after().version(),
                proof.after().accountedEntryCount(), json.write(proof.receipt()), Timestamp.from(proof.completedAt().truncatedTo(ChronoUnit.MICROS)));
        if (!find(command.tenantId(), command.id()).filter(proof::equals).isPresent()) throw changed();
    }

    /** 后续操作进入查询也保留原成功和银行事实，读取不能使用当前状态替换历史完成。 */
    public Optional<SupplierAdjustmentCompletion> find(String tenant, UUID operationId) {
        return jdbc.query(SELECT_PROOF + " WHERE c.tenant_id=? AND c.operation_id=?", this::restore, tenant, operationId.toString()).stream().findFirst();
    }

    /** 完成时新确认的银行原件继续约束后续查询、资金登记及下一次记账。 */
    public List<SupplierAdjustmentCompletion> history(String tenant, UUID paymentId) {
        return jdbc.query(SELECT_PROOF + " WHERE c.tenant_id=? AND c.payment_id=? ORDER BY c.return_version", this::restore, tenant, paymentId.toString());
    }

    private SupplierAdjustmentCompletion restore(ResultSet row, int index) throws SQLException {
        var operation = json.read(row.getString("operation_json"), SupplierPayableAdjustmentOperation.class);
        var bank = json.read(row.getString("bank_json"), SupplierPaymentOperation.class);
        var before = json.read(row.getString("before_json"), SupplierPaymentReturns.class);
        var after = json.read(row.getString("after_json"), SupplierPaymentReturns.class);
        var receipt = json.read(row.getString("proof_json"), SupplierPaymentReturnPort.Receipt.class);
        var proof = new SupplierAdjustmentCompletion(operation, bank, receipt, before, after);
        if (!operation.command().tenantId().equals(row.getString("tenant_id")) || !operation.command().id().toString().equals(row.getString("operation_id"))
                || operation.version() != row.getLong("operation_version") || !bank.command().id().toString().equals(row.getString("payment_id"))
                || bank.version() != row.getLong("payment_version") || !bank.status().name().equals(row.getString("bank_status"))
                || before.version() != row.getLong("before_return_version") || after.version() != row.getLong("return_version")
                || !bank.command().holdCommand().authorization().source().reservation().id().toString().equals(row.getString("reservation_id"))
                || after.accountedEntryCount() != row.getInt("accounted_entry_count")
                || !proof.completedAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("completed_at").toInstant())) throw changed();
        return proof;
    }
    private static DomainException changed() { return new DomainException("SUPPLIER_ADJUSTMENT_COMPLETION_CHANGED", "Supplier adjustment completion requires the exact persisted operation, bank and return ledger revisions"); }
}
