package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * JdbcAdvanceOverdueRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AdvanceOverdueRepositoryMapper {
    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 recorded 所需的持久化事实。 */
    List<Integer> recorded(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 record 所需的持久化事实。 */
    int record(
            @Param("tenantId") String tenantId,
            @Param("advanceId") String advanceId,
            @Param("inboxId") String inboxId,
            @Param("employeeId") String employeeId,
            @Param("advanceVersion") Long advanceVersion,
            @Param("observedOn") LocalDate observedOn,
            @Param("timeZone") String timeZone,
            @Param("outstanding") BigDecimal outstanding,
            @Param("currency") String currency,
            @Param("createdAt") Timestamp createdAt);

    /** 读取 ownedDueBeforeRows 所需的数据库事实。 */
    List<SqlRow> ownedDueBeforeRows(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);

    /** 读取 candidatesRows 所需的数据库事实。 */
    List<SqlRow> candidatesRows(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
