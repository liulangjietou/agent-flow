package io.agentflow.approval.process.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * FlowableApprovalProxyNotifications 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FlowableApprovalProxyNotificationsMapper {
    /** 读取 overdue 所需的持久化事实。 */
    List<SqlRow> overdue(@Param("parameters") Object[] parameters);

    /** 读取 notify 所需的持久化事实。 */
    List<Boolean> notify(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("getId") Object getId,
            @Param("kind") String kind,
            @Param("value5") String value5);

    /** 新增 notify 所需的持久化事实。 */
    int notify2(
            @Param("tenantId") String tenantId,
            @Param("proxyId") String proxyId,
            @Param("taskId") Object taskId,
            @Param("kind") String kind,
            @Param("inboxId") String inboxId);

    /** 读取 capture 所需的持久化事实。 */
    List<SqlRow> capture(@Param("parameters") Object[] parameters);

    /** 读取 lifecycle 所需的持久化事实。 */
    List<Boolean> lifecycle(
            @Param("tenantId") String tenantId,
            @Param("proxyId") String proxyId,
            @Param("taskId") String taskId,
            @Param("kind") String kind,
            @Param("version") Long version);

    /** 新增 lifecycle 所需的持久化事实。 */
    int lifecycle2(
            @Param("tenantId") String tenantId,
            @Param("proxyId") String proxyId,
            @Param("taskId") String taskId,
            @Param("kind") String kind,
            @Param("inboxId") String inboxId,
            @Param("eventVersion") Long eventVersion);

    /** 读取 deliveryAllowed 所需的持久化事实。 */
    List<SqlRow> deliveryAllowed(
            @Param("tenantId") String tenantId, @Param("inboxId") String inboxId);

    /** 按 candidates 的筛选条件执行数据库查询。 */
    List<SqlRow> candidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
