package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcBudgetOccupationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BudgetOccupationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("targetDigest") String targetDigest,
            @Param("pendingOperationId") String pendingOperationId,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("employeeId") String employeeId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("pendingOperationId") String pendingOperationId,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("employeeId") String employeeId,
            @Param("targetDigest") String targetDigest,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
