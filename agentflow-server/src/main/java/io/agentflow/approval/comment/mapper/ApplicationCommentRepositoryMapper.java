package io.agentflow.approval.comment.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcApplicationCommentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApplicationCommentRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("id") String id,
            @Param("author") String author,
            @Param("content") String content,
            @Param("createdAt") Object createdAt,
            @Param("mentionsJson") String mentionsJson,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Long applicationVersion,
            @Param("applicationStatus") String applicationStatus,
            @Param("roundNo") Integer roundNo);

    /** 按 list 的筛选条件执行数据库查询。 */
    List<SqlRow> listQuery(
            @Param("hasRoundNo") boolean hasRoundNo,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
