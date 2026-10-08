package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseArchiveRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseArchiveRepositoryMapper {
    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("report") String report,
            @Param("round") Integer round);

    /** 新增 blocked 所需的持久化事实。 */
    int blocked(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("settlementVersion") Long settlementVersion,
            @Param("issue") String issue,
            @Param("checkedAt") Timestamp checkedAt);

    /** 更新 blocked 所需的持久化事实。 */
    int blocked2(
            @Param("issue") String issue,
            @Param("version") Long version,
            @Param("checkedAt") Timestamp checkedAt,
            @Param("tenantId") String tenantId,
            @Param("businessId") String businessId,
            @Param("roundNo") Integer roundNo);

    /** 新增 seal 所需的持久化事实。 */
    int seal(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("settlementVersion") Long settlementVersion,
            @Param("checkedAt") Timestamp checkedAt,
            @Param("archivedAt") Timestamp archivedAt,
            @Param("manifestJson") String manifestJson,
            @Param("manifestSha256") String manifestSha256);

    /** 更新 seal 所需的持久化事实。 */
    int seal2(
            @Param("version") Long version,
            @Param("at") Timestamp at,
            @Param("archivedAt") Timestamp archivedAt,
            @Param("encoded") String encoded,
            @Param("digest") String digest,
            @Param("tenantId") String tenantId,
            @Param("businessId") String businessId,
            @Param("roundNo") Integer roundNo);

    /** 新增 seal 所需的持久化事实。 */
    int seal3(
            @Param("businessId") String businessId,
            @Param("roundNo") Integer roundNo,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("invoiceId") String invoiceId,
            @Param("employeeId") String employeeId,
            @Param("sha256") String sha256);

    /** 新增 seal 所需的持久化事实。 */
    int seal4(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("applicationId") String applicationId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt);

    /** 按 candidates 的筛选条件执行数据库查询。 */
    List<SqlRow> candidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
