package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.PaymentExecutionRequestRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 原授权唯一出纳选择与读取租约，READY 必须指向同授权的实际付款操作。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentExecutionRequestRepository {
    private final PaymentExecutionRequestRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcPaymentAuthorizationRepository authorizations;

    /** 选择与幂等结果同事务落库，账户网络查询由后台执行。 */
    public JdbcPaymentExecutionRequestRepository(
            PaymentExecutionRequestRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcPaymentAuthorizationRepository authorizations) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.authorizations = authorizations;
    }

    /** 只允许当前尚未执行授权登记选择，数据库唯一键防止两个出纳占用同一授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentExecutionRequest value) {
        var input = value.input();
        var authorization =
                authorizations
                        .find(input.tenantId(), input.authorizationId())
                        .orElseThrow(JdbcPaymentExecutionRequestRepository::conflict);
        if (!PaymentExecutionRequest.queue(
                        input.id(),
                        authorization,
                        input.cashier(),
                        input.debitReference(),
                        input.debitVersion(),
                        value.createdAt())
                .equals(value)) throw conflict();
        sqlMapper.create(
                input.tenantId(),
                input.id().toString(),
                input.authorizationId().toString(),
                input.authorizationVersion(),
                input.cashier(),
                json.write(input),
                json.write(value),
                timestamp(value.createdAt()),
                timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()),
                DiagnosticContext.capture().traceId());
        append(value);
    }

    /** 比较原输入与版本，不能用重试更换出纳、账户或授权，也不能覆盖较新的领取。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentExecutionRequest value) {
        var input = value.input();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        operationId(value),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        input.tenantId(),
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 请求编号只能在原租户内定位。 */
    public Optional<PaymentExecutionRequest> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 原授权最多一份执行选择，失败重读不会产生另一个出纳意图。 */
    public Optional<PaymentExecutionRequest> forAuthorization(String tenant, UUID authorizationId) {
        return SqlRows.map(sqlMapper.forAuthorization(tenant, authorizationId.toString()), row())
                .stream()
                .findFirst();
    }

    /** 每批扫描最多十条，持久租约过期后仍按同一选择恢复。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, PaymentExecutionRequest> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), PaymentExecutionRequest.class);
            var input = value.input();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    PaymentExecutionRequest.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.authorizationId().toString().equals(row.getString("authorization_id"))
                    || input.authorizationVersion() != row.getLong("authorization_version")
                    || !input.cashier().equals(row.getString("cashier_id"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || !Objects.equals(operationId(value), row.getString("operation_id"))
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until"))))
                throw new IllegalStateException(
                        "Persisted payment execution request identity is inconsistent");
            return value;
        };
    }

    private void append(PaymentExecutionRequest value) {
        sqlMapper.append(
                value.input().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static String operationId(PaymentExecutionRequest value) { return value.status() == PaymentExecutionRequest.Status.READY ? value.input().authorizationId().toString() : null; }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment execution request input or version changed"); }

    /**
     * 调度标识不返回付款内容。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 没有原业务事实的历史调用保留空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }
}
