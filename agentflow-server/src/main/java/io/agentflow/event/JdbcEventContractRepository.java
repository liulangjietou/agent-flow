package io.agentflow.event;

import io.agentflow.common.DomainException;
import io.agentflow.event.mapper.EventContractRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 用条件更新串行分配发布版本；启停只修改可用性列，历史写入失败时整个操作回滚。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcEventContractRepository implements EventContractRepository {
    private final EventContractRepositoryMapper sqlMapper;

    /** 使用业务数据库的事务，不建立单独的事件配置存储。 */
    public JdbcEventContractRepository(EventContractRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    @Transactional
    public void publish(EventContract contract, long expectedVersion) {
        if (expectedVersion < 0
                || expectedVersion == Long.MAX_VALUE
                || contract.version() != expectedVersion + 1) throw conflict();
        if (expectedVersion == 0) {
            try {
                sqlMapper.publish(contract.tenantId(), contract.key());
            } catch (DuplicateKeyException duplicate) {
                throw conflict();
            }
        } else if (sqlMapper.publish2(
                        contract.version(), contract.tenantId(), contract.key(), expectedVersion)
                != 1) throw conflict();
        sqlMapper.publish3(
                contract.tenantId(),
                contract.key(),
                contract.version(),
                contract.name(),
                contract.sourceKey(),
                contract.eventType(),
                contract.envelopeVersion(),
                contract.publishedBy(),
                Timestamp.from(contract.publishedAt()),
                contract.publicationReason(),
                contract.publishedBy(),
                Timestamp.from(contract.publishedAt()),
                contract.publicationReason());
        appendHistory(EventContractAvailability.published(contract));
    }

    @Override
    @Transactional
    public void changeAvailability(EventContractAvailability value, long expectedRevision) {
        if (expectedRevision < 1
                || expectedRevision == Long.MAX_VALUE
                || value.revision() != expectedRevision + 1) throw conflict();
        int changed =
                sqlMapper.changeAvailability(
                        value.revision(),
                        value.enabled(),
                        value.changedBy(),
                        Timestamp.from(value.changedAt()),
                        value.reason(),
                        value.tenantId(),
                        value.key(),
                        value.contractVersion(),
                        expectedRevision,
                        value.enabled());
        if (changed != 1) throw conflict();
        appendHistory(value);
    }

    private void appendHistory(EventContractAvailability value) {
        sqlMapper.appendHistory(
                value.tenantId(),
                value.key(),
                value.contractVersion(),
                value.revision(),
                value.enabled(),
                value.changedBy(),
                Timestamp.from(value.changedAt()),
                value.reason());
    }

    @Override
    public Optional<Version> find(String tenantId, String key, long version) {
        return SqlRows.map(sqlMapper.find(tenantId, key, version), versionMapper()).stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Version> lockVersion(String tenantId, String key, long version) {
        return SqlRows.map(sqlMapper.lockVersion(tenantId, key, version), versionMapper()).stream()
                .findFirst();
    }

    @Override
    public Optional<Version> latest(String tenantId, String key) {
        return SqlRows.map(sqlMapper.latest(tenantId, key), versionMapper()).stream().findFirst();
    }

    @Override
    public List<Version> list(String tenantId, String afterKey, int limit) {
        return SqlRows.map(
                sqlMapper.list(tenantId, afterKey == null ? "" : afterKey, limit + 1),
                versionMapper());
    }

    @Override
    public List<Version> versions(String tenantId, String key, Long beforeVersion, int limit) {
        // 空游标不施加上界，不能用合法的最大版本代替“无限大”而漏掉该记录。

        Object[] arguments =
                beforeVersion == null
                        ? new Object[] {tenantId, key, limit + 1}
                        : new Object[] {tenantId, key, beforeVersion, limit + 1};
        return SqlRows.map(
                sqlMapper.versionsQuery(beforeVersion == null, arguments), versionMapper());
    }

    @Override
    public List<EventContractAvailability> history(
            String tenantId, String key, long version, Long beforeRevision, int limit) {

        Object[] arguments =
                beforeRevision == null
                        ? new Object[] {tenantId, key, version, limit + 1}
                        : new Object[] {tenantId, key, version, beforeRevision, limit + 1};
        return SqlRows.map(
                sqlMapper.historyQuery(beforeRevision == null, arguments),
                row ->
                        new EventContractAvailability(
                                row.getString("tenant_id"),
                                row.getString("contract_key"),
                                row.getLong("contract_version"),
                                row.getLong("revision"),
                                row.getBoolean("enabled"),
                                row.getString("changed_by"),
                                row.getTimestamp("changed_at").toInstant(),
                                row.getString("reason")));
    }

    private static Function<SqlRow, Version> versionMapper() {
        return row ->
                new Version(
                        new EventContract(
                                row.getString("tenant_id"),
                                row.getString("contract_key"),
                                row.getLong("contract_version"),
                                row.getString("name"),
                                row.getString("source_key"),
                                row.getString("event_type"),
                                row.getInt("envelope_version"),
                                row.getString("published_by"),
                                row.getTimestamp("published_at").toInstant(),
                                row.getString("publication_reason")),
                        new EventContractAvailability(
                                row.getString("tenant_id"),
                                row.getString("contract_key"),
                                row.getLong("contract_version"),
                                row.getLong("availability_revision"),
                                row.getBoolean("enabled"),
                                row.getString("changed_by"),
                                row.getTimestamp("changed_at").toInstant(),
                                row.getString("change_reason")));
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Event contract or availability changed"); }
}
