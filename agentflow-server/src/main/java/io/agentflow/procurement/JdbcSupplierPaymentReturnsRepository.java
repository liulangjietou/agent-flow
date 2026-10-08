package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.procurement.mapper.SupplierPaymentReturnsRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 回款账本引用原成功银行修订，冻结和具名登记之外不允许直接修改累计资金。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentReturnsRepository {
    private final SupplierPaymentReturnsRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final SupplierPayableReturnGuard guard;
    private final JdbcSupplierAdjustmentCompletions completions;

    /** 原成功来源由银行仓储核对，持久化不借用当前账户目录或原核销状态。 */
    public JdbcSupplierPaymentReturnsRepository(
            SupplierPaymentReturnsRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcSupplierPaymentOperationRepository payments,
            SupplierPayableReturnGuard guard,
            JdbcSupplierAdjustmentCompletions completions) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.payments = payments;
        this.guard = guard;
        this.completions = completions;
    }

    /** 首次只能保存空账本，并关联实际首次成功银行修订。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentReturns value) {
        if (!value.equals(SupplierPaymentReturns.open(value.request(), value.createdAt())))
            throw conflict();
        var request = value.request();
        var command = request.command();
        var original =
                payments.firstSuccessfulRevision(command.tenantId(), command.id())
                        .orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
        if (!new SupplierPaymentReturnPort.Request(original.command(), original.observation())
                .equals(request)) throw conflict();
        var source = command.holdCommand().authorization().source().reservation().source();
        var content = source.round().content();
        sqlMapper.create(
                command.tenantId(),
                command.id().toString(),
                original.version(),
                source.requestId().toString(),
                content.legalEntityId().toString(),
                content.supplierReference(),
                content.payableReference(),
                json.write(request),
                json.write(value),
                timestamp(value.createdAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 普通查询只可以要求核对，不能追加资金或解除已有退回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentReturns requireReview(String tenant, UUID id, Instant at) {
        var before = locked(tenant, id); var next = before.requireReview(at);
        if (!before.equals(next)) persist(before, next);
        return next;
    }

    /** 当前原件按租户和原付款读取，不返回其他申请的同名应付。 */
    public Optional<SupplierPaymentReturns> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 登记与历史读取都复核原账本修订的身份。 */
    public SupplierPaymentReturns revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierPaymentReturns.class);
                            if (!value.request().command().tenantId().equals(tenant)
                                    || !value.request().command().id().equals(id)
                                    || value.version() != version) throw conflict();
                            requireAccounting(value);
                            return value;
                        })
                .stream()
                .findFirst()
                .orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
    }

    SupplierPaymentReturns locked(String tenant, UUID id) {
        var source =
                find(tenant, id)
                        .orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict)
                        .request()
                        .command()
                        .holdCommand()
                        .authorization()
                        .source()
                        .reservation()
                        .source();
        guard.lock(tenant, source.round().content());
        return SqlRows.map(sqlMapper.locked(tenant, id.toString()), row()).stream()
                .findFirst()
                .orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
    }

    // 只有同包的登记仓储在保存具名决定的事务中调用，公开仓储没有任意账本更新入口。
    SupplierPaymentReturns register(SupplierPaymentReturns before, SupplierPaymentReturn decision) {
        var next = before.register(decision); persist(before, next); return next;
    }

    // 完成表引用后继修订，当前账本又引用完成表；先追加修订，再由同事务保存证明并更新当前行。
    void stageAccounting(SupplierAdjustmentCompletion proof) {
        var before = proof.before(); var bank = before.request().command();
        if (!locked(bank.tenantId(), bank.id()).equals(before)) throw conflict();
        append(proof.after());
    }

    // 只供同包原子完成编排调用，普通查询和登记不能直接改变已记账引用。
    void completeAccounting(SupplierAdjustmentCompletion proof) {
        var bank = proof.before().request().command();
        var after = proof.after();
        var accounted = after.accounting();
        if (!completions
                .find(bank.tenantId(), accounted.operationId())
                .filter(proof::equals)
                .isPresent()) throw conflict();
        int changed =
                sqlMapper.completeAccounting(
                        json.write(after),
                        after.version(),
                        after.reviewRequired(),
                        timestamp(after.updatedAt()),
                        accounted.operationId().toString(),
                        accounted.operationVersion(),
                        accounted.entryCount(),
                        timestamp(accounted.accountedAt()),
                        bank.tenantId(),
                        bank.id().toString(),
                        proof.before().version(),
                        json.write(proof.before().request()),
                        json.write(proof.before()));
        if (changed != 1) throw conflict();
    }

    /** 完成时读到的更高修订也属于已知原件，后续登记不得仅比较较早的查询记录。 */
    public List<SupplierPaymentReturnPort.Receipt> accountingReceipts(SupplierPaymentReturns ledger) {
        if (ledger.accounting() == null) return List.of();
        requireAccounting(ledger); var bank = ledger.request().command();
        return completions.history(bank.tenantId(), bank.id()).stream().map(SupplierAdjustmentCompletion::receipt).toList();
    }

    private void persist(SupplierPaymentReturns before, SupplierPaymentReturns value) {
        var command = before.request().command();
        int changed =
                sqlMapper.persist(
                        json.write(value),
                        value.version(),
                        value.reviewRequired(),
                        timestamp(value.updatedAt()),
                        command.tenantId(),
                        command.id().toString(),
                        before.version(),
                        json.write(before.request()),
                        json.write(before));
        if (changed != 1) throw conflict();
        append(value);
    }

    private Function<SqlRow, SupplierPaymentReturns> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentReturns.class);
            var request = value.request();
            var command = request.command();
            var source = command.holdCommand().authorization().source().reservation().source();
            var content = source.round().content();
            if (!command.tenantId().equals(row.getString("tenant_id"))
                    || !command.id().toString().equals(row.getString("payment_id"))
                    || !source.requestId().toString().equals(row.getString("request_id"))
                    || !content.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || !content.supplierReference().equals(row.getString("supplier_reference"))
                    || !content.payableReference().equals(row.getString("payable_reference"))
                    || !request.equals(
                            json.read(
                                    row.getString("input_json"),
                                    SupplierPaymentReturnPort.Request.class))
                    || value.version() != row.getLong("version")
                    || value.reviewRequired() != row.getBoolean("review_required")
                    || !time(value.createdAt()).equals(row.getTimestamp("created_at").toInstant())
                    || !time(value.updatedAt()).equals(row.getTimestamp("updated_at").toInstant()))
                throw conflict();
            var original =
                    payments.revision(
                                    command.tenantId(),
                                    command.id(),
                                    row.getLong("payment_version"))
                            .orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
            if (!original.settleable()
                    || !original.command().equals(command)
                    || !original.observation().equals(request.original())) throw conflict();
            requireAccountingHeaders(row, value);
            requireAccounting(value);
            return value;
        };
    }

    private void requireAccounting(SupplierPaymentReturns ledger) {
        var accounted = ledger.accounting(); if (accounted == null) return;
        var proof = completions.find(ledger.request().command().tenantId(), accounted.operationId()).orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
        var after = proof.after();
        if (!ledger.request().equals(after.request()) || !accounted.equals(after.accounting()) || ledger.version() < after.version()
                || ledger.updatedAt().isBefore(after.updatedAt()) || ledger.entries().size() < after.entries().size()
                || !after.entries().equals(ledger.entries().subList(0, after.entries().size()))) throw conflict();
    }

    private static void requireAccountingHeaders(SqlRow row, SupplierPaymentReturns ledger) {
        var accounted = ledger.accounting();
        // V80 非空升级核验会读取旧表；没有记账列的旧表只允许恢复没有记账引用的原记录。
        if (accounted == null && !hasAccountingColumn(row)) return;
        if (!Objects.equals(accounted == null ? null : accounted.operationId().toString(), row.getString("accounting_id"))
                || !Objects.equals(accounted == null ? null : accounted.operationVersion(), row.getObject("accounting_version", Long.class))
                || !Objects.equals(accounted == null ? null : accounted.entryCount(), row.getObject("accounted_entry_count", Integer.class))
                || !Objects.equals(accounted == null ? null : time(accounted.accountedAt()), row.getTimestamp("accounted_at") == null ? null : row.getTimestamp("accounted_at").toInstant())) throw conflict();
    }

    private static boolean hasAccountingColumn(SqlRow row) {
        return row.containsKey("accounting_id");
    }

    private void append(SupplierPaymentReturns value) {
        var command = value.request().command();
        sqlMapper.append(
                command.tenantId(), command.id().toString(), value.version(), json.write(value));
    }

    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(time(value)); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier return ledger or original bank source changed"); }
}
