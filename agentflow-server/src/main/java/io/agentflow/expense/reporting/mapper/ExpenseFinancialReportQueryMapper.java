package io.agentflow.expense.reporting.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ExpenseFinancialReportQuery 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseFinancialReportQueryMapper {
    /** 读取 read 所需的持久化事实。 */
    List<SqlRow> read(
            @Param("tenantId") String tenantId,
            @Param("startAt") Timestamp startAt,
            @Param("endAt") Timestamp endAt);
}
