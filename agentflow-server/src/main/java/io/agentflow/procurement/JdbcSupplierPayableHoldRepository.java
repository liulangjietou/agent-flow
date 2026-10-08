package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.procurement.mapper.SupplierPayableHoldRepositoryMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 预留队列、领取租约及逐版外部事实持久保存，命令原文和摘要不能被重试替换。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPayableHoldRepository {
    private final SupplierPayableHoldRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;

    /** 队列必须引用同事务保存的真实授权。 */
    public JdbcSupplierPayableHoldRepository(
            SupplierPayableHoldRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcSupplierPaymentAuthorizationRepository authorizations) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.authorizations = authorizations;
    }

    /** 初始队列与首个修订原子保存，失败不会留下可由后台发送的孤立记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPayableHoldOperation value) {
        var command = value.command();
        if (!authorizations
                        .find(command.tenantId(), command.id())
                        .orElseThrow(JdbcSupplierPayableHoldRepository::conflict)
                        .equals(command.authorization())
                || !SupplierPayableHoldOperation.queue(command, value.createdAt()).equals(value))
            throw conflict();
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                command.tenantId(),
                command.id().toString(),
                json.write(command),
                command.digest(),
                json.write(value),
                timestamp(value.createdAt()),
                timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()));
        append(value);
    }

    /** 比较原命令及上一版本后更新；迟到工作进程不能覆盖已领取或已确认的新版本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPayableHoldOperation value) {
        var command = value.command();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        value.dispatches(),
                        value.highestRevision(),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        command.tenantId(),
                        command.id().toString(),
                        value.version() - 1,
                        json.write(command),
                        command.digest());
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 对照关系列和原命令恢复，持久化矛盾不能被页面解释为真实预留成功。 */
    public Optional<SupplierPayableHoldOperation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                .findFirst();
    }

    /** 后续付款固定已保存的预留修订，后来的查询不覆盖其原会计依据。 */
    public Optional<SupplierPayableHoldOperation> revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierPayableHoldOperation.class);
                            if (!value.command().tenantId().equals(tenant)
                                    || !value.command().id().equals(id)
                                    || value.version() != version)
                                throw new IllegalStateException(
                                        "Persisted supplier hold revision identity is"
                                            + " inconsistent");
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 每轮只领取有限标识，后台扫描不加载供应商或票面内容。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private SupplierPayableHoldOperation restore(SqlRow row) {
        var value = json.read(row.getString("state_json"), SupplierPayableHoldOperation.class); var command = value.command();
        if (!command.equals(json.read(row.getString("command_json"), SupplierPayableHoldCommand.class)) || !command.digest().equals(row.getString("command_digest"))
                || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || value.dispatches() != row.getInt("dispatches") || value.highestRevision() != row.getLong("highest_revision")
                || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) {
            throw new IllegalStateException("Persisted supplier payable hold identity is inconsistent");
        }
        return value;
    }

    private void append(SupplierPayableHoldOperation value) {
        sqlMapper.append(
                value.command().tenantId(),
                value.command().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier payable hold command, authorization or version changed"); }

    /**
     * 持久任务标识不含业务明细。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务关联不存在时保持空值，不借用当前审批轮次或工作线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
