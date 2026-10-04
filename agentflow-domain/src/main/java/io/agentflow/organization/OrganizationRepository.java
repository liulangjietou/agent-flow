package io.agentflow.organization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地组织事实仓储；所有查询显式限定租户，修改和追加审计属于同一事务。
 * @author owlzhangfq@gmail.com
 */
public interface OrganizationRepository {
    /** 初始化后不再向演示目录回退。 */
    void initialize(String tenantId, String actor, Instant now);
    /** 读取是否已明确启用本地目录。 */
    boolean initialized(String tenantId);
    /** 只读目录修订，尚未初始化返回 0，供跨聚合设置确认来源。 */
    long revision(String tenantId);
    /** 写事务首先锁定目录行，串行化跨实体关系检查，返回当前目录修订。 */
    long lock(String tenantId);
    /** 读取组织单元。 */
    Optional<OrganizationUnit> unit(String tenantId, UUID id);
    /** 按标识分页列出一种组织单元，返回 limit+1 条。 */
    List<OrganizationUnit> units(String tenantId, OrganizationUnit.Kind kind, String afterId, int limit);
    /** 保存组织单元；expectedRevision 为 0 表示新增。 */
    void save(String tenantId, OrganizationUnit value, long expectedRevision);
    /** 读取人员。 */
    Optional<OrganizationPerson> person(String tenantId, UUID id);
    /** 精确读取认证主体的本地人员记录。 */
    Optional<OrganizationPerson> personBySubject(String tenantId, String subject);
    /** 按标识分页列出人员，返回 limit+1 条。 */
    List<OrganizationPerson> people(String tenantId, String afterId, int limit);
    /** 保存人员；身份绑定唯一且不能更新。 */
    void save(String tenantId, OrganizationPerson value, long expectedRevision);
    /** 读取任职。 */
    Optional<OrganizationAppointment> appointment(String tenantId, UUID id);
    /** 精确读取任职身份，供同步预检要求显式采用已有任职，不按名称猜测。 */
    Optional<OrganizationAppointment> appointmentByIdentity(String tenantId, UUID personId, UUID departmentId, UUID positionId);
    /** 分页读取任职，可限定人员。 */
    List<OrganizationAppointment> appointments(String tenantId, UUID personId, String afterId, int limit);
    /** 保存任职，结束任职保留关系。 */
    void save(String tenantId, OrganizationAppointment value, long expectedRevision);
    /** 追加带目录修订的修改事实，snapshot 为本次修改后的实体。 */
    void recordChange(String tenantId, long previousRevision, String actor, String kind, UUID recordId, Object snapshot, Instant now);
    /** 管理员读取变更历史，返回 limit+1 条。 */
    List<Change> changes(String tenantId, Long beforeRevision, int limit);

    /**
     * 不可变操作事实只供当前租户管理员读取，不出现在普通选人接口。
     * @author owlzhangfq@gmail.com
     */
    record Change(long revision, String actor, String kind, UUID recordId, String snapshotJson, Instant occurredAt) { }
}
