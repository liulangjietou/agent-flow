package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.InvoiceVerificationRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

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
import java.util.function.Function;

/**
 * 验票任务、活动互斥键及转换历史共用事务，输入只写一次。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInvoiceVerificationRepository {
    private final InvoiceVerificationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 不使用进程内队列，重启后从原数据库恢复待执行记录。 */
    public JdbcInvoiceVerificationRepository(
            InvoiceVerificationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 登记失败整体回滚，唯一键冲突后不在失效事务里继续读写。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(InvoiceVerificationJob job) {
        var input = job.input();
        if (job.status() != InvoiceVerificationJob.Status.QUEUED) throw conflict();
        try {
            sqlMapper.create(
                    input.tenantId(),
                    input.id().toString(),
                    input.invoiceId().toString(),
                    input.ownerId(),
                    input.originalId().toString(),
                    input.originalDigest(),
                    input.invoiceVersion(),
                    json.write(input),
                    json.write(job),
                    input.invoiceId().toString(),
                    Timestamp.from(job.createdAt()),
                    DiagnosticContext.capture().traceId());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "INVOICE_VERIFICATION_ACTIVE",
                    "Invoice already has an active verification job");
        }
        append(job);
    }

    /** 版本和不可变输入一并匹配，终态清除活动键并保留原租约。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(InvoiceVerificationJob job) {
        var input = job.input();
        int updated =
                sqlMapper.update(
                        job.version(),
                        job.status().name(),
                        json.write(job),
                        job.active() ? input.invoiceId().toString() : null,
                        timestamp(job.leaseUntil()),
                        timestamp(job.completedAt()),
                        input.tenantId(),
                        input.id().toString(),
                        json.write(input),
                        job.version() - 1);
        if (updated != 1) throw conflict();
        append(job);
    }

    /** 发票行锁阻止占用或其他财务转换与验票结果同时写入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInvoice(String tenant, UUID invoiceId) {
        if (sqlMapper.lockInvoice(tenant, invoiceId.toString()).isEmpty())
            throw new DomainException("NOT_FOUND", "Invoice not found");
    }

    /** 读取仍以独立租户列定位，并验证状态 JSON 与检索列一致。 */
    public Optional<InvoiceVerificationJob> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 由活动唯一键查询，调用方应先锁发票串行化排队与完成。 */
    public boolean active(String tenant, UUID invoiceId) {
        return activeId(tenant, invoiceId).isPresent();
    }

    /** 页面恢复直接读取活动唯一键，不把历史 UUID 分页当作执行顺序。 */
    public Optional<UUID> activeId(String tenant, UUID invoiceId) {
        return sqlMapper.activeId(tenant, invoiceId.toString()).stream()
                .findFirst()
                .map(UUID::fromString);
    }

    /** 当前版本必须恰由成功任务产生，且其后没有任何同版本的新尝试；不以时间戳猜最新结论。 */
    public java.util.Map<UUID, InvoiceVerificationJob> currentReceipts(
            String tenant, java.util.Collection<UUID> invoiceIds) {
        if (invoiceIds.isEmpty()) return java.util.Map.of();
        return SqlRows.map(
                        sqlMapper.currentReceipts(
                                java.util.Map.of(
                                        "tenant",
                                        tenant,
                                        "ids",
                                        invoiceIds.stream().map(UUID::toString).toList())),
                        row())
                .stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                value -> value.input().invoiceId(), value -> value));
    }

    /** 占用会增加发票版本；核销接受占用前成功验票，但后续任意新查验必须已成功。 */
    public java.util.Map<UUID, InvoiceVerificationJob> settlementReceipts(
            String tenant, java.util.Collection<UUID> invoiceIds) {
        if (invoiceIds.isEmpty()) return java.util.Map.of();
        return SqlRows.map(
                        sqlMapper.settlementReceipts(
                                java.util.Map.of(
                                        "tenant",
                                        tenant,
                                        "ids",
                                        invoiceIds.stream().map(UUID::toString).toList())),
                        row())
                .stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                value -> value.input().invoiceId(), value -> value));
    }

    /** 每次只扫描十项，过期运行用于结算超时而非再次发送。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(Timestamp.from(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id")));
    }

    /** 本人单张发票的有界历史使用固定游标顺序。 */
    public List<InvoiceVerificationJob> list(
            String tenant, UUID invoiceId, UUID before, int limit) {

        return before == null
                ? SqlRows.map(
                        sqlMapper.listQuery(
                                before == null, new Object[] {tenant, invoiceId.toString(), limit}),
                        row())
                : SqlRows.map(
                        sqlMapper.listQuery2(
                                before == null,
                                new Object[] {
                                    tenant, invoiceId.toString(), before.toString(), limit
                                }),
                        row());
    }

    private Function<SqlRow, InvoiceVerificationJob> row() {
        return row -> {
            var job = json.read(row.getString("state_json"), InvoiceVerificationJob.class);
            var input = job.input();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    InvoiceVerificationJob.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.invoiceId().toString().equals(row.getString("invoice_id"))
                    || !input.ownerId().equals(row.getString("owner_id"))
                    || !input.originalId().toString().equals(row.getString("original_id"))
                    || !input.originalDigest().equals(row.getString("original_digest"))
                    || input.invoiceVersion() != row.getLong("invoice_version")
                    || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status"))
                    || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(
                            job.active() ? input.invoiceId().toString() : null,
                            row.getString("active_invoice_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(
                            job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException(
                        "Persisted invoice verification identity is inconsistent");
            }
            return job;
        };
    }

    private void append(InvoiceVerificationJob job) {
        sqlMapper.append(
                job.input().tenantId(),
                job.input().id().toString(),
                job.version(),
                json.write(job));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Invoice verification version or input changed"); }

    /**
     * 扫描结果仅含定位标识，不带原件或敏感票面。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) {}
}
