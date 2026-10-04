package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 编排组织实体间的同租户关系、部门环检查和事务审计；实体自身负责启停及修订转换。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationService {
    private static final String PERSON = "PERSON";
    private static final String APPOINTMENT = "APPOINTMENT";
    private final OrganizationRepository repository;

    /** 权限在 HTTP 入口及幂等回放前核对，这里仅接受服务端认证主体。 */
    public OrganizationService(OrganizationRepository repository) { this.repository = repository; }

    /** 显式建立本地目录，之后不会因目录为空而恢复演示名单。 */
    @Transactional
    public void initialize(Actor actor) { repository.initialize(actor.tenantId(), actor.userId(), Instant.now()); }

    /** 新增有明确类型的组织单元。 */
    @Transactional
    public OrganizationUnit createUnit(Actor actor, OrganizationUnit.Kind kind, String name, UUID legalEntityId,
                                       UUID parentDepartmentId, boolean active) {
        long revision = repository.lock(actor.tenantId());
        var value = new OrganizationUnit(UUID.randomUUID(), kind, name, legalEntityId, parentDepartmentId, active, 1);
        checkUnit(actor.tenantId(), value);
        repository.save(actor.tenantId(), value, 0);
        record(actor, revision, kind.name(), value.id(), value);
        return value;
    }

    /** 修改自身状态及同法人内部层级，不改写实体类型、法人归属和历史。 */
    @Transactional
    public OrganizationUnit updateUnit(Actor actor, UUID id, String name, UUID parentDepartmentId, boolean active, long expectedRevision) {
        long revision = repository.lock(actor.tenantId());
        var value = unit(actor.tenantId(), id).revise(name, parentDepartmentId, active, expectedRevision);
        checkUnit(actor.tenantId(), value);
        repository.save(actor.tenantId(), value, expectedRevision);
        record(actor, revision, value.kind().name(), value.id(), value);
        return value;
    }

    /** 人员绑定稳定身份，不接受系统角色配置。 */
    @Transactional
    public OrganizationPerson createPerson(Actor actor, String subject, String displayName, boolean active, boolean approvalEligible) {
        long revision = repository.lock(actor.tenantId());
        var value = new OrganizationPerson(UUID.randomUUID(), subject, displayName, active, approvalEligible, 1);
        repository.save(actor.tenantId(), value, 0);
        record(actor, revision, PERSON, value.id(), value);
        return value;
    }

    /** 本地停用或取消资格影响新的资格读取，历史责任事实仍然保留。 */
    @Transactional
    public OrganizationPerson updatePerson(Actor actor, UUID id, String displayName, boolean active, boolean approvalEligible, long expectedRevision) {
        long revision = repository.lock(actor.tenantId());
        var value = person(actor.tenantId(), id).revise(displayName, active, approvalEligible, expectedRevision);
        repository.save(actor.tenantId(), value, expectedRevision);
        record(actor, revision, PERSON, value.id(), value);
        return value;
    }

    /** 任职引用明确人员及同法人部门/岗位，支持一人多任职。 */
    @Transactional
    public OrganizationAppointment createAppointment(Actor actor, UUID personId, UUID departmentId, UUID positionId, boolean active) {
        long revision = repository.lock(actor.tenantId());
        var value = new OrganizationAppointment(UUID.randomUUID(), personId, departmentId, positionId, active, 1);
        checkAppointment(actor.tenantId(), value);
        repository.save(actor.tenantId(), value, 0);
        record(actor, revision, APPOINTMENT, value.id(), value);
        return value;
    }

    /** 结束或恢复原任职，不把调岗覆盖到历史关系上。 */
    @Transactional
    public OrganizationAppointment updateAppointment(Actor actor, UUID id, boolean active, long expectedRevision) {
        long revision = repository.lock(actor.tenantId());
        var value = appointment(actor.tenantId(), id).revise(active, expectedRevision);
        checkAppointment(actor.tenantId(), value);
        repository.save(actor.tenantId(), value, expectedRevision);
        record(actor, revision, APPOINTMENT, value.id(), value);
        return value;
    }

    /** 同法人内为任职设置主管，检查整条主管链，不以人员主任职推断路径。 */
    @Transactional
    public OrganizationAppointment setSupervisor(Actor actor, UUID id, UUID supervisorId, long expectedRevision) {
        long revision = repository.lock(actor.tenantId());
        var value = appointment(actor.tenantId(), id).withSupervisor(supervisorId, expectedRevision);
        relations(actor.tenantId()).supervisor(value);
        repository.save(actor.tenantId(), value, expectedRevision);
        record(actor, revision, APPOINTMENT, id, value);
        return value;
    }

    /** 部门负责人必须在该部门有效任职，清空配置不删除历史记录。 */
    @Transactional
    public OrganizationUnit setDepartmentHead(Actor actor, UUID id, UUID appointmentId, long expectedRevision) {
        long revision = repository.lock(actor.tenantId());
        var value = requireKind(actor.tenantId(), id, OrganizationUnit.Kind.DEPARTMENT).withHead(appointmentId, expectedRevision);
        relations(actor.tenantId()).head(value);
        repository.save(actor.tenantId(), value, expectedRevision);
        record(actor, revision, value.kind().name(), id, value);
        return value;
    }

    private OrganizationRelations relations(String tenant) {
        return new OrganizationRelations(id -> unit(tenant, id), id -> person(tenant, id), id -> appointment(tenant, id));
    }
    private void checkUnit(String tenant, OrganizationUnit value) { relations(tenant).unit(value); }
    private void checkAppointment(String tenant, OrganizationAppointment value) { relations(tenant).appointment(value); }

    private OrganizationUnit requireKind(String tenant, UUID id, OrganizationUnit.Kind kind) {
        var value = unit(tenant, id);
        if (value.kind() != kind) throw relation();
        return value;
    }
    private OrganizationUnit unit(String tenant, UUID id) { return repository.unit(tenant, id).orElseThrow(OrganizationService::notFound); }
    private OrganizationPerson person(String tenant, UUID id) { return repository.person(tenant, id).orElseThrow(OrganizationService::notFound); }
    private OrganizationAppointment appointment(String tenant, UUID id) { return repository.appointment(tenant, id).orElseThrow(OrganizationService::notFound); }
    private void record(Actor actor, long revision, String kind, UUID id, Object value) {
        repository.recordChange(actor.tenantId(), revision, actor.userId(), kind, id, value, Instant.now());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Organization record not found"); }
    private static DomainException relation() { return new DomainException("ORGANIZATION_RELATION_INVALID", "Organization relation type or legal entity does not match"); }
}
