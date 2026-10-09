package io.agentflow.event.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcEventContractRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EventContractRepositoryMapper {
    /** 新增 publish 所需的持久化事实。 */
    int publish(@Param("tenantId") String tenantId, @Param("contractKey") String contractKey);

    /** 更新 publish 所需的持久化事实。 */
    int publish2(
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("expectedVersion") Long expectedVersion);

    /** 新增 publish 所需的持久化事实。 */
    int publish3(
            @Param("tenantId") String tenantId,
            @Param("contractKey") String contractKey,
            @Param("contractVersion") Long contractVersion,
            @Param("name") String name,
            @Param("sourceKey") String sourceKey,
            @Param("eventType") String eventType,
            @Param("envelopeVersion") Integer envelopeVersion,
            @Param("publishedBy") String publishedBy,
            @Param("publishedAt") Timestamp publishedAt,
            @Param("publicationReason") String publicationReason,
            @Param("changedBy") String changedBy,
            @Param("changedAt") Timestamp changedAt,
            @Param("changeReason") String changeReason);

    /** 更新 changeAvailability 所需的持久化事实。 */
    int changeAvailability(
            @Param("revision") Long revision,
            @Param("enabled") Boolean enabled,
            @Param("changedBy") String changedBy,
            @Param("changedAt") Timestamp changedAt,
            @Param("reason") String reason,
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("contractVersion") Long contractVersion,
            @Param("expectedRevision") Long expectedRevision,
            @Param("beforeEnabled") Boolean beforeEnabled);

    /** 新增 appendHistory 所需的持久化事实。 */
    int appendHistory(
            @Param("tenantId") String tenantId,
            @Param("contractKey") String contractKey,
            @Param("contractVersion") Long contractVersion,
            @Param("revision") Long revision,
            @Param("enabled") Boolean enabled,
            @Param("changedBy") String changedBy,
            @Param("changedAt") Timestamp changedAt,
            @Param("reason") String reason);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("version") Long version);

    /** 读取 lockVersion 所需的持久化事实。 */
    List<SqlRow> lockVersion(
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("version") Long version);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(@Param("tenantId") String tenantId, @Param("key") String key);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list(
            @Param("tenantId") String tenantId,
            @Param("contractKey") String contractKey,
            @Param("limit") Integer limit);

    /** 执行 versions 的条件查询。 */
    List<SqlRow> versionsQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);

    /** 执行 history 的条件查询。 */
    List<SqlRow> historyQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
