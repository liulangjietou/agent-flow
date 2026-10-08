package io.agentflow.approval.copy;


import io.agentflow.approval.copy.mapper.CopyRecipientRepositoryMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 只追加收件事实；唯一键防止重复投递，不从当前目录补写历史成员。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcCopyRecipientRepository {
    private final CopyRecipientRepositoryMapper sqlMapper;

    /** 使用原审批事务的数据源。 */
    public JdbcCopyRecipientRepository(CopyRecipientRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 原节点已成功投递时不重新解析目录，避免重放把后来加入的人员补进旧轮次。 */
    public boolean delivered(
            String tenant, UUID application, int round, String instance, String node) {
        return Boolean.TRUE.equals(
                SqlRows.single(
                        sqlMapper.delivered(
                                tenant, application.toString(), round, instance, node)));
    }

    /** 返回本次是否首次保存；原收件事实不允许更新。 */
    public boolean append(CopyRecipient value) {
        return sqlMapper.append(
                        value.tenantId(),
                        value.applicationId().toString(),
                        value.roundNo(),
                        value.processInstanceId(),
                        value.nodeId(),
                        value.nodeName(),
                        value.recipient(),
                        value.rule(),
                        value.directoryRevision(),
                        Timestamp.from(value.createdAt()),
                        value.tenantId(),
                        value.applicationId().toString(),
                        value.roundNo(),
                        value.nodeId(),
                        value.recipient())
                == 1;
    }

    /** 按当前主体和明确轮次读取节点授权，跨租户及跨轮次没有回退分支。 */
    public List<CopyRecipient> find(String tenant, UUID application, int round, String recipient) {
        return SqlRows.map(
                sqlMapper.find(tenant, application.toString(), round, recipient),
                row ->
                        new CopyRecipient(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("application_id")),
                                row.getInt("round_no"),
                                row.getString("process_instance_id"),
                                row.getString("node_id"),
                                row.getString("node_name"),
                                row.getString("recipient_id"),
                                row.getString("recipient_rule"),
                                row.getLong("directory_revision"),
                                row.getTimestamp("created_at").toInstant()));
    }
}
