package io.agentflow.approval.copy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 只追加收件事实；唯一键防止重复投递，不从当前目录补写历史成员。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcCopyRecipientRepository {
    private final JdbcTemplate jdbc;
    /** 使用原审批事务的数据源。 */
    public JdbcCopyRecipientRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 原节点已成功投递时不重新解析目录，避免重放把后来加入的人员补进旧轮次。 */
    public boolean delivered(String tenant, UUID application, int round, String instance, String node) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_copy_recipient WHERE tenant_id=? AND application_id=? AND round_no=? AND process_instance_id=? AND node_id=?)",
                Boolean.class, tenant, application.toString(), round, instance, node));
    }

    /** 返回本次是否首次保存；原收件事实不允许更新。 */
    public boolean append(CopyRecipient value) {
        return jdbc.update("""
                INSERT INTO approval_copy_recipient
                (tenant_id,application_id,round_no,process_instance_id,node_id,node_name,recipient_id,recipient_rule,directory_revision,created_at)
                SELECT ?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS
                (SELECT 1 FROM approval_copy_recipient WHERE tenant_id=? AND application_id=? AND round_no=? AND node_id=? AND recipient_id=?)
                """, value.tenantId(), value.applicationId().toString(), value.roundNo(), value.processInstanceId(), value.nodeId(),
                value.nodeName(), value.recipient(), value.rule(), value.directoryRevision(), Timestamp.from(value.createdAt()),
                value.tenantId(), value.applicationId().toString(), value.roundNo(), value.nodeId(), value.recipient()) == 1;
    }

    /** 按当前主体和明确轮次读取节点授权，跨租户及跨轮次没有回退分支。 */
    public List<CopyRecipient> find(String tenant, UUID application, int round, String recipient) {
        return jdbc.query("SELECT * FROM approval_copy_recipient WHERE tenant_id=? AND application_id=? AND round_no=? AND recipient_id=? ORDER BY node_id",
                (row, index) -> new CopyRecipient(row.getString("tenant_id"), UUID.fromString(row.getString("application_id")),
                        row.getInt("round_no"), row.getString("process_instance_id"), row.getString("node_id"), row.getString("node_name"),
                        row.getString("recipient_id"), row.getString("recipient_rule"), row.getLong("directory_revision"), row.getTimestamp("created_at").toInstant()),
                tenant, application.toString(), round, recipient);
    }
}
