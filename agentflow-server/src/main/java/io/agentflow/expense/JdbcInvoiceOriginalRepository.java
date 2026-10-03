package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 原件元数据与个人配额，所有查询先按租户定位；文件发布状态不改写原件身份。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInvoiceOriginalRepository {
    private static final RowMapper<InvoiceOriginal> ROW = (row, index) -> new InvoiceOriginal(UUID.fromString(row.getString("id")),
            UUID.fromString(row.getString("invoice_id")), row.getString("tenant_id"), row.getString("owner_id"), row.getString("filename"),
            row.getLong("byte_size"), row.getString("sha256"), InvoiceOriginal.Format.valueOf(row.getString("format")),
            row.getTimestamp("created_at").toInstant(), InvoiceOriginal.Status.valueOf(row.getString("status")));
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 与发票创建、幂等结果共用外层事务。 */
    public JdbcInvoiceOriginalRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 首次并发建票夹时让冲突事务回滚重试，不在 PostgreSQL 失效事务中继续查询。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCapacity(String tenant, String owner, long bytes, long maximumBytes, int maximumUploads) {
        try {
            jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM invoice_wallet WHERE tenant_id=? AND owner_id=?)",
                    tenant, owner, tenant, owner);
        } catch (DuplicateKeyException concurrentInitialization) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Invoice wallet was initialized concurrently; retry the same request");
        }
        int updated = jdbc.update("UPDATE invoice_wallet SET used_bytes=used_bytes+?,upload_count=upload_count+1 WHERE tenant_id=? AND owner_id=? AND used_bytes<=? AND upload_count<?",
                bytes, tenant, owner, maximumBytes - bytes, maximumUploads);
        if (updated != 1) throw new DomainException("INVOICE_WALLET_QUOTA_EXCEEDED", "Invoice wallet upload capacity is exhausted");
    }

    /** 指纹、名称和归属只在登记时写入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(InvoiceOriginal original) {
        jdbc.update("INSERT INTO invoice_original(tenant_id,id,invoice_id,owner_id,filename,byte_size,sha256,format,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                original.tenantId(), original.id().toString(), original.invoiceId().toString(), original.ownerId(), original.filename(),
                original.size(), original.sha256(), original.format().name(), original.status().name(), Timestamp.from(original.createdAt()));
    }

    /** 读取原件不推断管理员具有他人票夹权限。 */
    public Optional<InvoiceOriginal> find(String tenant, UUID invoiceId) {
        return jdbc.query("SELECT * FROM invoice_original WHERE tenant_id=? AND invoice_id=?", ROW, tenant, invoiceId.toString()).stream().findFirst();
    }

    /** 预检一次取得所选原件的元数据，原件内容仍在事务外逐个校验。 */
    public java.util.Map<UUID, InvoiceOriginal> findAll(String tenant, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "SELECT * FROM invoice_original WHERE tenant_id=:tenant AND invoice_id IN (:ids)",
                java.util.Map.of("tenant", tenant, "ids", ids.stream().map(UUID::toString).toList()), ROW).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(InvoiceOriginal::invoiceId, value -> value));
    }

    /** 发布和状态更新前以发票标识串行化同一原件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceOriginal lock(String tenant, UUID invoiceId) {
        return jdbc.query("SELECT * FROM invoice_original WHERE tenant_id=? AND invoice_id=? FOR UPDATE", ROW, tenant, invoiceId.toString())
                .stream().findFirst().orElseThrow(() -> new DomainException("NOT_FOUND", "Invoice original not found"));
    }

    /** READY 是原件完成态，不能被晚到失败覆盖。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateStatus(InvoiceOriginal value) {
        jdbc.update("UPDATE invoice_original SET status=? WHERE tenant_id=? AND invoice_id=? AND status<>'READY'",
                value.status().name(), value.tenantId(), value.invoiceId().toString());
    }

    /** 使用固定数量和游标读取当前员工票夹，避免无界遍历全部财务资源。 */
    public List<Entry> list(String tenant, String owner, UUID before, int limit) {
        String sql = "SELECT o.*,r.state_json,r.version AS resource_version FROM invoice_original o JOIN finance_resource r "
                + "ON r.tenant_id=o.tenant_id AND r.resource_type=o.resource_type AND r.id=o.invoice_id "
                + "WHERE o.tenant_id=? AND o.owner_id=? " + (before == null ? "" : "AND o.invoice_id<? ") + "ORDER BY o.invoice_id DESC LIMIT ?";
        RowMapper<Entry> mapper = (row, index) -> {
            var original = ROW.mapRow(row, index);
            var invoice = Invoice.restore(json.read(row.getString("state_json"), Invoice.State.class));
            if (invoice.version() != row.getLong("resource_version") || !invoice.id().equals(original.invoiceId())
                    || !invoice.tenantId().equals(original.tenantId()) || !invoice.ownerId().equals(original.ownerId())
                    || !invoice.originalFileId().equals(original.id()) || !invoice.originalDigest().equals(original.sha256())) {
                throw new IllegalStateException("Persisted invoice original identity is inconsistent");
            }
            return new Entry(original, invoice);
        };
        return before == null ? jdbc.query(sql, mapper, tenant, owner, limit) : jdbc.query(sql, mapper, tenant, owner, before.toString(), limit);
    }

    /**
     * 单次数据库读取中的原件及发票状态，避免票夹列表逐条追加数据库查询。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(InvoiceOriginal original, Invoice invoice) { }
}
