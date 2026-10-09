package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.procurement.mapper.SupplierPayableReviewRepositoryMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久应付复核保存原读取意图和单次消费身份，字段级授权之外不暴露完整快照。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPayableReviewRepository {
    private final SupplierPayableReviewRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final ApprovedSupplierPaymentSources sources;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;

    /** 复核与授权使用同一批准来源及数据库事务。 */
    public JdbcSupplierPayableReviewRepository(
            SupplierPayableReviewRepositoryMapper sqlMapper,
            JsonUtil json,
            ApprovedSupplierPaymentSources sources,
            JdbcSupplierPaymentAuthorizationRepository authorizations) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.sources = sources;
        this.authorizations = authorizations;
    }

    /** 同一财务在同一申请上最多有一条未完成读取，不凭空保存可授权结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPayableReview value) {
        var input = value.input();
        var approved = input.source();
        var reservation = approved.reservation();
        var source = reservation.source();
        if (!SupplierPayableReview.queue(
                                input.id(), approved, input.requestedBy(), input.requestedAt())
                        .equals(value)
                || !approved.equals(sources.derive(source.tenantId(), source.requestId())))
            throw conflict();
        try {
            sqlMapper.create(
                    DiagnosticContext.capture().traceId(),
                    source.tenantId(),
                    input.id().toString(),
                    source.requestId().toString(),
                    source.applicationId().toString(),
                    source.employeeId(),
                    source.round().roundNo(),
                    approved.approval().applicationVersion(),
                    approved.approvedRequestVersion(),
                    reservation.id().toString(),
                    input.requestedBy(),
                    timestamp(input.requestedAt()),
                    json.write(input),
                    json.write(value),
                    timestamp(value.updatedAt()),
                    source.requestId().toString());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "SUPPLIER_PAYABLE_REVIEW_PENDING",
                    "The current finance actor already has a pending payable review");
        }
        append(value);
    }

    /** 领取及消费比较原输入和前版，消费另对照同事务建立的精确授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPayableReview value) {
        requireConsumption(value);
        var input = value.input();
        var tenant = input.source().reservation().source().tenantId();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        timestamp(value.updatedAt()),
                        timestamp(value.leaseUntil()),
                        timestamp(value.checkedAt()),
                        activeRequest(value),
                        value.consumedAuthorizationId() == null
                                ? null
                                : value.consumedAuthorizationId().toString(),
                        tenant,
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 原租户和读取编号共同限定结果，不向其他财务自动共享尚未授权的复核材料。 */
    public Optional<SupplierPayableReview> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                .findFirst();
    }

    /** 工作区默认展示当前财务最近一次读取，读取历史保留原编号。 */
    public Optional<SupplierPayableReview> latest(String tenant, UUID requestId, String finance) {
        return SqlRows.map(sqlMapper.latest(tenant, requestId.toString(), finance), this::restore)
                .stream()
                .findFirst();
    }

    /** 已消费结果用于审计原授权采用了哪次 ERP 依据，不改变原授权内容。 */
    public Optional<SupplierPayableReview> forAuthorization(String tenant, UUID authorizationId) {
        return SqlRows.map(
                        sqlMapper.forAuthorization(tenant, authorizationId.toString()),
                        this::restore)
                .stream()
                .findFirst();
    }

    /** 单批最多十条原标识，只有只读队列和过期读取租约参与恢复。 */
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

    private SupplierPayableReview restore(SqlRow row) {
        var value = json.read(row.getString("state_json"), SupplierPayableReview.class); var input = value.input(); var approved = input.source(); var source = approved.reservation().source();
        if (!input.equals(json.read(row.getString("input_json"), SupplierPayableReview.Input.class)) || !source.tenantId().equals(row.getString("tenant_id"))
                || !input.id().toString().equals(row.getString("id")) || !source.requestId().toString().equals(row.getString("request_id"))
                || !source.applicationId().toString().equals(row.getString("application_id")) || !source.employeeId().equals(row.getString("employee_id"))
                || source.round().roundNo() != row.getInt("round_no") || approved.approval().applicationVersion() != row.getLong("application_version")
                || approved.approvedRequestVersion() != row.getLong("request_version") || !approved.reservation().id().toString().equals(row.getString("reservation_id"))
                || !input.requestedBy().equals(row.getString("requested_by")) || !input.requestedAt().equals(instant(row.getTimestamp("requested_at")))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || !value.updatedAt().equals(instant(row.getTimestamp("updated_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                || !Objects.equals(value.checkedAt(), instant(row.getTimestamp("checked_at"))) || !Objects.equals(activeRequest(value), row.getString("active_request_id"))
                || !Objects.equals(value.consumedAuthorizationId() == null ? null : value.consumedAuthorizationId().toString(), row.getString("consumed_authorization_id"))) {
            throw new IllegalStateException("Persisted supplier payable review identity is inconsistent");
        }
        requireConsumption(value); return value;
    }

    private void requireConsumption(SupplierPayableReview value) {
        if (value.status() == SupplierPayableReview.Status.CONSUMED) {
            var authorization = authorizations.find(value.input().source().reservation().source().tenantId(), value.consumedAuthorizationId()).orElseThrow(JdbcSupplierPayableReviewRepository::conflict);
            if (!value.supports(authorization)) throw conflict();
        }
    }

    private void append(SupplierPayableReview value) {
        sqlMapper.append(
                value.input().source().reservation().source().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static String activeRequest(SupplierPayableReview value) { return value.active() ? value.input().source().reservation().source().requestId().toString() : null; }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier payable review input, version or authorization consumption changed"); }

    /**
     * 后台候选只包含租户与原读取标识。
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
