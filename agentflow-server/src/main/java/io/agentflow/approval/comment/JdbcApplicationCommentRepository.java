package io.agentflow.approval.comment;


import com.fasterxml.jackson.core.type.TypeReference;

import io.agentflow.approval.comment.mapper.ApplicationCommentRepositoryMapper;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评论查询始终限定租户与申请，分页在数据库完成，不扫描其他申请的正文。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationCommentRepository implements ApplicationCommentRepository {
    private final ApplicationCommentRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 使用平台数据源读取评论。 */
    public JdbcApplicationCommentRepository(
            ApplicationCommentRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    public void append(String tenantId, ApplicationComment comment) {
        int inserted =
                sqlMapper.append(
                        comment.id().toString(),
                        comment.author(),
                        comment.content(),
                        java.sql.Timestamp.from(comment.createdAt()),
                        json.write(comment.mentions()),
                        tenantId,
                        comment.applicationId().toString(),
                        comment.applicationVersion(),
                        comment.applicationStatus().name(),
                        comment.roundNo());
        if (inserted != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Application context changed before comment was recorded");
    }

    @Override
    public List<ApplicationComment> list(String tenantId, UUID applicationId, Query query) {

        var arguments = new ArrayList<Object>(List.of(tenantId, applicationId.toString()));
        if (query.roundNo() != null) {
            arguments.add(query.roundNo());
        }
        if (query.beforeTime() != null) {

            var time = java.sql.Timestamp.from(query.beforeTime());
            arguments.add(time);
            arguments.add(time);
            arguments.add(query.beforeId().toString());
        }

        arguments.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.listQuery(
                        (query.roundNo() != null),
                        (query.beforeTime() != null),
                        arguments.toArray()),
                row ->
                        new ApplicationComment(
                                UUID.fromString(row.getString("id")),
                                UUID.fromString(row.getString("application_id")),
                                row.getString("author_id"),
                                row.getString("content"),
                                row.getInt("round_no"),
                                row.getLong("application_version"),
                                ApplicationStatus.valueOf(row.getString("application_status")),
                                row.getTimestamp("created_at").toInstant(),
                                json.read(
                                        row.getString("mentions_json"),
                                        new TypeReference<List<String>>() {})));
    }
}
