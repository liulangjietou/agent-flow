package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcInboxRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface InboxRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("recipient") String recipient,
            @Param("eventKey") String eventKey,
            @Param("applicationId") String applicationId,
            @Param("title") String title,
            @Param("businessNo") String businessNo,
            @Param("kind") String kind,
            @Param("actor") String actor,
            @Param("taskId") String taskId,
            @Param("nodeName") String nodeName,
            @Param("roundNo") Integer roundNo,
            @Param("createdAt") Timestamp createdAt,
            @Param("content") String content,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("recipientId") String recipientId,
            @Param("expectedEventKey") String expectedEventKey);

    /** 读取 unreadCount 所需的持久化事实。 */
    List<Long> unreadCount(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("id") String id);

    /** 更新 saveRead 所需的持久化事实。 */
    int saveRead(
            @Param("readAt") Timestamp readAt,
            @Param("tenantId") String tenantId,
            @Param("recipient") String recipient,
            @Param("id") String id);

    /** 读取 saveRead 所需的持久化事实。 */
    List<SqlRow> saveRead2(
            @Param("tenantId") String tenantId,
            @Param("recipient") String recipient,
            @Param("id") String id);

    /** 按 list 的筛选条件执行数据库查询。 */
    List<SqlRow> listQuery(
            @Param("unreadOnlySelected") boolean unreadOnlySelected,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
