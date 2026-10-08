package io.agentflow.expense.reporting.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ExpenseReportingActivity 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseReportingActivityMapper {
    /** 读取 read 所需的持久化事实。 */
    List<String> read(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("startAt") Timestamp startAt,
            @Param("endAt") Timestamp endAt);

    /** 读取 read 所需的持久化事实。 */
    List<Long> read2(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("roundNo") Integer roundNo,
            @Param("startAt") Timestamp startAt,
            @Param("endAt") Timestamp endAt);

    /** 读取 read 所需的持久化事实。 */
    List<String> read3(
            @Param("tenant") String tenant,
            @Param("getKey") String getKey,
            @Param("startAt") Timestamp startAt,
            @Param("endAt") Timestamp endAt,
            @Param("getValueAt") Timestamp getValueAt);

    /** 读取 read 所需的持久化事实。 */
    List<SqlRow> read4();
}
