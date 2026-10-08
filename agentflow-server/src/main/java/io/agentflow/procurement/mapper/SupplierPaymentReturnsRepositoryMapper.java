package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPaymentReturnsRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPaymentReturnsRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("paymentId") String paymentId,
            @Param("paymentVersion") Long paymentVersion,
            @Param("requestId") String requestId,
            @Param("legalEntityId") String legalEntityId,
            @Param("supplierReference") Object supplierReference,
            @Param("payableReference") Object payableReference,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 locked 所需的持久化事实。 */
    List<SqlRow> locked(@Param("tenant") String tenant, @Param("id") String id);

    /** 更新 completeAccounting 所需的持久化事实。 */
    int completeAccounting(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("reviewRequired") Boolean reviewRequired,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion,
            @Param("entryCount") Integer entryCount,
            @Param("accountedAt") Timestamp accountedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("requestJson") String requestJson,
            @Param("beforeJson") String beforeJson);

    /** 更新 persist 所需的持久化事实。 */
    int persist(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("reviewRequired") Boolean reviewRequired,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("requestJson") String requestJson,
            @Param("expectedStateJson") String expectedStateJson);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("paymentId") String paymentId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
