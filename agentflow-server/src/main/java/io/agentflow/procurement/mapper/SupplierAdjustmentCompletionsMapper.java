package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierAdjustmentCompletions 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierAdjustmentCompletionsMapper {
    /** 读取 create 所需的持久化事实。 */
    List<Integer> create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("digest") String digest,
            @Param("expectedStateJson") String expectedStateJson,
            @Param("expectedVersion") Long expectedVersion,
            @Param("stateJson8") String stateJson8,
            @Param("commandJson") String commandJson,
            @Param("commandDigest") String commandDigest,
            @Param("version11") Long version11,
            @Param("stateJson12") String stateJson12);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion,
            @Param("paymentId") String paymentId,
            @Param("paymentVersion") Long paymentVersion,
            @Param("bankStatus") String bankStatus,
            @Param("reservationId") String reservationId,
            @Param("beforeReturnVersion") Long beforeReturnVersion,
            @Param("returnVersion") Long returnVersion,
            @Param("accountedEntryCount") Integer accountedEntryCount,
            @Param("proofJson") String proofJson,
            @Param("completedAt") Timestamp completedAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("operationId") String operationId);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("paymentId") String paymentId);
}
