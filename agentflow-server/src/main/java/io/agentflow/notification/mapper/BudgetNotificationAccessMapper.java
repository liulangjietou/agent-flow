package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * BudgetNotificationAccess 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BudgetNotificationAccessMapper {
    /** 读取 original 所需的持久化事实。 */
    List<SqlRow> original(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("financialVersion") Object financialVersion);

    /** 读取 row 所需的持久化事实。 */
    List<SqlRow> row(
            @Param("tenant") String tenant,
            @Param("recipient") String recipient,
            @Param("messageId") String messageId);
}
