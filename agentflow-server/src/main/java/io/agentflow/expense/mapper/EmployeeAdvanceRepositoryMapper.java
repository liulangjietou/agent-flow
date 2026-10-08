package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * JdbcEmployeeAdvanceRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EmployeeAdvanceRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("advanceId") String advanceId,
            @Param("employeeId") String employeeId,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("paidOn") LocalDate paidOn,
            @Param("dueOn") LocalDate dueOn,
            @Param("traceId") String traceId);

    /** 执行 findOwnedByPaidOn 的条件查询。 */
    List<SqlRow> findOwnedByPaidOnQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
