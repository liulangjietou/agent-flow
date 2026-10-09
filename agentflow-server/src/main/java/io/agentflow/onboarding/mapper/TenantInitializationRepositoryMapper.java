package io.agentflow.onboarding.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcTenantInitializationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface TenantInitializationRepositoryMapper {
    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("workspaceName") String workspaceName,
            @Param("initializedBy") String initializedBy,
            @Param("initializedAt") Timestamp initializedAt,
            @Param("administratorPersonId") String administratorPersonId,
            @Param("administratorAppointmentId") String administratorAppointmentId,
            @Param("calendarId") String calendarId,
            @Param("calendarRevision") Long calendarRevision,
            @Param("snapshotJson") String snapshotJson);

    /** 新增 append 所需的持久化事实。 */
    int append2(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("actorId") String actorId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt);
}
