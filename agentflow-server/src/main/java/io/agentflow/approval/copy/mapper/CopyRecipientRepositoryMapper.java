package io.agentflow.approval.copy.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcCopyRecipientRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface CopyRecipientRepositoryMapper {
    /** 读取 delivered 所需的持久化事实。 */
    List<Boolean> delivered(
            @Param("tenant") String tenant,
            @Param("application") String application,
            @Param("round") Integer round,
            @Param("instance") String instance,
            @Param("node") String node);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("processInstanceId") String processInstanceId,
            @Param("nodeId") String nodeId,
            @Param("nodeName") String nodeName,
            @Param("recipient") String recipient,
            @Param("rule") String rule,
            @Param("directoryRevision") Long directoryRevision,
            @Param("createdAt") Timestamp createdAt,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("expectedApplicationId") String expectedApplicationId,
            @Param("expectedRoundNo") Integer expectedRoundNo,
            @Param("expectedNodeId") String expectedNodeId,
            @Param("recipientId") String recipientId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("application") String application,
            @Param("round") Integer round,
            @Param("recipient") String recipient);
}
