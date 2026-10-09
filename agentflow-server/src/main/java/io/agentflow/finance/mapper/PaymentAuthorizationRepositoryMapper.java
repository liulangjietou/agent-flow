package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * JdbcPaymentAuthorizationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentAuthorizationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationVersion") Long applicationVersion,
            @Param("businessVersion") Long businessVersion,
            @Param("purpose") String purpose,
            @Param("voucherOperationId") String voucherOperationId,
            @Param("voucherKind") String voucherKind,
            @Param("termsJson") String termsJson,
            @Param("decisionJson") String decisionJson,
            @Param("stateJson") String stateJson,
            @Param("activeBusinessId") String activeBusinessId,
            @Param("authorizedAt") Timestamp authorizedAt,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("legalEntityId") String legalEntityId,
            @Param("dueDate") LocalDate dueDate);

    /** 读取 update 所需的持久化事实。 */
    List<Long> update(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 更新 update 所需的持久化事实。 */
    int update2(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("activeBusiness") String activeBusiness,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("debitAccountKey") String debitAccountKey,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("termsJson") String termsJson,
            @Param("decisionJson") String decisionJson,
            @Param("value13") String value13,
            @Param("dueDate") LocalDate dueDate);

    /** 新增 update 所需的持久化事实。 */
    int update3(
            @Param("tenantId") String tenantId,
            @Param("authorizationId") String authorizationId,
            @Param("operationVersion") Long operationVersion,
            @Param("basis") String basis,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("retirementJson") String retirementJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(
            @Param("tenant") String tenant,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("applicationId") String applicationId,
            @Param("round") Integer round);

    /** 读取 row 所需的持久化事实。 */
    List<SqlRow> row(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("operationVersion") Long operationVersion,
            @Param("basis") String basis,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt);

    /** 读取 requireRetirementProof 所需的持久化事实。 */
    List<SqlRow> requireRetirementProof(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("operationVersion") Long operationVersion);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("authorizationId") String authorizationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 按 cashierPage 的筛选条件执行数据库查询。 */
    List<SqlRow> cashierPageQuery(
            @Param("hasLegalEntityId") boolean hasLegalEntityId,
            @Param("assignedAccountFilter") boolean assignedAccountFilter,
            @Param("unassignedAccountFilter") boolean unassignedAccountFilter,
            @Param("undatedSelected") boolean undatedSelected,
            @Param("hasDueFrom") boolean hasDueFrom,
            @Param("hasDueTo") boolean hasDueTo,
            @Param("hasDecision") boolean hasDecision,
            @Param("hasDecision2") boolean hasDecision2,
            @Param("hasCursor") boolean hasCursor,
            @Param("condition9") boolean condition9,
            @Param("parameters") Object[] parameters);

    /** 按 cashierCount 的筛选条件执行数据库查询。 */
    List<Long> cashierCountQuery(
            @Param("hasLegalEntityId") boolean hasLegalEntityId,
            @Param("assignedAccountFilter") boolean assignedAccountFilter,
            @Param("unassignedAccountFilter") boolean unassignedAccountFilter,
            @Param("undatedSelected") boolean undatedSelected,
            @Param("hasDueFrom") boolean hasDueFrom,
            @Param("hasDueTo") boolean hasDueTo,
            @Param("parameters") Object[] parameters);

    /** 按 cashierMatches 的筛选条件执行数据库查询。 */
    List<Boolean> cashierMatchesQuery(
            @Param("hasLegalEntityId") boolean hasLegalEntityId,
            @Param("assignedAccountFilter") boolean assignedAccountFilter,
            @Param("unassignedAccountFilter") boolean unassignedAccountFilter,
            @Param("undatedSelected") boolean undatedSelected,
            @Param("hasDueFrom") boolean hasDueFrom,
            @Param("hasDueTo") boolean hasDueTo,
            @Param("parameters") Object[] parameters);

    /** 按 cashierAccounts 的筛选条件执行数据库查询。 */
    List<SqlRow> cashierAccountsQuery(
            @Param("hasLegalEntityId") boolean hasLegalEntityId,
            @Param("assignedAccountFilter") boolean assignedAccountFilter,
            @Param("unassignedAccountFilter") boolean unassignedAccountFilter,
            @Param("undatedSelected") boolean undatedSelected,
            @Param("hasDueFrom") boolean hasDueFrom,
            @Param("hasDueTo") boolean hasDueTo,
            @Param("condition6") boolean condition6,
            @Param("parameters") Object[] parameters);
}
