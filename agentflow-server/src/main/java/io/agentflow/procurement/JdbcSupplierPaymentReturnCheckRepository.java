package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.procurement.mapper.SupplierPaymentReturnCheckRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 原供应商付款复核只读队列和不可变修订，同一原件的不同操作者观察均保留。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentReturnCheckRepository {
    private final SupplierPaymentReturnCheckRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 仓储只处理本地事实，不执行外部资金或会计操作。 */
    public JdbcSupplierPaymentReturnCheckRepository(
            SupplierPaymentReturnCheckRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 意图先加入原供应商事务，后台只处理已提交的原付款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentReturnCheck value) {
        if (value.version() != 1 || value.status() != SupplierPaymentReturnCheck.Status.QUEUED)
            throw conflict();
        var input = value.input();
        requireSource(input);
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                input.tenantId(),
                input.id().toString(),
                input.request().command().id().toString(),
                input.paymentVersion(),
                input.requestedBy(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 版本和原输入共同约束，迟到任务不能替换当前租约或二次登记。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPaymentReturnCheck value) {
        var before = find(value.input().tenantId(), value.input().id()).orElseThrow(JdbcSupplierPaymentReturnCheckRepository::conflict);
        var expected = switch (value.status()) {
            case RUNNING -> before.claim(value.updatedAt(), Duration.between(value.updatedAt(), value.leaseUntil()));
            case CHECKED -> before.complete(new io.agentflow.finance.FinanceResult.Success<>(value.receipt()), value.updatedAt());
            case UNAVAILABLE -> before.fail(value.issue(), value.updatedAt());
            case VOIDED -> before.voidSource(value.updatedAt());
            default -> throw conflict();
        };
        if (!expected.equals(value)) throw conflict();
        persist(value);
    }

    // 明确登记的消费与具名决定共享事务，普通任务更新不能制造已登记状态。
    void resolve(SupplierPaymentReturnCheck before, SupplierPaymentReturn decision) {
        persist(before.resolve(decision, decision.registeredAt()));
    }

    private void persist(SupplierPaymentReturnCheck value) {
        var input = value.input();
        int changed =
                sqlMapper.persist(
                        value.version(),
                        value.status().name(),
                        json.write(value),
                        timestamp(value.updatedAt()),
                        timestamp(value.leaseUntil()),
                        value.active() ? Boolean.TRUE : null,
                        input.tenantId(),
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 按租户定位精确查询，不从编号推断业务授权。 */
    public Optional<SupplierPaymentReturnCheck> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 财务只办理自己对这笔原供应商付款最新明确发起的复核。 */
    public Optional<SupplierPaymentReturnCheck> latest(
            String tenant, UUID paymentId, String actor) {
        return SqlRows.map(sqlMapper.latest(tenant, paymentId.toString(), actor), row()).stream()
                .findFirst();
    }

    /** 不同财务的已完成观察共同约束最高外部版本和既有资金事实。 */
    public List<SupplierPaymentReturnCheck> history(String tenant, UUID paymentId) {
        return SqlRows.map(sqlMapper.history(tenant, paymentId.toString()), row());
    }

    /** 财务登记必须能回放精确的查询消费前后修订。 */
    public SupplierPaymentReturnCheck revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierPaymentReturnCheck.class);
                            if (!value.input().tenantId().equals(tenant)
                                    || !value.input().id().equals(id)
                                    || value.version() != version) throw conflict();
                            return value;
                        })
                .stream()
                .findFirst()
                .orElseThrow(JdbcSupplierPaymentReturnCheckRepository::conflict);
    }

    /** 每次只领取有限数量，并由应用服务处理超时旧租约。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, SupplierPaymentReturnCheck> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentReturnCheck.class);
            var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.request()
                            .command()
                            .id()
                            .toString()
                            .equals(row.getString("payment_id"))
                    || input.paymentVersion() != row.getLong("payment_version")
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || !input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    SupplierPaymentReturnCheck.Input.class))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || !time(input.requestedAt()).equals(instant(row.getTimestamp("created_at")))
                    || !time(value.updatedAt()).equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            time(value.leaseUntil()), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(
                            value.active() ? Boolean.TRUE : null, row.getObject("active_marker")))
                throw new IllegalStateException(
                        "Persisted supplier payment return identity is inconsistent");
            requireSource(input);
            return value;
        };
    }

    private void requireSource(SupplierPaymentReturnCheck.Input input) {
        var matches =
                SqlRows.map(
                        sqlMapper.requireSource(
                                input.tenantId(), input.request().command().id().toString()),
                        row ->
                                row.getLong("payment_version") == input.paymentVersion()
                                        && input.request()
                                                .equals(
                                                        json.read(
                                                                row.getString("input_json"),
                                                                SupplierPaymentReturnPort.Request
                                                                        .class)));
        if (matches.size() != 1 || !matches.get(0)) throw conflict();
    }

    private void append(SupplierPaymentReturnCheck value) {
        sqlMapper.append(
                value.input().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(time(at)); }

    private static Instant time(Instant at) { return at == null ? null : at.truncatedTo(ChronoUnit.MICROS); }

    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier payment return version or original input changed"); }

    /**
     * 扫描只携带租户与任务标识。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务关联不存在时保持空值，不借用当前审批轮次或工作线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
