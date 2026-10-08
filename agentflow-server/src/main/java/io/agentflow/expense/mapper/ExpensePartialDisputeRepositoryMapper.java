package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePartialDisputeRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePartialDisputeRepositoryMapper {
    /** 新增 record 所需的持久化事实。 */
    int record(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("adjustmentId") String adjustmentId,
            @Param("side") String side,
            @Param("operationId") String operationId,
            @Param("sequenceNo") Integer sequenceNo,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Long afterVersion,
            @Param("outcome") String outcome,
            @Param("resolvedBy") String resolvedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("stateJson") String stateJson);

    /** 读取 recorded 所需的持久化事实。 */
    List<SqlRow> recorded(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 读取 revisions 所需的持久化事实。 */
    List<SqlRow> revisions(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version);
}
