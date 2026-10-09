package io.agentflow.servicetask.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcServiceTaskOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ServiceTaskOperationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("processInstanceId") Object processInstanceId,
            @Param("executionId") Object executionId,
            @Param("nodeId") Object nodeId,
            @Param("operationKey") Object operationKey,
            @Param("operationVersion") Object operationVersion,
            @Param("contractDigest") String contractDigest,
            @Param("targetDigest") String targetDigest,
            @Param("commandDigest") String commandDigest,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("pollAt") Timestamp pollAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("pollAt") Timestamp pollAt,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("digest") String digest,
            @Param("targetDigest") String targetDigest);

    /** 更新 postpone 所需的持久化事实。 */
    int postpone(
            @Param("pollAt") Timestamp pollAt,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 更新 markProgress 所需的持久化事实。 */
    int markProgress(
            @Param("progress") String progress,
            @Param("progressedAt") Timestamp progressedAt,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<SqlRow> lock(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 atWait 所需的持久化事实。 */
    List<SqlRow> atWait(
            @Param("tenant") String tenant,
            @Param("processInstanceId") String processInstanceId,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("pollAt") Timestamp pollAt);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") Object tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 执行 forRound 的条件查询。 */
    List<SqlRow> forRoundQuery(@Param("parameters") Object[] parameters);

    /** 执行 forRound 的条件查询。 */
    List<SqlRow> forRoundQuery2(@Param("parameters") Object[] parameters);
}
