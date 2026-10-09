package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.mapper.PaymentPayeeReviewRepositoryMapper;
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
 * 原付款的账户复核及修订单独保存，绝不修改原批准账户或原授权条款。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentPayeeReviewRepository {
    private final PaymentPayeeReviewRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 仓储只保存事实，来源和实际财务权限由持有原业务锁的应用服务校验。 */
    public JdbcPaymentPayeeReviewRepository(
            PaymentPayeeReviewRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 原授权及凭证的修订必须已经存在，复核登记和审计同事务完成。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentPayeeReview value) {
        if (value.version() != 1
                || value.status() != PaymentPayeeReview.Status.QUEUED
                || value.attempts() != 0) throw conflict();
        var input = value.input();
        var original = input.original();
        sqlMapper.create(
                original.tenantId(),
                input.id().toString(),
                original.id().toString(),
                input.authorizationVersion(),
                original.voucherOperationId().toString(),
                input.voucherVersion(),
                input.requestedBy(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()),
                DiagnosticContext.capture().traceId());
        append(value);
    }

    /** 输入不可更新，迟到读取及重复消费通过原版本比较更新拒绝。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentPayeeReview value) {
        var input = value.input();
        int changed =
                sqlMapper.update(
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        timestamp(value.updatedAt()),
                        timestamp(value.leaseUntil()),
                        timestamp(value.checkedAt()),
                        timestamp(value.account() == null ? null : value.account().validUntil()),
                        text(value.consumedAuthorizationId()),
                        value.issue() == null ? null : value.issue().name(),
                        json.write(value),
                        input.original().tenantId(),
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 查询必须使用当前认证租户或持久队列中的租户，不能从复核编号推断权限。 */
    public Optional<PaymentPayeeReview> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 新授权只允许使用该财务最后一次明确请求的复核，不复用已被新读取替代的结果。 */
    public Optional<PaymentPayeeReview> latest(
            String tenant, UUID originalAuthorizationId, String finance) {
        return SqlRows.map(
                        sqlMapper.latest(tenant, originalAuthorizationId.toString(), finance),
                        row())
                .stream()
                .findFirst();
    }

    /** 换账户授权在所有后续执行检查中都必须找到原子消费的复核事实。 */
    public Optional<PaymentPayeeReview> forAuthorization(String tenant, UUID authorizationId) {
        return SqlRows.map(sqlMapper.forAuthorization(tenant, authorizationId.toString()), row())
                .stream()
                .findFirst();
    }

    /** 每批最多十个只读请求；失败记录不会自动无限重试外部账户。 */
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

    private Function<SqlRow, PaymentPayeeReview> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), PaymentPayeeReview.class);
            var input = value.input();
            var original = input.original();
            if (!input.equals(
                            json.read(row.getString("input_json"), PaymentPayeeReview.Input.class))
                    || !original.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !original.id().toString().equals(row.getString("original_authorization_id"))
                    || input.authorizationVersion() != row.getLong("original_authorization_version")
                    || !original.voucherOperationId()
                            .toString()
                            .equals(row.getString("voucher_operation_id"))
                    || input.voucherVersion() != row.getLong("voucher_version")
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(value.checkedAt(), instant(row.getTimestamp("checked_at")))
                    || !Objects.equals(
                            value.account() == null ? null : value.account().validUntil(),
                            instant(row.getTimestamp("valid_until")))
                    || !Objects.equals(
                            text(value.consumedAuthorizationId()),
                            row.getString("consumed_authorization_id"))
                    || !Objects.equals(
                            value.issue() == null ? null : value.issue().name(),
                            row.getString("issue"))) {
                throw new IllegalStateException("Persisted payee review identity is inconsistent");
            }
            return value;
        };
    }

    private void append(PaymentPayeeReview value) {
        sqlMapper.append(
                value.input().original().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static String text(UUID value) { return value == null ? null : value.toString(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original payee review input or version changed"); }

    /**
     * 后台候选仅包含标识，不提前加载或输出账户快照。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 没有原业务事实的历史调用保留空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }
}
