package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.procurement.mapper.SupplierAdjustmentCompletionsMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 完成记录只引用实际不可变修订，恢复时重放账本变化，不依赖当前操作的查询状态。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierAdjustmentCompletions {
    private final SupplierAdjustmentCompletionsMapper sqlMapper;
    private final JsonUtil json;

    /** 直接读取已固定的修订，避免账本、占用及完成仓储之间形成循环依赖。 */
    public JdbcSupplierAdjustmentCompletions(
            SupplierAdjustmentCompletionsMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 上层先锁原申请与银行并追加后继账本修订，完成证据与本地余额变更在同一事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierAdjustmentCompletion proof) {
        var operation = proof.operation();
        var command = operation.command();
        var bank = proof.bank();
        var before = proof.before();
        int current =
                SqlRows.single(
                        sqlMapper.create(
                                command.tenantId(),
                                command.id().toString(),
                                operation.version(),
                                json.write(command),
                                command.digest(),
                                json.write(operation),
                                bank.version(),
                                json.write(bank),
                                json.write(bank.command()),
                                bank.command().digest(),
                                before.version(),
                                json.write(before)));
        if (current != 1) throw changed();
        sqlMapper.create2(
                command.tenantId(),
                command.id().toString(),
                operation.version(),
                bank.command().id().toString(),
                bank.version(),
                bank.status().name(),
                bank.command().holdCommand().authorization().source().reservation().id().toString(),
                before.version(),
                proof.after().version(),
                proof.after().accountedEntryCount(),
                json.write(proof.receipt()),
                Timestamp.from(proof.completedAt().truncatedTo(ChronoUnit.MICROS)));
        if (!find(command.tenantId(), command.id()).filter(proof::equals).isPresent())
            throw changed();
    }

    /** 后续操作进入查询也保留原成功和银行事实，读取不能使用当前状态替换历史完成。 */
    public Optional<SupplierAdjustmentCompletion> find(String tenant, UUID operationId) {
        return SqlRows.map(sqlMapper.find(tenant, operationId.toString()), this::restore).stream()
                .findFirst();
    }

    /** 完成时新确认的银行原件继续约束后续查询、资金登记及下一次记账。 */
    public List<SupplierAdjustmentCompletion> history(String tenant, UUID paymentId) {
        return SqlRows.map(sqlMapper.history(tenant, paymentId.toString()), this::restore);
    }

    private SupplierAdjustmentCompletion restore(SqlRow row) {
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

    private static DomainException changed() {
        return new DomainException(
                "SUPPLIER_ADJUSTMENT_COMPLETION_CHANGED",
                "Supplier adjustment completion requires the exact persisted operation, bank and"
                    + " return ledger revisions");
    }
}
