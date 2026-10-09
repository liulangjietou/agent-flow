package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcAssistRunRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AssistRunRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("status") String status,
            @Param("contextJson") String contextJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Long applicationVersion,
            @Param("roundNo") Integer roundNo);

    /** 读取 update 所需的持久化事实。 */
    List<String> update(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("applicationVersion") Long applicationVersion,
            @Param("roundNo") Integer roundNo);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId, @Param("runId") String runId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId,
            @Param("runVersion") Long runVersion,
            @Param("status") String status,
            @Param("stateJson") String stateJson);

    /** 执行 update 的条件查询。 */
    int updateQuery(@Param("parameters") Object[] parameters);
}
