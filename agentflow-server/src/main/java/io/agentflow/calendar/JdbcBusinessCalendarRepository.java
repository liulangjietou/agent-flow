package io.agentflow.calendar;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 当前日历指针与不可变版本同事务保存；所有读写带租户条件。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBusinessCalendarRepository implements BusinessCalendarRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 注入持久化和统一 JSON 编解码。 */
    public JdbcBusinessCalendarRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    @Transactional
    public void create(BusinessCalendar calendar) {
        try {
            jdbc.update("""
                    INSERT INTO business_calendar (id,tenant_id,calendar_key,name,zone_id,revision,updated_by,updated_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, calendar.id().toString(), calendar.tenantId(), calendar.key(), calendar.name(), calendar.rules().zoneId(),
                    calendar.revision(), calendar.updatedBy(), Timestamp.from(calendar.updatedAt()));
        } catch (DuplicateKeyException exception) {
            throw new DomainException("CALENDAR_KEY_CONFLICT", "A calendar with this key already exists in the tenant");
        }
        appendVersion(calendar);
    }

    @Override
    @Transactional
    public void update(BusinessCalendar calendar, long expectedRevision) {
        int affected = jdbc.update("""
                UPDATE business_calendar SET name=?,zone_id=?,revision=?,updated_by=?,updated_at=?
                WHERE tenant_id=? AND id=? AND revision=?
                """, calendar.name(), calendar.rules().zoneId(), calendar.revision(), calendar.updatedBy(), Timestamp.from(calendar.updatedAt()),
                calendar.tenantId(), calendar.id().toString(), expectedRevision);
        if (affected != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Calendar changed before this revision was saved");
        appendVersion(calendar);
    }

    private void appendVersion(BusinessCalendar calendar) {
        jdbc.update("""
                INSERT INTO business_calendar_version (tenant_id,calendar_id,calendar_key,name,zone_id,revision,rules_json,updated_by,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, calendar.tenantId(), calendar.id().toString(), calendar.key(), calendar.name(), calendar.rules().zoneId(), calendar.revision(),
                json.write(calendar.rules()), calendar.updatedBy(), Timestamp.from(calendar.updatedAt()));
    }

    @Override
    public Optional<BusinessCalendar> find(String tenantId, UUID id) {
        return jdbc.query("""
                SELECT v.* FROM business_calendar c JOIN business_calendar_version v
                ON v.tenant_id=c.tenant_id AND v.calendar_id=c.id AND v.revision=c.revision
                WHERE c.tenant_id=? AND c.id=?
                """, calendarMapper(), tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<BusinessCalendar> findVersion(String tenantId, UUID id, long revision) {
        return jdbc.query("SELECT * FROM business_calendar_version WHERE tenant_id=? AND calendar_id=? AND revision=?",
                calendarMapper(), tenantId, id.toString(), revision).stream().findFirst();
    }

    @Override
    public List<Summary> list(String tenantId, String afterKey, int limit) {
        return jdbc.query("SELECT id,calendar_key,name,zone_id,revision,updated_by,updated_at FROM business_calendar WHERE tenant_id=? AND calendar_key>? ORDER BY calendar_key LIMIT ?",
                summaryMapper(), tenantId, afterKey == null ? "" : afterKey, limit + 1);
    }

    @Override
    public List<Summary> versions(String tenantId, UUID id, Long beforeRevision, int limit) {
        return jdbc.query("""
                SELECT calendar_id AS id,calendar_key,name,zone_id,revision,updated_by,updated_at FROM business_calendar_version
                WHERE tenant_id=? AND calendar_id=? AND revision<? ORDER BY revision DESC LIMIT ?
                """, summaryMapper(), tenantId, id.toString(), beforeRevision == null ? Long.MAX_VALUE : beforeRevision, limit + 1);
    }

    private RowMapper<BusinessCalendar> calendarMapper() {
        return (row, index) -> new BusinessCalendar(UUID.fromString(row.getString("calendar_id")), row.getString("tenant_id"), row.getString("calendar_key"),
                row.getString("name"), row.getLong("revision"), json.read(row.getString("rules_json"), CalendarRules.class), row.getString("updated_by"), row.getTimestamp("updated_at").toInstant());
    }

    private RowMapper<Summary> summaryMapper() {
        return (row, index) -> new Summary(UUID.fromString(row.getString("id")), row.getString("calendar_key"), row.getString("name"), row.getString("zone_id"),
                row.getLong("revision"), row.getString("updated_by"), row.getTimestamp("updated_at").toInstant());
    }
}
