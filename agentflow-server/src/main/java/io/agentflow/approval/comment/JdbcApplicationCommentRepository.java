package io.agentflow.approval.comment;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评论查询始终限定租户与申请，分页在数据库完成，不扫描其他申请的正文。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationCommentRepository implements ApplicationCommentRepository {
    private final JdbcTemplate jdbc;

    /** 使用平台数据源读取评论。 */
    public JdbcApplicationCommentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void append(String tenantId, ApplicationComment comment) {
        int inserted = jdbc.update("""
                INSERT INTO application_comment
                  (id,tenant_id,application_id,author_id,content,round_no,application_version,application_status,created_at)
                SELECT ?,tenant_id,id,?,?,round_no,version,status,? FROM approval_application
                WHERE tenant_id=? AND id=? AND version=? AND status=? AND round_no=?
                """, comment.id().toString(), comment.author(), comment.content(), java.sql.Timestamp.from(comment.createdAt()),
                tenantId, comment.applicationId().toString(), comment.applicationVersion(), comment.applicationStatus().name(), comment.roundNo());
        if (inserted != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Application context changed before comment was recorded");
    }

    @Override
    public List<ApplicationComment> list(String tenantId, UUID applicationId, Query query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM application_comment WHERE tenant_id=? AND application_id=?");
        var arguments = new ArrayList<Object>(List.of(tenantId, applicationId.toString()));
        if (query.roundNo() != null) { sql.append(" AND round_no=?"); arguments.add(query.roundNo()); }
        if (query.beforeTime() != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND id<?))");
            var time = java.sql.Timestamp.from(query.beforeTime());
            arguments.add(time); arguments.add(time); arguments.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        arguments.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new ApplicationComment(UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("application_id")), row.getString("author_id"), row.getString("content"),
                row.getInt("round_no"), row.getLong("application_version"),
                ApplicationStatus.valueOf(row.getString("application_status")), row.getTimestamp("created_at").toInstant()), arguments.toArray());
    }
}
