package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePartialPreparationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePartialPreparationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("adjustmentId") String adjustmentId,
            @Param("adjustmentVersion") Long adjustmentVersion,
            @Param("reportId") String reportId,
            @Param("side") String side,
            @Param("requestedBy") String requestedBy,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 新增 consume 所需的持久化事实。 */
    int consume(
            @Param("tenantId") String tenantId,
            @Param("preparationId") String preparationId,
            @Param("adjustmentId") String adjustmentId,
            @Param("preparationBefore") Long preparationBefore,
            @Param("preparationAfter") Long preparationAfter,
            @Param("adjustmentBefore") Long adjustmentBefore,
            @Param("adjustmentAfter") Long adjustmentAfter,
            @Param("operationId") String operationId,
            @Param("authorizedAt") Timestamp authorizedAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("adjustment") String adjustment,
            @Param("side") String side,
            @Param("actor") String actor);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 save 所需的持久化事实。 */
    List<SqlRow> save(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 更新 save 所需的持久化事实。 */
    int save2(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("active") Integer active,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("originalInput") Object originalInput);

    /** 读取 requireConsumption 所需的持久化事实。 */
    List<SqlRow> requireConsumption(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("preparationId") String preparationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
