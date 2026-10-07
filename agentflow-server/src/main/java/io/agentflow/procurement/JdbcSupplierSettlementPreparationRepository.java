package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算准备保留实际到账修订、财务与日期；READY 必须对应同事务登记的原结算命令。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierSettlementPreparationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPaymentOperationRepository payments;

    /** 原银行修订由银行仓储核验，输入不能自行宣告到账。 */
    public JdbcSupplierSettlementPreparationRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPaymentOperationRepository payments) {
        this.jdbc = jdbc; this.json = json; this.payments = payments;
    }

    /** 上层持有原申请锁，当前准备和已登记结算共同阻止并行变更记账日期。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierSettlementPreparation value) {
        var input = value.input(); var bank = input.payment(); var tenant = bank.command().tenantId(); var paymentId = bank.command().id();
        if (!bank.equals(payments.find(tenant, paymentId).orElseThrow(JdbcSupplierSettlementPreparationRepository::conflict))
                || !bank.equals(payments.revision(tenant, paymentId, bank.version()).orElseThrow(JdbcSupplierSettlementPreparationRepository::conflict))
                || !value.equals(SupplierSettlementPreparation.queue(input.id(), bank, input.financeActor(), input.accountingDate(), input.requestedAt()))) throw conflict();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payable_settlement_operation WHERE tenant_id=? AND active_payment_id=?", Integer.class, tenant, paymentId.toString()) != 0) throw occupied();
        try {
            jdbc.update("""
                    INSERT INTO supplier_settlement_preparation(trace_id,tenant_id,id,payment_id,payment_version,finance_actor,accounting_date,input_json,state_json,
                    version,status,attempts,created_at,updated_at,next_attempt_at,active_payment_id) VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?,?)
                    """, DiagnosticContext.capture().traceId(), tenant, input.id().toString(), paymentId.toString(), bank.version(), input.financeActor(), Date.valueOf(input.accountingDate()), json.write(input), json.write(value),
                    timestamp(input.requestedAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), paymentId.toString());
        } catch (DuplicateKeyException duplicate) { throw occupied(); }
        append(value);
    }

    /** 不可变输入及连续版本参加更新，终止准备不能被迟到读取重开。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierSettlementPreparation value) {
        requireRegistration(value); var input = value.input();
        int changed = jdbc.update("""
                UPDATE supplier_settlement_preparation SET state_json=?,version=?,status=?,attempts=?,updated_at=?,next_attempt_at=?,lease_until=?,
                active_payment_id=?,registered_operation_id=? WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND status IN ('QUEUED','RUNNING')
                """, json.write(value), value.version(), value.status().name(), value.attempts(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()),
                owner(value), registered(value), input.payment().command().tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }

    /** 以租户和原准备编号恢复，不根据页面传来的银行正文恢复来源。 */
    public Optional<SupplierSettlementPreparation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_settlement_preparation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 读取中的意图独占原银行；登记后由实际结算命令接管独占。 */
    public Optional<SupplierSettlementPreparation> active(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_settlement_preparation WHERE tenant_id=? AND active_payment_id=?", this::restore, tenant, paymentId.toString()).stream().findFirst();
    }

    /** 页面可以查看最近准备的终止原因，历史不会被新日期覆盖。 */
    public Optional<SupplierSettlementPreparation> latest(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_settlement_preparation WHERE tenant_id=? AND payment_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                this::restore, tenant, paymentId.toString()).stream().findFirst();
    }

    /** 原结算引用当时实际领取版本，后续 READY 不替换这份依据。 */
    public Optional<SupplierSettlementPreparation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_settlement_preparation_revision WHERE tenant_id=? AND preparation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierSettlementPreparation.class);
            if (!value.input().payment().command().tenantId().equals(tenant) || !value.input().id().equals(id) || value.version() != version) throw conflict(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 有界扫描只暴露领取标识，旧租约恢复仍沿用原输入。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id,trace_id FROM supplier_settlement_preparation WHERE (status='QUEUED' AND next_attempt_at<=?)
                OR (status='RUNNING' AND lease_until<=?) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), timestamp(now), timestamp(now));
    }

    private SupplierSettlementPreparation restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), SupplierSettlementPreparation.class); var input = value.input(); var bank = input.payment();
        if (!input.equals(json.read(row.getString("input_json"), SupplierSettlementPreparation.Input.class)) || !bank.command().tenantId().equals(row.getString("tenant_id"))
                || !input.id().toString().equals(row.getString("id")) || !bank.command().id().toString().equals(row.getString("payment_id"))
                || bank.version() != row.getLong("payment_version") || !input.financeActor().equals(row.getString("finance_actor")) || !input.accountingDate().equals(row.getDate("accounting_date").toLocalDate())
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || !input.requestedAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                || !Objects.equals(owner(value), row.getString("active_payment_id")) || !Objects.equals(registered(value), row.getString("registered_operation_id"))) throw inconsistent();
        if (!payments.revision(bank.command().tenantId(), bank.command().id(), bank.version()).filter(bank::equals).isPresent()) throw conflict();
        requireRegistration(value); return value;
    }
    private void requireRegistration(SupplierSettlementPreparation value) {
        if (value.status() != SupplierSettlementPreparation.Status.READY) return;
        var input = value.input(); var tenant = input.payment().command().tenantId();
        var commands = jdbc.query("SELECT command_json FROM supplier_payable_settlement_operation WHERE tenant_id=? AND id=? AND preparation_version=?",
                (row, index) -> json.read(row.getString("command_json"), SupplierPayableSettlementCommand.class), tenant, input.id().toString(), value.version() - 1);
        var previous = revision(tenant, input.id(), value.version() - 1).orElseThrow(JdbcSupplierSettlementPreparationRepository::conflict);
        if (commands.size() != 1 || !previous.ready(commands.get(0), value.updatedAt()).equals(value)) throw conflict();
    }
    private void append(SupplierSettlementPreparation value) {
        jdbc.update("INSERT INTO supplier_settlement_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,?,?)",
                value.input().payment().command().tenantId(), value.input().id().toString(), value.version(), json.write(value));
    }
    private static String owner(SupplierSettlementPreparation value) { return value.active() ? value.input().payment().command().id().toString() : null; }
    private static String registered(SupplierSettlementPreparation value) { return value.status() == SupplierSettlementPreparation.Status.READY ? value.input().id().toString() : null; }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted supplier settlement preparation is inconsistent"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier settlement preparation or original paid source changed"); }
    private static DomainException occupied() { return new DomainException("SUPPLIER_SETTLEMENT_PENDING", "Original bank payment already has an active settlement intent or command"); }

    /**
     * 后台不在扫描结果展开原付款资料。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) {
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
