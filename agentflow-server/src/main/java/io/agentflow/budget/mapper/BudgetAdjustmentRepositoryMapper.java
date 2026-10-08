package io.agentflow.budget.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcBudgetAdjustmentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BudgetAdjustmentRepositoryMapper {
    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock2(@Param("tenant") String tenant, @Param("id") Object id);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock3(@Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("businessId") String businessId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("requestId") String requestId,
            @Param("requestVersion") Long requestVersion,
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("stateJson") String stateJson);
}
