package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.JdbcFinanceReceiptCreditRepository;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.procurement.mapper.SupplierPaymentReturnRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 回款决定、查询消费、累计修订和跨业务资金防重在同一事务保存。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentReturnRepository {
    private final SupplierPaymentReturnRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcSupplierPaymentReturnsRepository ledgers;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcFinanceReceiptCreditRepository funds;

    /** 不依赖 ERP 写入，未完成的账务调整与已登记银行资金分别保留。 */
    public JdbcSupplierPaymentReturnRepository(
            SupplierPaymentReturnRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcSupplierPaymentReturnsRepository ledgers,
            JdbcSupplierPaymentReturnCheckRepository checks,
            JdbcSupplierPaymentOperationRepository payments,
            JdbcFinanceReceiptCreditRepository funds) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.ledgers = ledgers;
        this.checks = checks;
        this.payments = payments;
        this.funds = funds;
    }

    /** 按原付款串行登记，任何后续原件、防重或审计失败均由调用方事务整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentReturns register(
            SupplierPaymentReturn decision, long returnVersion, long checkVersion) {
        var tenant = decision.tenantId();
        var id = decision.receipt().request().command().id();
        var before = ledgers.locked(tenant, id);
        var check =
                checks.find(tenant, decision.checkId())
                        .orElseThrow(JdbcSupplierPaymentReturnRepository::conflict);
        if (before.version() != returnVersion
                || !before.reviewRequired()
                || check.version() != checkVersion
                || !before.request().equals(check.input().request())
                || !checks.latest(tenant, id, decision.registeredBy())
                        .map(value -> value.input().id().equals(check.input().id()))
                        .orElse(false)) throw conflict();
        var resolved = check.resolve(decision, decision.registeredAt());
        var bank =
                payments.find(tenant, id)
                        .orElseThrow(JdbcSupplierPaymentReturnRepository::conflict);
        if (!decision.receipt().matchesCurrentBank(bank)
                || checks.history(tenant, id).stream()
                        .anyMatch(value -> !decision.receipt().continues(value.receipt()))
                || ledgers.accountingReceipts(before).stream()
                        .anyMatch(value -> !decision.receipt().continues(value))) throw conflict();
        var next = ledgers.register(before, decision);
        checks.resolve(check, decision);
        sqlMapper.register(
                tenant,
                decision.id().toString(),
                id.toString(),
                before.version(),
                next.version(),
                decision.checkId().toString(),
                resolved.version(),
                decision.receipt().status().name(),
                decision.registeredBy(),
                timestamp(decision.receipt().observedAt()),
                timestamp(decision.registeredAt()),
                json.write(decision));
        for (var entry : next.entries())
            if (entry.registrationId().equals(decision.id())) funds.record(decision, entry);
        return next;
    }

    /** 原消息只按实际决定编号恢复，不扫描历史或替换成最近一次登记。 */
    public Optional<Registered> find(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.find(tenant, id.toString()),
                        row ->
                                new Registered(
                                        row.getLong("return_version"),
                                        restore(
                                                row,
                                                tenant,
                                                UUID.fromString(row.getString("payment_id")))))
                .stream()
                .findFirst();
    }

    /** 恢复决定时回放原查询和账本修订，不以当前银行状态改写历史登记。 */
    public List<SupplierPaymentReturn> history(String tenant, UUID paymentId) {
        return SqlRows.map(
                sqlMapper.history(tenant, paymentId.toString()),
                row -> restore(row, tenant, paymentId));
    }

    /** 当前办理页面只取有界历史，游标使用同一原付款单调递增的账本版本。 */
    public List<Registered> page(String tenant, UUID paymentId, Long beforeVersion, int limit) {

        var parameters =
                beforeVersion == null
                        ? new Object[] {tenant, paymentId.toString(), limit}
                        : new Object[] {tenant, paymentId.toString(), beforeVersion, limit};
        return SqlRows.map(
                sqlMapper.pageQuery(beforeVersion == null, parameters),
                row ->
                        new Registered(
                                row.getLong("return_version"), restore(row, tenant, paymentId)));
    }

    private SupplierPaymentReturn restore(SqlRow row, String tenant, UUID paymentId) {
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
    }

    /**
     * 账本版本用于稳定分页，实际决定继续引用原不可变查询。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Registered(long returnVersion, SupplierPaymentReturn decision) {}

    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }

    private static Timestamp timestamp(Instant at) { return Timestamp.from(time(at)); }

    private static DomainException conflict() {
        return new DomainException(
                "CONCURRENCY_CONFLICT",
                "Supplier return decision requires current original bank and complete cumulative"
                    + " evidence");
    }
}
