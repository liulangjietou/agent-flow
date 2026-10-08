package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SupplierPayableNotificationAccess 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPayableNotificationAccessMapper {
    /** 读取 original 所需的持久化事实。 */
    List<SqlRow> original(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 original 所需的持久化事实。 */
    List<SqlRow> original2(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 original 所需的持久化事实。 */
    List<SqlRow> original3(
            @Param("tenant") String tenant,
            @Param("requestId") String requestId,
            @Param("approvedRequestVersion") Long approvedRequestVersion);

    /** 读取 row 所需的持久化事实。 */
    List<SqlRow> row(
            @Param("tenant") String tenant,
            @Param("recipient") String recipient,
            @Param("id") String id);
}
