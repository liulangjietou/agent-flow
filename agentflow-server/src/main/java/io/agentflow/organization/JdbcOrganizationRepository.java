package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组织关系按实体存储，复用应用事务和数据库租户外键，不保存整租户可变 JSON 配置。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 注入原业务数据源与统一 JSON 组件。 */
    public JdbcOrganizationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public void initialize(String tenantId, String actor, Instant now) {
        try {
            jdbc.update("INSERT INTO organization_directory(tenant_id,revision,initialized_by,initialized_at) VALUES (?,1,?,?)",
                    tenantId, actor, Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new DomainException("ORGANIZATION_ALREADY_INITIALIZED", "Local organization is already initialized");
        }
    }

    @Override
    public boolean initialized(String tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organization_directory WHERE tenant_id=?)", Boolean.class, tenantId));
    }

    @Override
    public long revision(String tenantId) {
        return jdbc.queryForList("SELECT revision FROM organization_directory WHERE tenant_id=?", Long.class, tenantId)
                .stream().findFirst().orElse(0L);
    }

    @Override
    public long lock(String tenantId) {
        return jdbc.queryForList("SELECT revision FROM organization_directory WHERE tenant_id=? FOR UPDATE", Long.class, tenantId)
                .stream().findFirst().orElseThrow(() -> new DomainException("ORGANIZATION_NOT_INITIALIZED", "Initialize local organization before editing"));
    }

    @Override
    public Optional<OrganizationUnit> unit(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM organization_unit WHERE tenant_id=? AND id=?", unitMapper(), tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public List<OrganizationUnit> units(String tenantId, OrganizationUnit.Kind kind, String afterId, int limit) {
        return jdbc.query("SELECT * FROM organization_unit WHERE tenant_id=? AND kind=? AND id>? ORDER BY id LIMIT ?",
                unitMapper(), tenantId, kind.name(), afterId, limit + 1);
    }

    @Override
    public void save(String tenantId, OrganizationUnit value, long expectedRevision) {
        if (expectedRevision == 0) {
            jdbc.update("INSERT INTO organization_unit(tenant_id,id,kind,name,legal_entity_id,parent_department_id,active,revision) VALUES (?,?,?,?,?,?,?,?)",
                    tenantId, value.id().toString(), value.kind().name(), value.name(), id(value.legalEntityId()), id(value.parentDepartmentId()), value.active(), value.revision());
        } else changed(jdbc.update("UPDATE organization_unit SET name=?,parent_department_id=?,active=?,revision=?,head_appointment_id=? WHERE tenant_id=? AND id=? AND revision=?",
                value.name(), id(value.parentDepartmentId()), value.active(), value.revision(), id(value.headAppointmentId()), tenantId, value.id().toString(), expectedRevision));
    }

    @Override
    public Optional<OrganizationPerson> person(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM organization_person WHERE tenant_id=? AND id=?", personMapper(), tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<OrganizationPerson> personBySubject(String tenantId, String subject) {
        return jdbc.query("SELECT * FROM organization_person WHERE tenant_id=? AND subject=?", personMapper(), tenantId, subject).stream().findFirst();
    }

    @Override
    public List<OrganizationPerson> people(String tenantId, String afterId, int limit) {
        return jdbc.query("SELECT * FROM organization_person WHERE tenant_id=? AND id>? ORDER BY id LIMIT ?", personMapper(), tenantId, afterId, limit + 1);
    }

    @Override
    public void save(String tenantId, OrganizationPerson value, long expectedRevision) {
        try {
            if (expectedRevision == 0) {
                jdbc.update("INSERT INTO organization_person(tenant_id,id,subject,display_name,active,approval_eligible,revision) VALUES (?,?,?,?,?,?,?)",
                        tenantId, value.id().toString(), value.subject(), value.displayName(), value.active(), value.approvalEligible(), value.revision());
            } else changed(jdbc.update("UPDATE organization_person SET display_name=?,active=?,approval_eligible=?,revision=? WHERE tenant_id=? AND id=? AND revision=?",
                    value.displayName(), value.active(), value.approvalEligible(), value.revision(), tenantId, value.id().toString(), expectedRevision));
        } catch (DuplicateKeyException exception) { throw new DomainException("ORGANIZATION_IDENTITY_CONFLICT", "The identity already belongs to a person in this tenant"); }
    }

    @Override
    public Optional<OrganizationAppointment> appointment(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM organization_appointment WHERE tenant_id=? AND id=?", appointmentMapper(), tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<OrganizationAppointment> appointmentByIdentity(String tenantId, UUID personId, UUID departmentId, UUID positionId) {
        return jdbc.query("SELECT * FROM organization_appointment WHERE tenant_id=? AND person_id=? AND department_id=? AND position_id=?",
                appointmentMapper(), tenantId, personId.toString(), departmentId.toString(), positionId.toString()).stream().findFirst();
    }

    @Override
    public List<OrganizationAppointment> appointments(String tenantId, UUID personId, String afterId, int limit) {
        if (personId == null) return jdbc.query("SELECT * FROM organization_appointment WHERE tenant_id=? AND id>? ORDER BY id LIMIT ?", appointmentMapper(), tenantId, afterId, limit + 1);
        return jdbc.query("SELECT * FROM organization_appointment WHERE tenant_id=? AND person_id=? AND id>? ORDER BY id LIMIT ?", appointmentMapper(), tenantId, personId.toString(), afterId, limit + 1);
    }

    @Override
    public void save(String tenantId, OrganizationAppointment value, long expectedRevision) {
        try {
            if (expectedRevision == 0) {
                jdbc.update("INSERT INTO organization_appointment(tenant_id,id,person_id,department_id,position_id,active,revision) VALUES (?,?,?,?,?,?,?)",
                        tenantId, value.id().toString(), value.personId().toString(), value.departmentId().toString(), value.positionId().toString(), value.active(), value.revision());
            } else changed(jdbc.update("UPDATE organization_appointment SET active=?,revision=?,supervisor_appointment_id=? WHERE tenant_id=? AND id=? AND revision=?",
                    value.active(), value.revision(), id(value.supervisorAppointmentId()), tenantId, value.id().toString(), expectedRevision));
        } catch (DuplicateKeyException exception) { throw new DomainException("ORGANIZATION_APPOINTMENT_CONFLICT", "This appointment already exists; update its active state instead"); }
    }

    @Override
    public void recordChange(String tenantId, long previousRevision, String actor, String kind, UUID recordId, Object snapshot, Instant now) {
        changed(jdbc.update("UPDATE organization_directory SET revision=revision+1 WHERE tenant_id=? AND revision=?", tenantId, previousRevision));
        jdbc.update("INSERT INTO organization_change(tenant_id,revision,actor,kind,record_id,snapshot_json,occurred_at) VALUES (?,?,?,?,?,?,?)",
                tenantId, previousRevision + 1, actor, kind, recordId.toString(), json.write(snapshot), Timestamp.from(now));
    }

    @Override
    public List<Change> changes(String tenantId, Long beforeRevision, int limit) {
        return jdbc.query("SELECT * FROM organization_change WHERE tenant_id=? AND revision<? ORDER BY revision DESC LIMIT ?",
                (row, index) -> new Change(row.getLong("revision"), row.getString("actor"), row.getString("kind"), UUID.fromString(row.getString("record_id")),
                        row.getString("snapshot_json"), row.getTimestamp("occurred_at").toInstant()), tenantId, beforeRevision == null ? Long.MAX_VALUE : beforeRevision, limit + 1);
    }

    private static RowMapper<OrganizationUnit> unitMapper() {
        return (row, index) -> new OrganizationUnit(uuid(row.getString("id")), OrganizationUnit.Kind.valueOf(row.getString("kind")), row.getString("name"),
                uuid(row.getString("legal_entity_id")), uuid(row.getString("parent_department_id")), row.getBoolean("active"), row.getLong("revision"), uuid(row.getString("head_appointment_id")));
    }
    private static RowMapper<OrganizationPerson> personMapper() {
        return (row, index) -> new OrganizationPerson(uuid(row.getString("id")), row.getString("subject"), row.getString("display_name"),
                row.getBoolean("active"), row.getBoolean("approval_eligible"), row.getLong("revision"));
    }
    private static RowMapper<OrganizationAppointment> appointmentMapper() {
        return (row, index) -> new OrganizationAppointment(uuid(row.getString("id")), uuid(row.getString("person_id")), uuid(row.getString("department_id")),
                uuid(row.getString("position_id")), row.getBoolean("active"), row.getLong("revision"), uuid(row.getString("supervisor_appointment_id")));
    }
    private static UUID uuid(String id) { return id == null ? null : UUID.fromString(id); }
    private static String id(UUID id) { return id == null ? null : id.toString(); }
    private static void changed(int affected) { if (affected != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Organization record changed concurrently"); }
}
