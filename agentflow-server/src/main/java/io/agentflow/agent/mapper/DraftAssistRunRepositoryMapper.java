package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcDraftAssistRunRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DraftAssistRunRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Long applicationVersion,
            @Param("requestedBy") String requestedBy);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("previous") String previous,
            @Param("contextJson") String contextJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 active 所需的持久化事实。 */
    List<Long> active(@Param("tenant") String tenant, @Param("applicationId") String applicationId);

    /** 读取 lease 所需的持久化事实。 */
    List<SqlRow> lease(@Param("tenant") String tenant, @Param("id") String id);

    /** 更新 lease 所需的持久化事实。 */
    int lease2(
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenant") String tenant,
            @Param("id") String id);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("applicationId") String applicationId,
            @Param("size") Integer size,
            @Param("offset") Long offset);

    /** 读取 page 所需的持久化事实。 */
    List<Long> page2(@Param("tenant") String tenant, @Param("applicationId") String applicationId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId,
            @Param("runVersion") Long runVersion,
            @Param("status") String status,
            @Param("stateJson") String stateJson);
}
