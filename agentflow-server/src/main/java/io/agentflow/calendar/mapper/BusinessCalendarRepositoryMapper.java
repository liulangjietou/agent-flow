package io.agentflow.calendar.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcBusinessCalendarRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface BusinessCalendarRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("calendarKey") String calendarKey,
            @Param("name") String name,
            @Param("zoneId") String zoneId,
            @Param("revision") Long revision,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("name") String name,
            @Param("zoneId") String zoneId,
            @Param("revision") Long revision,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 新增 appendVersion 所需的持久化事实。 */
    int appendVersion(
            @Param("tenantId") String tenantId,
            @Param("calendarId") String calendarId,
            @Param("calendarKey") String calendarKey,
            @Param("name") String name,
            @Param("zoneId") String zoneId,
            @Param("revision") Long revision,
            @Param("rulesJson") String rulesJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 findVersion 所需的持久化事实。 */
    List<SqlRow> findVersion(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("revision") Long revision);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list(
            @Param("tenantId") String tenantId,
            @Param("calendarKey") String calendarKey,
            @Param("limit") Integer limit);

    /** 读取 versions 所需的持久化事实。 */
    List<SqlRow> versions(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("revision") Long revision,
            @Param("limit") Integer limit);
}
