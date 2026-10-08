package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcOrganizationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OrganizationRepositoryMapper {
    /** 新增 initialize 所需的持久化事实。 */
    int initialize(
            @Param("tenantId") String tenantId,
            @Param("initializedBy") String initializedBy,
            @Param("initializedAt") Timestamp initializedAt);

    /** 读取 initialized 所需的持久化事实。 */
    List<Boolean> initialized(@Param("tenantId") String tenantId);

    /** 读取 revision 所需的持久化事实。 */
    List<Long> revision(@Param("tenantId") String tenantId);

    /** 读取 lock 所需的持久化事实。 */
    List<Long> lock(@Param("tenantId") String tenantId);

    /** 读取 unit 所需的持久化事实。 */
    List<SqlRow> unit(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 units 所需的持久化事实。 */
    List<SqlRow> units(
            @Param("tenantId") String tenantId,
            @Param("kind") String kind,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("name") String name,
            @Param("legalEntityId") String legalEntityId,
            @Param("parentDepartmentId") String parentDepartmentId,
            @Param("active") Boolean active,
            @Param("revision") Long revision);

    /** 更新 save 所需的持久化事实。 */
    int save2(
            @Param("name") String name,
            @Param("parentDepartmentId") String parentDepartmentId,
            @Param("active") Boolean active,
            @Param("revision") Long revision,
            @Param("headAppointmentId") String headAppointmentId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 读取 person 所需的持久化事实。 */
    List<SqlRow> person(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 personBySubject 所需的持久化事实。 */
    List<SqlRow> personBySubject(
            @Param("tenantId") String tenantId, @Param("subject") String subject);

    /** 读取 people 所需的持久化事实。 */
    List<SqlRow> people(
            @Param("tenantId") String tenantId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 新增 save 所需的持久化事实。 */
    int save3(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("subject") String subject,
            @Param("displayName") String displayName,
            @Param("active") Boolean active,
            @Param("approvalEligible") Boolean approvalEligible,
            @Param("revision") Long revision);

    /** 更新 save 所需的持久化事实。 */
    int save4(
            @Param("displayName") String displayName,
            @Param("active") Boolean active,
            @Param("approvalEligible") Boolean approvalEligible,
            @Param("revision") Long revision,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 读取 appointment 所需的持久化事实。 */
    List<SqlRow> appointment(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 appointmentByIdentity 所需的持久化事实。 */
    List<SqlRow> appointmentByIdentity(
            @Param("tenantId") String tenantId,
            @Param("personId") String personId,
            @Param("departmentId") String departmentId,
            @Param("positionId") String positionId);

    /** 读取 appointments 所需的持久化事实。 */
    List<SqlRow> appointments(
            @Param("tenantId") String tenantId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 读取 appointments 所需的持久化事实。 */
    List<SqlRow> appointments2(
            @Param("tenantId") String tenantId,
            @Param("personId") String personId,
            @Param("afterId") String afterId,
            @Param("limit") Integer limit);

    /** 新增 save 所需的持久化事实。 */
    int save5(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("personId") String personId,
            @Param("departmentId") String departmentId,
            @Param("positionId") String positionId,
            @Param("active") Boolean active,
            @Param("revision") Long revision);

    /** 更新 save 所需的持久化事实。 */
    int save6(
            @Param("active") Boolean active,
            @Param("revision") Long revision,
            @Param("supervisorAppointmentId") String supervisorAppointmentId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 更新 recordChange 所需的持久化事实。 */
    int recordChange(
            @Param("tenantId") String tenantId, @Param("previousRevision") Long previousRevision);

    /** 新增 recordChange 所需的持久化事实。 */
    int recordChange2(
            @Param("tenantId") String tenantId,
            @Param("revision") Long revision,
            @Param("actor") String actor,
            @Param("kind") String kind,
            @Param("recordId") String recordId,
            @Param("snapshotJson") String snapshotJson,
            @Param("occurredAt") Timestamp occurredAt);

    /** 读取 changes 所需的持久化事实。 */
    List<SqlRow> changes(
            @Param("tenantId") String tenantId,
            @Param("revision") Long revision,
            @Param("limit") Integer limit);
}
