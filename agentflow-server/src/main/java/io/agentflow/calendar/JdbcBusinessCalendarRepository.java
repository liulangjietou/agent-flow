package io.agentflow.calendar;

import io.agentflow.calendar.mapper.BusinessCalendarRepositoryMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 当前日历指针与不可变版本同事务保存；所有读写带租户条件。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBusinessCalendarRepository implements BusinessCalendarRepository {
    private final BusinessCalendarRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 注入持久化和统一 JSON 编解码。 */
    public JdbcBusinessCalendarRepository(
            BusinessCalendarRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    @Transactional
    public void create(BusinessCalendar calendar) {
        try {
            sqlMapper.create(
                    calendar.id().toString(),
                    calendar.tenantId(),
                    calendar.key(),
                    calendar.name(),
                    calendar.rules().zoneId(),
                    calendar.revision(),
                    calendar.updatedBy(),
                    Timestamp.from(calendar.updatedAt()));
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "CALENDAR_KEY_CONFLICT",
                    "A calendar with this key already exists in the tenant");
        }
        appendVersion(calendar);
    }

    @Override
    @Transactional
    public void update(BusinessCalendar calendar, long expectedRevision) {
        int affected =
                sqlMapper.update(
                        calendar.name(),
                        calendar.rules().zoneId(),
                        calendar.revision(),
                        calendar.updatedBy(),
                        Timestamp.from(calendar.updatedAt()),
                        calendar.tenantId(),
                        calendar.id().toString(),
                        expectedRevision);
        if (affected != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT", "Calendar changed before this revision was saved");
        appendVersion(calendar);
    }

    private void appendVersion(BusinessCalendar calendar) {
        sqlMapper.appendVersion(
                calendar.tenantId(),
                calendar.id().toString(),
                calendar.key(),
                calendar.name(),
                calendar.rules().zoneId(),
                calendar.revision(),
                json.write(calendar.rules()),
                calendar.updatedBy(),
                Timestamp.from(calendar.updatedAt()));
    }

    @Override
    public Optional<BusinessCalendar> find(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.find(tenantId, id.toString()), calendarMapper()).stream()
                .findFirst();
    }

    @Override
    public Optional<BusinessCalendar> findVersion(String tenantId, UUID id, long revision) {
        return SqlRows.map(
                        sqlMapper.findVersion(tenantId, id.toString(), revision), calendarMapper())
                .stream()
                .findFirst();
    }

    @Override
    public List<Summary> list(String tenantId, String afterKey, int limit) {
        return SqlRows.map(
                sqlMapper.list(tenantId, afterKey == null ? "" : afterKey, limit + 1),
                summaryMapper());
    }

    @Override
    public List<Summary> versions(String tenantId, UUID id, Long beforeRevision, int limit) {
        return SqlRows.map(
                sqlMapper.versions(
                        tenantId,
                        id.toString(),
                        beforeRevision == null ? Long.MAX_VALUE : beforeRevision,
                        limit + 1),
                summaryMapper());
    }

    private Function<SqlRow, BusinessCalendar> calendarMapper() {
        return row ->
                new BusinessCalendar(
                        UUID.fromString(row.getString("calendar_id")),
                        row.getString("tenant_id"),
                        row.getString("calendar_key"),
                        row.getString("name"),
                        row.getLong("revision"),
                        json.read(row.getString("rules_json"), CalendarRules.class),
                        row.getString("updated_by"),
                        row.getTimestamp("updated_at").toInstant());
    }

    private Function<SqlRow, Summary> summaryMapper() {
        return row ->
                new Summary(
                        UUID.fromString(row.getString("id")),
                        row.getString("calendar_key"),
                        row.getString("name"),
                        row.getString("zone_id"),
                        row.getLong("revision"),
                        row.getString("updated_by"),
                        row.getTimestamp("updated_at").toInstant());
    }
}
