package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回款账本引用原成功银行修订，冻结和具名登记之外不允许直接修改累计资金。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentReturnsRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final SupplierPayableReturnGuard guard;

    /** 原成功来源由银行仓储核对，持久化不借用当前账户目录或原核销状态。 */
    public JdbcSupplierPaymentReturnsRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPaymentOperationRepository payments, SupplierPayableReturnGuard guard) {
        this.jdbc = jdbc; this.json = json; this.payments = payments; this.guard = guard;
    }

    /** 首次只能保存空账本，并关联实际首次成功银行修订。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentReturns value) {
        if (!value.equals(SupplierPaymentReturns.open(value.request(), value.createdAt()))) throw conflict();
        var request = value.request(); var command = request.command();
        var original = payments.firstSuccessfulRevision(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
        if (!new SupplierPaymentReturnPort.Request(original.command(), original.observation()).equals(request)) throw conflict();
        var source = command.holdCommand().authorization().source().reservation().source(); var content = source.round().content();
        jdbc.update("""
                INSERT INTO supplier_payment_returns(tenant_id,payment_id,payment_version,request_id,legal_entity_id,supplier_reference,payable_reference,input_json,state_json,version,review_required,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,1,FALSE,?,?)
                """, command.tenantId(), command.id().toString(), original.version(), source.requestId().toString(), content.legalEntityId().toString(),
                content.supplierReference(), content.payableReference(), json.write(request), json.write(value), timestamp(value.createdAt()), timestamp(value.updatedAt()));
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
        return jdbc.query("SELECT * FROM supplier_payment_returns WHERE tenant_id=? AND payment_id=?", row(), tenant, id.toString()).stream().findFirst();
    }

    /** 登记与历史读取都复核原账本修订的身份。 */
    public SupplierPaymentReturns revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_payment_returns_revision WHERE tenant_id=? AND payment_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentReturns.class);
            if (!value.request().command().tenantId().equals(tenant) || !value.request().command().id().equals(id) || value.version() != version) throw conflict();
            return value;
        }, tenant, id.toString(), version).stream().findFirst().orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
    }

    SupplierPaymentReturns locked(String tenant, UUID id) {
        var source = find(tenant, id).orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict).request().command()
                .holdCommand().authorization().source().reservation().source();
        guard.lock(tenant, source.round().content());
        return jdbc.query("SELECT * FROM supplier_payment_returns WHERE tenant_id=? AND payment_id=? FOR UPDATE", row(), tenant, id.toString()).stream()
                .findFirst().orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
    }

    // 只有同包的登记仓储在保存具名决定的事务中调用，公开仓储没有任意账本更新入口。
    SupplierPaymentReturns register(SupplierPaymentReturns before, SupplierPaymentReturn decision) {
        var next = before.register(decision); persist(before, next); return next;
    }

    private void persist(SupplierPaymentReturns before, SupplierPaymentReturns value) {
        var command = before.request().command();
        int changed = jdbc.update("""
                UPDATE supplier_payment_returns SET state_json=?,version=?,review_required=?,updated_at=?
                WHERE tenant_id=? AND payment_id=? AND version=? AND input_json=? AND state_json=?
                """, json.write(value), value.version(), value.reviewRequired(), timestamp(value.updatedAt()), command.tenantId(), command.id().toString(),
                before.version(), json.write(before.request()), json.write(before));
        if (changed != 1) throw conflict(); append(value);
    }

    private RowMapper<SupplierPaymentReturns> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentReturns.class); var request = value.request(); var command = request.command();
            var source = command.holdCommand().authorization().source().reservation().source(); var content = source.round().content();
            if (!command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("payment_id"))
                    || !source.requestId().toString().equals(row.getString("request_id")) || !content.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || !content.supplierReference().equals(row.getString("supplier_reference")) || !content.payableReference().equals(row.getString("payable_reference"))
                    || !request.equals(json.read(row.getString("input_json"), SupplierPaymentReturnPort.Request.class))
                    || value.version() != row.getLong("version") || value.reviewRequired() != row.getBoolean("review_required")
                    || !time(value.createdAt()).equals(row.getTimestamp("created_at").toInstant()) || !time(value.updatedAt()).equals(row.getTimestamp("updated_at").toInstant())) throw conflict();
            var original = payments.revision(command.tenantId(), command.id(), row.getLong("payment_version")).orElseThrow(JdbcSupplierPaymentReturnsRepository::conflict);
            if (!original.settleable() || !original.command().equals(command) || !original.observation().equals(request.original())) throw conflict();
            return value;
        };
    }
    private void append(SupplierPaymentReturns value) {
        var command = value.request().command();
        jdbc.update("INSERT INTO supplier_payment_returns_revision(tenant_id,payment_id,version,state_json) VALUES(?,?,?,?)", command.tenantId(), command.id().toString(), value.version(), json.write(value));
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(time(value)); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier return ledger or original bank source changed"); }
}
