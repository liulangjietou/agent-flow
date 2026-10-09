package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePrecheckRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePrecheckRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("applicationVersion") Long applicationVersion,
            @Param("financialVersion") Long financialVersion,
            @Param("attemptNo") Long attemptNo,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("activeReportId") String activeReportId,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("stateJson") String stateJson,
            @Param("reportId") String reportId,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("completedAt") Timestamp completedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("inputJson") String inputJson,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latestAttempt 所需的持久化事实。 */
    List<Long> latestAttempt(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 读取 latestId 所需的持久化事实。 */
    List<String> latestId(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 读取 active 所需的持久化事实。 */
    List<String> active(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery2(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);
}
