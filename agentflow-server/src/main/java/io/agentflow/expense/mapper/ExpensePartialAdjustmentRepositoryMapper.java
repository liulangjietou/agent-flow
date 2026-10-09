package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePartialAdjustmentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePartialAdjustmentRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("sequenceNo") Long sequenceNo,
            @Param("settlementVersion") Object settlementVersion,
            @Param("consumptionId") String consumptionId,
            @Param("consumedVersion") Object consumedVersion,
            @Param("accrualId") String accrualId,
            @Param("accrualVersion") Object accrualVersion,
            @Param("previousId") String previousId,
            @Param("previousVersion") Object previousVersion,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("activeReportId") String activeReportId,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("reportId") String reportId,
            @Param("fundsIdentity") Object fundsIdentity,
            @Param("registrationId") String registrationId,
            @Param("entryJson") String entryJson,
            @Param("activeFundsIdentity") Object activeFundsIdentity);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("at") Timestamp at, @Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 completeResources 所需的持久化事实。 */
    List<SqlRow> completeResources(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 completeResources 所需的持久化事实。 */
    List<SqlRow> completeResources2(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 completeResources 所需的持久化事实。 */
    int completeResources3(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Long afterVersion,
            @Param("sourceJson") String sourceJson,
            @Param("completedAt") Timestamp completedAt);

    /** 更新 completeResources 所需的持久化事实。 */
    int completeResources4(
            @Param("updatedAt") Timestamp updatedAt,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 latestCompleted 所需的持久化事实。 */
    List<SqlRow> latestCompleted(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 claimedReturnIds 所需的持久化事实。 */
    List<String> claimedReturnIds(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 ready 所需的持久化事实。 */
    List<SqlRow> ready();

    /** 更新 save 所需的持久化事实。 */
    int save(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("activeReport") String activeReport,
            @Param("id") String id,
            @Param("budgetStatus") String budgetStatus,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("accrualOperationId") String accrualOperationId,
            @Param("accrualStatus") String accrualStatus,
            @Param("accrualNextAt") Timestamp accrualNextAt,
            @Param("accrualLeaseUntil") Timestamp accrualLeaseUntil,
            @Param("at") Timestamp at,
            @Param("sequence") Long sequence,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("resolutionCount") Integer resolutionCount,
            @Param("tenantId") String tenantId,
            @Param("expectedId") String expectedId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 新增 registerOperation 所需的持久化事实。 */
    int registerOperation(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("adjustmentId") String adjustmentId,
            @Param("side") String side,
            @Param("adjustmentVersion") Long adjustmentVersion,
            @Param("inputJson") String inputJson,
            @Param("authorizationSourceJson") String authorizationSourceJson,
            @Param("createdAt") Timestamp createdAt);

    /** 读取 requireUsedReturns 所需的持久化事实。 */
    List<String> requireUsedReturns(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("fundsIdentity") Object fundsIdentity);

    /** 读取 requireCompletedHistoryCurrent 所需的持久化事实。 */
    List<String> requireCompletedHistoryCurrent(
            @Param("tenantId") String tenantId, @Param("reportId") String reportId);

    /** 读取 sequence 所需的持久化事实。 */
    List<Long> sequence(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId);

    /** 读取 requireRecordedCompletion 所需的持久化事实。 */
    List<SqlRow> requireRecordedCompletion(
            @Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 requireRegistered 所需的持久化事实。 */
    List<SqlRow> requireRegistered(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("adjustmentId") String adjustmentId,
            @Param("side") String side);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 按 due 的筛选条件执行数据库查询。 */
    List<SqlRow> dueQuery(
            @Param("sideChoice") String sideChoice, @Param("parameters") Object[] parameters);
}
