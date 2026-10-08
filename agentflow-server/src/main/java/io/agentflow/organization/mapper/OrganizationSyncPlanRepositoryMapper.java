package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcOrganizationSyncPlanRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OrganizationSyncPlanRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("batchVersion") Long batchVersion,
            @Param("sourceVersion") Long sourceVersion,
            @Param("directoryRevision") Long directoryRevision,
            @Param("preparedBy") String preparedBy,
            @Param("preparedAt") Timestamp preparedAt,
            @Param("ready") Boolean ready,
            @Param("digest") String digest,
            @Param("body") String body,
            @Param("tenantId") String tenantId,
            @Param("batchId") String batchId,
            @Param("version") Long version,
            @Param("sourceVersion2") Long sourceVersion2,
            @Param("revision") Long revision);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 新增 applied 所需的持久化事实。 */
    int applied(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("batchId") String batchId,
            @Param("digest") String digest);

    /** 读取 appliedPlan 所需的持久化事实。 */
    List<SqlRow> appliedPlan(@Param("tenant") String tenant, @Param("batchId") String batchId);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("batchId") String batchId,
            @Param("size") Integer size,
            @Param("offset") Long offset);

    /** 读取 page 所需的持久化事实。 */
    List<Long> page2(@Param("tenant") String tenant, @Param("batchId") String batchId);
}
