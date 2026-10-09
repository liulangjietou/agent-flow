package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcAssistJobRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AssistJobRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId,
            @Param("taskId") String taskId,
            @Param("requesterJson") String requesterJson,
            @Param("sourcesJson") String sourcesJson,
            @Param("targetDigest") String targetDigest,
            @Param("traceId") String traceId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("runId") String runId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenant") String tenant, @Param("runId") String runId);

    /** 更新 lease 所需的持久化事实。 */
    int lease(
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenant") String tenant,
            @Param("runId") String runId);
}
