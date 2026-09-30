package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回款决定、查询消费、累计修订和跨业务资金防重在同一事务保存。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentReturnRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPaymentReturnsRepository ledgers;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcFinanceReceiptCreditRepository funds;

    /** 不依赖 ERP 写入，未完成的账务调整与已登记银行资金分别保留。 */
    public JdbcSupplierPaymentReturnRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPaymentReturnsRepository ledgers,
            JdbcSupplierPaymentReturnCheckRepository checks, JdbcSupplierPaymentOperationRepository payments, JdbcFinanceReceiptCreditRepository funds) {
        this.jdbc = jdbc; this.json = json; this.ledgers = ledgers; this.checks = checks; this.payments = payments; this.funds = funds;
    }

    /** 按原付款串行登记，任何后续原件、防重或审计失败均由调用方事务整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentReturns register(SupplierPaymentReturn decision, long returnVersion, long checkVersion) {
        var tenant = decision.tenantId(); var id = decision.receipt().request().command().id();
        var before = ledgers.locked(tenant, id);
        var check = checks.find(tenant, decision.checkId()).orElseThrow(JdbcSupplierPaymentReturnRepository::conflict);
        if (before.version() != returnVersion || !before.reviewRequired() || check.version() != checkVersion
                || !before.request().equals(check.input().request())
                || !checks.latest(tenant, id, decision.registeredBy()).map(value -> value.input().id().equals(check.input().id())).orElse(false)) throw conflict();
        var resolved = check.resolve(decision, decision.registeredAt());
        var bank = payments.find(tenant, id).orElseThrow(JdbcSupplierPaymentReturnRepository::conflict);
        if (!bank.settleable() && bank.status() != SupplierPaymentOperation.Status.REVERSED || bank.conflictingObservation() != null
                || !bank.command().equals(before.request().command()) || !decision.receipt().samePaymentFacts(bank.observation())
                || bank.observation().revision() > decision.receipt().current().revision()
                || bank.observation().observedAt().isAfter(decision.receipt().current().observedAt())
                || checks.history(tenant, id).stream().anyMatch(value -> !decision.receipt().continues(value.receipt()))) throw conflict();
        var next = ledgers.register(before, decision); checks.resolve(check, decision);
        jdbc.update("""
                INSERT INTO supplier_payment_return_registration(tenant_id,id,payment_id,before_version,return_version,check_id,check_version,outcome,registered_by,observed_at,registered_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant, decision.id().toString(), id.toString(), before.version(), next.version(), decision.checkId().toString(), resolved.version(),
                decision.receipt().status().name(), decision.registeredBy(), timestamp(decision.receipt().observedAt()), timestamp(decision.registeredAt()), json.write(decision));
        for (var entry : next.entries()) if (entry.registrationId().equals(decision.id())) funds.record(decision, entry);
        return next;
    }

    /** 恢复决定时回放原查询和账本修订，不以当前银行状态改写历史登记。 */
    public List<SupplierPaymentReturn> history(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_payment_return_registration WHERE tenant_id=? AND payment_id=? ORDER BY return_version", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentReturn.class);
            if (!value.tenantId().equals(tenant) || !value.id().toString().equals(row.getString("id"))
                    || !value.receipt().request().command().id().equals(paymentId) || !value.checkId().toString().equals(row.getString("check_id"))
                    || !value.registeredBy().equals(row.getString("registered_by")) || !value.receipt().status().name().equals(row.getString("outcome"))
                    || !time(value.receipt().observedAt()).equals(row.getTimestamp("observed_at").toInstant())
                    || !time(value.registeredAt()).equals(row.getTimestamp("registered_at").toInstant())) throw conflict();
            var before = ledgers.revision(tenant, paymentId, row.getLong("before_version"));
            var after = ledgers.revision(tenant, paymentId, row.getLong("return_version"));
            var priorCheck = checks.revision(tenant, value.checkId(), row.getLong("check_version") - 1);
            var resolved = checks.revision(tenant, value.checkId(), row.getLong("check_version"));
            if (!before.register(value).equals(after) || !priorCheck.resolve(value, value.registeredAt()).equals(resolved)) throw conflict();
            return value;
        }, tenant, paymentId.toString());
    }

    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant at) { return Timestamp.from(time(at)); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier return decision requires current original bank and complete cumulative evidence"); }
}
