package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.InvoiceOriginalRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 原件元数据与个人配额，所有查询先按租户定位；文件发布状态不改写原件身份。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInvoiceOriginalRepository {
    private static final Function<SqlRow, InvoiceOriginal> ROW =
            row ->
                    new InvoiceOriginal(
                            UUID.fromString(row.getString("id")),
                            UUID.fromString(row.getString("invoice_id")),
                            row.getString("tenant_id"),
                            row.getString("owner_id"),
                            row.getString("filename"),
                            row.getLong("byte_size"),
                            row.getString("sha256"),
                            InvoiceOriginal.Format.valueOf(row.getString("format")),
                            row.getTimestamp("created_at").toInstant(),
                            InvoiceOriginal.Status.valueOf(row.getString("status")));
    private final InvoiceOriginalRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 与发票创建、幂等结果共用外层事务。 */
    public JdbcInvoiceOriginalRepository(InvoiceOriginalRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 首次并发建票夹时让冲突事务回滚重试，不在 PostgreSQL 失效事务中继续查询。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCapacity(
            String tenant, String owner, long bytes, long maximumBytes, int maximumUploads) {
        try {
            sqlMapper.reserveCapacity(tenant, owner, tenant, owner);
        } catch (DuplicateKeyException concurrentInitialization) {
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Invoice wallet was initialized concurrently; retry the same request");
        }
        int updated =
                sqlMapper.reserveCapacity2(
                        bytes, tenant, owner, maximumBytes - bytes, maximumUploads);
        if (updated != 1)
            throw new DomainException(
                    "INVOICE_WALLET_QUOTA_EXCEEDED", "Invoice wallet upload capacity is exhausted");
    }

    /** 指纹、名称和归属只在登记时写入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(InvoiceOriginal original) {
        sqlMapper.create(
                original.tenantId(),
                original.id().toString(),
                original.invoiceId().toString(),
                original.ownerId(),
                original.filename(),
                original.size(),
                original.sha256(),
                original.format().name(),
                original.status().name(),
                Timestamp.from(original.createdAt()));
    }

    /** 读取原件不推断管理员具有他人票夹权限。 */
    public Optional<InvoiceOriginal> find(String tenant, UUID invoiceId) {
        return SqlRows.map(sqlMapper.find(tenant, invoiceId.toString()), ROW).stream().findFirst();
    }

    /** 预检一次取得所选原件的元数据，原件内容仍在事务外逐个校验。 */
    public java.util.Map<UUID, InvoiceOriginal> findAll(
            String tenant, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        return SqlRows.map(
                        sqlMapper.findAll(
                                java.util.Map.of(
                                        "tenant",
                                        tenant,
                                        "ids",
                                        ids.stream().map(UUID::toString).toList())),
                        ROW)
                .stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                InvoiceOriginal::invoiceId, value -> value));
    }

    /** 发布和状态更新前以发票标识串行化同一原件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceOriginal lock(String tenant, UUID invoiceId) {
        return SqlRows.map(sqlMapper.lock(tenant, invoiceId.toString()), ROW).stream()
                .findFirst()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Invoice original not found"));
    }

    /** READY 是原件完成态，不能被晚到失败覆盖。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateStatus(InvoiceOriginal value) {
        sqlMapper.updateStatus(
                value.status().name(), value.tenantId(), value.invoiceId().toString());
    }

    /** 使用固定数量和游标读取当前员工票夹，避免无界遍历全部财务资源。 */
    public List<Entry> list(String tenant, String owner, UUID before, int limit) {

        Function<SqlRow, Entry> mapper =
                row -> {
                    var original = ROW.apply(row);
                    var invoice =
                            Invoice.restore(
                                    json.read(row.getString("state_json"), Invoice.State.class));
                    if (invoice.version() != row.getLong("resource_version")
                            || !invoice.id().equals(original.invoiceId())
                            || !invoice.tenantId().equals(original.tenantId())
                            || !invoice.ownerId().equals(original.ownerId())
                            || !invoice.originalFileId().equals(original.id())
                            || !invoice.originalDigest().equals(original.sha256())) {
                        throw new IllegalStateException(
                                "Persisted invoice original identity is inconsistent");
                    }
                    return new Entry(original, invoice);
                };
        return before == null
                ? SqlRows.map(
                        sqlMapper.listQuery(before == null, new Object[] {tenant, owner, limit}),
                        mapper)
                : SqlRows.map(
                        sqlMapper.listQuery2(
                                before == null,
                                new Object[] {tenant, owner, before.toString(), limit}),
                        mapper);
    }

    /**
     * 单次数据库读取中的原件及发票状态，避免票夹列表逐条追加数据库查询。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Entry(InvoiceOriginal original, Invoice invoice) {}
}
