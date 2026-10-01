package io.agentflow.event;

import io.agentflow.common.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 用条件更新串行分配发布版本；启停只修改可用性列，历史写入失败时整个操作回滚。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcEventContractRepository implements EventContractRepository {
    private final JdbcTemplate jdbc;

    /** 使用业务数据库的事务，不建立单独的事件配置存储。 */
    public JdbcEventContractRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void publish(EventContract contract, long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || contract.version() != expectedVersion + 1) throw conflict();
        if (expectedVersion == 0) {
            try {
                jdbc.update("INSERT INTO event_contract (tenant_id,contract_key,latest_version) VALUES (?,?,1)", contract.tenantId(), contract.key());
            } catch (DuplicateKeyException duplicate) { throw conflict(); }
        } else if (jdbc.update("""
                UPDATE event_contract SET latest_version=? WHERE tenant_id=? AND contract_key=? AND latest_version=?
                """, contract.version(), contract.tenantId(), contract.key(), expectedVersion) != 1) throw conflict();
        jdbc.update("""
                INSERT INTO event_contract_version
                (tenant_id,contract_key,contract_version,name,source_key,event_type,envelope_version,published_by,published_at,publication_reason,
                 availability_revision,enabled,changed_by,changed_at,change_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,1,TRUE,?,?,?)
                """, contract.tenantId(), contract.key(), contract.version(), contract.name(), contract.sourceKey(), contract.eventType(),
                contract.envelopeVersion(), contract.publishedBy(), Timestamp.from(contract.publishedAt()), contract.publicationReason(),
                contract.publishedBy(), Timestamp.from(contract.publishedAt()), contract.publicationReason());
        appendHistory(EventContractAvailability.published(contract));
    }

    @Override
    @Transactional
    public void changeAvailability(EventContractAvailability value, long expectedRevision) {
        if (expectedRevision < 1 || expectedRevision == Long.MAX_VALUE || value.revision() != expectedRevision + 1) throw conflict();
        int changed = jdbc.update("""
                UPDATE event_contract_version SET availability_revision=?,enabled=?,changed_by=?,changed_at=?,change_reason=?
                WHERE tenant_id=? AND contract_key=? AND contract_version=? AND availability_revision=? AND enabled<>?
                """, value.revision(), value.enabled(), value.changedBy(), Timestamp.from(value.changedAt()), value.reason(), value.tenantId(),
                value.key(), value.contractVersion(), expectedRevision, value.enabled());
        if (changed != 1) throw conflict();
        appendHistory(value);
    }

    private void appendHistory(EventContractAvailability value) {
        jdbc.update("""
                INSERT INTO event_contract_availability_history
                (tenant_id,contract_key,contract_version,revision,enabled,changed_by,changed_at,reason) VALUES (?,?,?,?,?,?,?,?)
                """, value.tenantId(), value.key(), value.contractVersion(), value.revision(), value.enabled(), value.changedBy(),
                Timestamp.from(value.changedAt()), value.reason());
    }

    @Override
    public Optional<Version> find(String tenantId, String key, long version) {
        return jdbc.query("SELECT * FROM event_contract_version WHERE tenant_id=? AND contract_key=? AND contract_version=?",
                versionMapper(), tenantId, key, version).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Version> lockVersion(String tenantId, String key, long version) {
        return jdbc.query("SELECT * FROM event_contract_version WHERE tenant_id=? AND contract_key=? AND contract_version=? FOR UPDATE",
                versionMapper(), tenantId, key, version).stream().findFirst();
    }

    @Override
    public Optional<Version> latest(String tenantId, String key) {
        return jdbc.query("""
                SELECT v.* FROM event_contract c JOIN event_contract_version v
                ON v.tenant_id=c.tenant_id AND v.contract_key=c.contract_key AND v.contract_version=c.latest_version
                WHERE c.tenant_id=? AND c.contract_key=?
                """, versionMapper(), tenantId, key).stream().findFirst();
    }

    @Override
    public List<Version> list(String tenantId, String afterKey, int limit) {
        return jdbc.query("""
                SELECT v.* FROM event_contract c JOIN event_contract_version v
                ON v.tenant_id=c.tenant_id AND v.contract_key=c.contract_key AND v.contract_version=c.latest_version
                WHERE c.tenant_id=? AND c.contract_key>? ORDER BY c.contract_key LIMIT ?
                """, versionMapper(), tenantId, afterKey == null ? "" : afterKey, limit + 1);
    }

    @Override
    public List<Version> versions(String tenantId, String key, Long beforeVersion, int limit) {
        // 空游标不施加上界，不能用合法的最大版本代替“无限大”而漏掉该记录。
        String filter = beforeVersion == null ? "" : " AND contract_version<?";
        Object[] arguments = beforeVersion == null ? new Object[]{tenantId, key, limit + 1} : new Object[]{tenantId, key, beforeVersion, limit + 1};
        return jdbc.query("SELECT * FROM event_contract_version WHERE tenant_id=? AND contract_key=?" + filter
                + " ORDER BY contract_version DESC LIMIT ?", versionMapper(), arguments);
    }

    @Override
    public List<EventContractAvailability> history(String tenantId, String key, long version, Long beforeRevision, int limit) {
        String filter = beforeRevision == null ? "" : " AND revision<?";
        Object[] arguments = beforeRevision == null ? new Object[]{tenantId, key, version, limit + 1} : new Object[]{tenantId, key, version, beforeRevision, limit + 1};
        return jdbc.query("SELECT * FROM event_contract_availability_history WHERE tenant_id=? AND contract_key=? AND contract_version=?" + filter
                + " ORDER BY revision DESC LIMIT ?", (row, index) -> new EventContractAvailability(row.getString("tenant_id"), row.getString("contract_key"), row.getLong("contract_version"),
                row.getLong("revision"), row.getBoolean("enabled"), row.getString("changed_by"), row.getTimestamp("changed_at").toInstant(), row.getString("reason")),
                arguments);
    }

    private static RowMapper<Version> versionMapper() {
        return (row, index) -> new Version(new EventContract(row.getString("tenant_id"), row.getString("contract_key"), row.getLong("contract_version"),
                row.getString("name"), row.getString("source_key"), row.getString("event_type"), row.getInt("envelope_version"), row.getString("published_by"),
                row.getTimestamp("published_at").toInstant(), row.getString("publication_reason")),
                new EventContractAvailability(row.getString("tenant_id"), row.getString("contract_key"), row.getLong("contract_version"),
                        row.getLong("availability_revision"), row.getBoolean("enabled"), row.getString("changed_by"), row.getTimestamp("changed_at").toInstant(), row.getString("change_reason")));
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Event contract or availability changed"); }
}
