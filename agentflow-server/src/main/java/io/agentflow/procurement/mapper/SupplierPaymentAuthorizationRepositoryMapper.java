package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPaymentAuthorizationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPaymentAuthorizationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("requestId") String requestId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationVersion") Long applicationVersion,
            @Param("requestVersion") Long requestVersion,
            @Param("reservationId") String reservationId,
            @Param("legalEntityId") String legalEntityId,
            @Param("authorizedBy") String authorizedBy,
            @Param("authorizedAt") Timestamp authorizedAt,
            @Param("expiresAt") Timestamp expiresAt,
            @Param("stateJson") String stateJson,
            @Param("activeRequestId") String activeRequestId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 forRequest 所需的持久化事实。 */
    List<SqlRow> forRequest(@Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 activeForRequest 所需的持久化事实。 */
    List<SqlRow> activeForRequest(
            @Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 retire 所需的持久化事实。 */
    List<SqlRow> retire(
            @Param("tenant") String tenant,
            @Param("authorizationId") String authorizationId,
            @Param("operationVersion") Long operationVersion,
            @Param("digest") String digest,
            @Param("stateJson") String stateJson);

    /** 新增 retire 所需的持久化事实。 */
    int retire2(
            @Param("tenantId") String tenantId,
            @Param("authorizationId") String authorizationId,
            @Param("operationVersion") Long operationVersion,
            @Param("basis") String basis,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("stateJson") String stateJson);

    /** 更新 retire 所需的持久化事实。 */
    int retire3(
            @Param("operationVersion") Long operationVersion,
            @Param("tenant") String tenant,
            @Param("authorizationId") String authorizationId);

    /** 读取 retirement 所需的持久化事实。 */
    List<SqlRow> retirement(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 retirementProof 所需的持久化事实。 */
    List<SqlRow> retirementProof(
            @Param("tenant") String tenant,
            @Param("authorizationId") String authorizationId,
            @Param("operationVersion") Long operationVersion);

    /** 按 cashierPage 的筛选条件执行数据库查询。 */
    List<SqlRow> cashierPageQuery(
            @Param("entitiesCount") int entitiesCount,
            @Param("condition1") boolean condition1,
            @Param("parameters") Object[] parameters);
}
