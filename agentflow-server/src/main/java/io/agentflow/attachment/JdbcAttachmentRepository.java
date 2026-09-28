package io.agentflow.attachment;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 文件元数据与轮次引用仓储，所有读写同时绑定租户和申请。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAttachmentRepository {
    private final JdbcTemplate jdbc;
    /** 注入本用例共享的持久化依赖。 */
    public JdbcAttachmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 与申请状态写入共用行锁，避免上传完成和提交在不同状态下交叉通过。 */
    public void lockApplication(String tenant, UUID application) {
        if (jdbc.queryForList("SELECT id FROM approval_application WHERE tenant_id=? AND id=? FOR UPDATE", tenant, application.toString()).isEmpty()) {
            throw new DomainException("NOT_FOUND", "Application not found");
        }
    }

    /** 限额统计包含未完成和已移除引用的上传，防止通过失败重试绕过持久存储上限。 */
    public void requireCapacity(String tenant, UUID application, long size, long maxBytes, int maxUploads) {
        var totals = jdbc.queryForMap("SELECT COUNT(*) AS uploads, COALESCE(SUM(byte_size),0) AS bytes FROM approval_attachment WHERE tenant_id=? AND application_id=?", tenant, application.toString());
        if (((Number) totals.get("uploads")).longValue() >= maxUploads || ((Number) totals.get("bytes")).longValue() > maxBytes - size) {
            throw new DomainException("ATTACHMENT_QUOTA_EXCEEDED", "Application attachment storage limit reached");
        }
    }

    /** 登记固定内容身份，上传不能更换所属字段或摘要。 */
    public void insert(Attachment file) {
        jdbc.update("INSERT INTO approval_attachment (id,tenant_id,application_id,field_path,filename,byte_size,sha256,created_by,created_at,status) VALUES (?,?,?,?,?,?,?,?,?,?)",
                file.id().toString(), file.tenantId(), file.applicationId().toString(), file.fieldPath(), file.filename(), file.size(), file.sha256(),
                file.createdBy(), OffsetDateTime.ofInstant(file.createdAt(), ZoneOffset.UTC), file.status().name());
    }

    /** 先由调用方验证申请访问权限，再读取该申请内的附件。 */
    public Attachment get(String tenant, UUID application, UUID id) {
        return jdbc.query("SELECT * FROM approval_attachment WHERE tenant_id=? AND application_id=? AND id=?", (row, index) ->
                new Attachment(UUID.fromString(row.getString("id")), row.getString("tenant_id"), UUID.fromString(row.getString("application_id")),
                        row.getString("field_path"), row.getString("filename"), row.getLong("byte_size"), row.getString("sha256"),
                        row.getString("created_by"), row.getObject("created_at", OffsetDateTime.class).toInstant(), Attachment.Status.valueOf(row.getString("status"))),
                tenant, application.toString(), id.toString()).stream().findFirst().orElseThrow(() -> new DomainException("NOT_FOUND", "Attachment not found"));
    }

    /** 完成后不可降级；并发失败不能覆盖另一次成功上传。 */
    public void transition(Attachment file, Attachment.Status status) {
        jdbc.update("UPDATE approval_attachment SET status=? WHERE tenant_id=? AND application_id=? AND id=? AND status<>'READY'",
                status.name(), file.tenantId(), file.applicationId().toString(), file.id().toString());
    }

    /** 与申请提交处于同一事务，只追加真实轮次引用。 */
    public void freeze(Attachment file, int round) {
        jdbc.update("INSERT INTO approval_attachment_round (tenant_id,application_id,round_no,field_path,attachment_id) VALUES (?,?,?,?,?)",
                file.tenantId(), file.applicationId().toString(), round, file.fieldPath(), file.id().toString());
    }

    /** 历史下载必须有当轮引用，不能仅凭当前字段和猜测的文件标识获得内容。 */
    public boolean frozen(Attachment file, int round) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM approval_attachment_round WHERE tenant_id=? AND application_id=? AND round_no=? AND field_path=? AND attachment_id=?",
                Integer.class, file.tenantId(), file.applicationId().toString(), round, file.fieldPath(), file.id().toString()) == 1;
    }
}
