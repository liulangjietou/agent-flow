package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcSupplierAdjustmentSources 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierAdjustmentSourcesMapper {
    /** 读取 requireEvidence 所需的持久化事实。 */
    List<SqlRow> requireEvidence(
            @Param("tenant") String tenant, @Param("operationId") String operationId);

    /** 读取 requireRecorded 所需的持久化事实。 */
    List<SqlRow> requireRecorded(
            @Param("tenant") String tenant,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("id") String id);

    /** 读取 requireFinancialState 所需的持久化事实。 */
    List<Integer> requireFinancialState(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 requireFinancialState 所需的持久化事实。 */
    List<Integer> requireFinancialState2(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 settlementRevision 所需的持久化事实。 */
    List<SqlRow> settlementRevision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 activeSettlement 所需的持久化事实。 */
    List<SqlRow> activeSettlement(
            @Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 读取 firstSettlement 所需的持久化事实。 */
    List<SqlRow> firstSettlement(@Param("tenantId") String tenantId, @Param("id") String id);
}
