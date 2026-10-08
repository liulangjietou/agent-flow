package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePaymentReturnRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePaymentReturnRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId,
            @Param("returnVersion") Long returnVersion,
            @Param("settlementVersion") Long settlementVersion,
            @Param("checkId") String checkId,
            @Param("checkVersion") Long checkVersion,
            @Param("outcome") String outcome,
            @Param("registeredBy") String registeredBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("registeredAt") Timestamp registeredAt,
            @Param("stateJson") String stateJson);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);
}
