package io.agentflow.approval.operations.mapper;

import io.agentflow.mybatis.SqlRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 审计事件分批归档留存 SQL 映射。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AuditRetentionMapper {
    /** 查询早于截止时刻的一批热表审计记录。 */
    List<SqlRow> findExpiredBatch(
            @Param("tenantId") String tenantId,
            @Param("cutoff") Timestamp cutoff,
            @Param("limit") int limit);

    /** 将审计事实写入归档表（按主键去重防重跑冲突）。 */
    int insertArchive(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("eventId") String eventId,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("aggregateVersion") long aggregateVersion,
            @Param("applicationId") String applicationId,
            @Param("action") String action,
            @Param("actorId") String actorId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt,
            @Param("archivedAt") Timestamp archivedAt);

    /** 仅在成功写入归档表后从热表删除同一条记录。 */
    int deleteFromHotTable(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 记录一次留存归档执行台账。 */
    int insertRun(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("cutoffAt") Timestamp cutoffAt,
            @Param("archivedCount") int archivedCount,
            @Param("triggeredBy") String triggeredBy,
            @Param("executedAt") Timestamp executedAt);

    /** 查询指定租户最近归档执行记录。 */
    List<SqlRow> listRuns(@Param("tenantId") String tenantId, @Param("limit") int limit);

    /** 统计指定租户已归档审计总数。 */
    List<SqlRow> countArchived(@Param("tenantId") String tenantId);
}
