package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.organization.mapper.OrganizationRepositoryMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 组织关系按实体存储，复用应用事务和数据库租户外键，不保存整租户可变 JSON 配置。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {
    private final OrganizationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 注入原业务数据源与统一 JSON 组件。 */
    public JdbcOrganizationRepository(OrganizationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    public void initialize(String tenantId, String actor, Instant now) {
        try {
            sqlMapper.initialize(tenantId, actor, Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "ORGANIZATION_ALREADY_INITIALIZED",
                    "Local organization is already initialized");
        }
    }

    @Override
    public boolean initialized(String tenantId) {
        return Boolean.TRUE.equals(SqlRows.single(sqlMapper.initialized(tenantId)));
    }

    @Override
    public long revision(String tenantId) {
        return sqlMapper.revision(tenantId).stream().findFirst().orElse(0L);
    }

    @Override
    public long lock(String tenantId) {
        return sqlMapper.lock(tenantId).stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new DomainException(
                                        "ORGANIZATION_NOT_INITIALIZED",
                                        "Initialize local organization before editing"));
    }

    @Override
    public Optional<OrganizationUnit> unit(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.unit(tenantId, id.toString()), unitMapper()).stream()
                .findFirst();
    }

    @Override
    public List<OrganizationUnit> units(
            String tenantId, OrganizationUnit.Kind kind, String afterId, int limit) {
        return SqlRows.map(
                sqlMapper.units(tenantId, kind.name(), afterId, limit + 1), unitMapper());
    }

    @Override
    public void save(String tenantId, OrganizationUnit value, long expectedRevision) {
        if (expectedRevision == 0) {
            sqlMapper.save(
                    tenantId,
                    value.id().toString(),
                    value.kind().name(),
                    value.name(),
                    id(value.legalEntityId()),
                    id(value.parentDepartmentId()),
                    value.active(),
                    value.revision());
        } else
            changed(
                    sqlMapper.save2(
                            value.name(),
                            id(value.parentDepartmentId()),
                            value.active(),
                            value.revision(),
                            id(value.headAppointmentId()),
                            tenantId,
                            value.id().toString(),
                            expectedRevision));
    }

    @Override
    public Optional<OrganizationPerson> person(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.person(tenantId, id.toString()), personMapper()).stream()
                .findFirst();
    }

    @Override
    public Optional<OrganizationPerson> personBySubject(String tenantId, String subject) {
        return SqlRows.map(sqlMapper.personBySubject(tenantId, subject), personMapper()).stream()
                .findFirst();
    }

    @Override
    public List<OrganizationPerson> people(String tenantId, String afterId, int limit) {
        return SqlRows.map(sqlMapper.people(tenantId, afterId, limit + 1), personMapper());
    }

    @Override
    public void save(String tenantId, OrganizationPerson value, long expectedRevision) {
        try {
            if (expectedRevision == 0) {
                sqlMapper.save3(
                        tenantId,
                        value.id().toString(),
                        value.subject(),
                        value.displayName(),
                        value.active(),
                        value.approvalEligible(),
                        value.revision());
            } else
                changed(
                        sqlMapper.save4(
                                value.displayName(),
                                value.active(),
                                value.approvalEligible(),
                                value.revision(),
                                tenantId,
                                value.id().toString(),
                                expectedRevision));
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "ORGANIZATION_IDENTITY_CONFLICT",
                    "The identity already belongs to a person in this tenant");
        }
    }

    @Override
    public Optional<OrganizationAppointment> appointment(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.appointment(tenantId, id.toString()), appointmentMapper())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<OrganizationAppointment> appointmentByIdentity(
            String tenantId, UUID personId, UUID departmentId, UUID positionId) {
        return SqlRows.map(
                        sqlMapper.appointmentByIdentity(
                                tenantId,
                                personId.toString(),
                                departmentId.toString(),
                                positionId.toString()),
                        appointmentMapper())
                .stream()
                .findFirst();
    }

    @Override
    public List<OrganizationAppointment> appointments(
            String tenantId, UUID personId, String afterId, int limit) {
        if (personId == null)
            return SqlRows.map(
                    sqlMapper.appointments(tenantId, afterId, limit + 1), appointmentMapper());
        return SqlRows.map(
                sqlMapper.appointments2(tenantId, personId.toString(), afterId, limit + 1),
                appointmentMapper());
    }

    @Override
    public void save(String tenantId, OrganizationAppointment value, long expectedRevision) {
        try {
            if (expectedRevision == 0) {
                sqlMapper.save5(
                        tenantId,
                        value.id().toString(),
                        value.personId().toString(),
                        value.departmentId().toString(),
                        value.positionId().toString(),
                        value.active(),
                        value.revision());
            } else
                changed(
                        sqlMapper.save6(
                                value.active(),
                                value.revision(),
                                id(value.supervisorAppointmentId()),
                                tenantId,
                                value.id().toString(),
                                expectedRevision));
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "ORGANIZATION_APPOINTMENT_CONFLICT",
                    "This appointment already exists; update its active state instead");
        }
    }

    @Override
    public void recordChange(
            String tenantId,
            long previousRevision,
            String actor,
            String kind,
            UUID recordId,
            Object snapshot,
            Instant now) {
        changed(sqlMapper.recordChange(tenantId, previousRevision));
        sqlMapper.recordChange2(
                tenantId,
                previousRevision + 1,
                actor,
                kind,
                recordId.toString(),
                json.write(snapshot),
                Timestamp.from(now));
    }

    @Override
    public List<Change> changes(String tenantId, Long beforeRevision, int limit) {
        return SqlRows.map(
                sqlMapper.changes(
                        tenantId,
                        beforeRevision == null ? Long.MAX_VALUE : beforeRevision,
                        limit + 1),
                row ->
                        new Change(
                                row.getLong("revision"),
                                row.getString("actor"),
                                row.getString("kind"),
                                UUID.fromString(row.getString("record_id")),
                                row.getString("snapshot_json"),
                                row.getTimestamp("occurred_at").toInstant()));
    }

    private static Function<SqlRow, OrganizationUnit> unitMapper() {
        return row ->
                new OrganizationUnit(
                        uuid(row.getString("id")),
                        OrganizationUnit.Kind.valueOf(row.getString("kind")),
                        row.getString("name"),
                        uuid(row.getString("legal_entity_id")),
                        uuid(row.getString("parent_department_id")),
                        row.getBoolean("active"),
                        row.getLong("revision"),
                        uuid(row.getString("head_appointment_id")));
    }

    private static Function<SqlRow, OrganizationPerson> personMapper() {
        return row ->
                new OrganizationPerson(
                        uuid(row.getString("id")),
                        row.getString("subject"),
                        row.getString("display_name"),
                        row.getBoolean("active"),
                        row.getBoolean("approval_eligible"),
                        row.getLong("revision"));
    }

    private static Function<SqlRow, OrganizationAppointment> appointmentMapper() {
        return row ->
                new OrganizationAppointment(
                        uuid(row.getString("id")),
                        uuid(row.getString("person_id")),
                        uuid(row.getString("department_id")),
                        uuid(row.getString("position_id")),
                        row.getBoolean("active"),
                        row.getLong("revision"),
                        uuid(row.getString("supervisor_appointment_id")));
    }

    private static UUID uuid(String id) { return id == null ? null : UUID.fromString(id); }

    private static String id(UUID id) { return id == null ? null : id.toString(); }

    private static void changed(int affected) { if (affected != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Organization record changed concurrently"); }
}
