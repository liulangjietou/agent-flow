package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcNotificationPreferencesRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface NotificationPreferencesRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("tenantId") String tenantId,
            @Param("recipientId") String recipientId,
            @Param("emailEnabled") Boolean emailEnabled,
            @Param("enterpriseImEnabled") Boolean enterpriseImEnabled,
            @Param("version") Long version,
            @Param("emailGeneration") Long emailGeneration,
            @Param("enterpriseImGeneration") Long enterpriseImGeneration,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 save 所需的持久化事实。 */
    int save2(
            @Param("emailEnabled") Boolean emailEnabled,
            @Param("enterpriseImEnabled") Boolean enterpriseImEnabled,
            @Param("version") Long version,
            @Param("emailGeneration") Long emailGeneration,
            @Param("enterpriseImGeneration") Long enterpriseImGeneration,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("recipient") String recipient,
            @Param("expectedVersion") Long expectedVersion);

    /** 新增 save 所需的持久化事实。 */
    int save3(
            @Param("tenantId") String tenantId,
            @Param("recipientId") String recipientId,
            @Param("version") Long version,
            @Param("emailEnabled") Boolean emailEnabled,
            @Param("enterpriseImEnabled") Boolean enterpriseImEnabled,
            @Param("emailGeneration") Long emailGeneration,
            @Param("enterpriseImGeneration") Long enterpriseImGeneration,
            @Param("changedAt") Timestamp changedAt);

    /** 执行 read 的条件查询。 */
    List<SqlRow> readQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
