package io.agentflow.expense;


import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseArchiveRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 档案与原件引用原子插入；没有覆盖已封存 JSON 或物理删除原件的写入方法。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseArchiveRepository {
    private final ExpenseArchiveRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 使用原报销事务，清单摘要按实际保存的 UTF-8 字节计算。 */
    public JdbcExpenseArchiveRepository(ExpenseArchiveRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 每次读取核对独立身份列和摘要，不能将损坏的 JSON 当作可下载档案。 */
    public Optional<Entry> find(String tenant, UUID report, int round) {
        return SqlRows.map(
                        sqlMapper.find(tenant, report.toString(), round),
                        row -> {
                            String encoded = row.getString("manifest_json");
                            String digest = row.getString("manifest_sha256");
                            var archive =
                                    encoded == null
                                            ? null
                                            : json.read(encoded, ExpenseArchive.class);
                            if (archive != null) {
                                var source = archive.manifest().source();
                                if (!sha256(encoded).equals(digest)
                                        || !source.tenantId().equals(tenant)
                                        || !source.businessId().equals(report)
                                        || source.roundNo() != round
                                        || archive.manifest().settlement().version()
                                                != row.getLong("settlement_version")
                                        || !archive.archivedAt()
                                                .equals(
                                                        row.getTimestamp("archived_at")
                                                                .toInstant()))
                                    throw new IllegalStateException(
                                            "Persisted expense archive identity or digest is"
                                                + " inconsistent");
                            }
                            return new Entry(archive, encoded, digest, row.getString("issue"));
                        })
                .stream()
                .findFirst();
    }

    /** 调用方已持有报销锁；重复检查只在原因变化时写入，完成后永不降级。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void blocked(ExpenseSettlement settlement, String issue, Instant at) {
        var source = settlement.input().source();
        var old = find(source.tenantId(), source.businessId(), source.roundNo()).orElse(null);
        if (old != null && (old.archive() != null || issue.equals(old.issue()))) return;
        if (old == null)
            sqlMapper.blocked(
                    source.tenantId(),
                    source.businessId().toString(),
                    source.roundNo(),
                    settlement.version(),
                    issue,
                    Timestamp.from(at));
        else
            sqlMapper.blocked2(
                    issue,
                    settlement.version(),
                    Timestamp.from(at),
                    source.tenantId(),
                    source.businessId().toString(),
                    source.roundNo());
    }

    /** 档案、原件留存引用与唯一封存审计同事务完成，失败时不留下部分清单。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seal(ExpenseArchive archive) {
        var source = archive.manifest().source();
        var old = find(source.tenantId(), source.businessId(), source.roundNo()).orElse(null);
        if (old != null && old.archive() != null) return;
        String encoded = json.write(archive), digest = sha256(encoded);
        var at = Timestamp.from(archive.archivedAt());
        if (old == null)
            sqlMapper.seal(
                    source.tenantId(),
                    source.businessId().toString(),
                    source.roundNo(),
                    archive.manifest().settlement().version(),
                    at,
                    at,
                    encoded,
                    digest);
        else
            sqlMapper.seal2(
                    archive.manifest().settlement().version(),
                    at,
                    at,
                    encoded,
                    digest,
                    source.tenantId(),
                    source.businessId().toString(),
                    source.roundNo());
        for (var original : archive.manifest().originals()) {
            int inserted =
                    sqlMapper.seal3(
                            source.businessId().toString(),
                            source.roundNo(),
                            source.tenantId(),
                            original.file().id().toString(),
                            original.file().invoiceId().toString(),
                            source.employeeId(),
                            original.file().sha256());
            if (inserted != 1) throw new IllegalStateException("Archive original binding changed");
        }
        sqlMapper.seal4(
                UUID.randomUUID().toString(),
                source.tenantId(),
                UUID.randomUUID().toString(),
                source.businessId().toString(),
                source.applicationId().toString(),
                json.write(
                        java.util.Map.of(
                                "roundNo",
                                source.roundNo(),
                                "manifestSha256",
                                digest,
                                "settlementVersion",
                                archive.manifest().settlement().version())),
                at);
    }

    /** 固定游标轮转，前页缺原件或凭证的单据不会饿死后续可归档报销。 */
    public List<Candidate> candidates(Candidate after) {

        var args = new java.util.ArrayList<Object>();
        if (after != null) {
            args.addAll(List.of(after.tenantId(), after.tenantId(), after.reportId().toString()));
        }
        return SqlRows.map(
                sqlMapper.candidatesQuery((after != null), args.toArray()),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("report_id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 摘要校验和下载使用同一份保存字节，不重新序列化历史档案。 */
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /**
     * 尚未封存时仅包含阻塞原因，不向调用方伪造清单。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Entry(ExpenseArchive archive, String encoded, String sha256, String issue) {}

    /**
     * 后台扫描身份不携带财务原文。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID reportId,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public Candidate(String tenantId, UUID reportId, String traceId) { this(tenantId, reportId, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID reportId) { this(tenantId, reportId, null); }
    }
}
