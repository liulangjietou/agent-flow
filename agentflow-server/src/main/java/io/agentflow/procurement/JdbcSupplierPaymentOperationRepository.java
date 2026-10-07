package io.agentflow.procurement;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.PaymentObservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原授权只允许一个银行命令，命令原文、来源领取和所有结果修订永久保留。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPaymentOperationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierPaymentExecutionRepository requests;
    private final JdbcSupplierPayableHoldRepository holds;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;

    /** 三份持久原件分别核验，不能用看似合法的指令替换实际出纳选择或财务授权。 */
    public JdbcSupplierPaymentOperationRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierPaymentExecutionRepository requests,
            JdbcSupplierPayableHoldRepository holds, JdbcSupplierPaymentAuthorizationRepository authorizations) {
        this.jdbc = jdbc; this.json = json; this.requests = requests; this.holds = holds; this.authorizations = authorizations;
    }

    /** 银行队列只能从当前有效领取创建，并与意图 READY 在同一上层事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierPaymentExecutionRequest request, SupplierPayableHoldOperation original, SupplierPaymentOperation value) {
        var command = value.command(); var input = request.input();
        if (!request.equals(requests.find(input.tenantId(), input.id()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict))
                || !original.equals(holds.find(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict))
                || !command.holdCommand().authorization().equals(authorizations.find(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict))
                || !command.registeredFrom(original) || request.ready(command, value.createdAt()).status() != SupplierPaymentExecutionRequest.Status.READY
                || !value.equals(SupplierPaymentOperation.queue(command, value.createdAt()))) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO supplier_payment_operation(trace_id,tenant_id,id,execution_request_id,execution_request_version,hold_version,command_json,command_digest,state_json,
                    version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at)
                    VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?)
                    """, DiagnosticContext.capture().traceId(), command.tenantId(), command.id().toString(), input.id().toString(), request.version(), original.version(), json.write(command), command.digest(), json.write(value),
                    timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("SUPPLIER_PAYMENT_ALREADY_REGISTERED", "Original supplier authorization already has an immutable bank command"); }
        append(value);
    }

    /** 前版及原命令共同参与条件更新，迟到响应无法替换已保存的指令或新结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPaymentOperation value) {
        var before = find(value.command().tenantId(), value.command().id()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
        if (before.version() != value.version() - 1 || before.conflictingObservation() != null && value.conflictingObservation() == null) {
            throw conflict();
        }
        persist(value);
    }

    /** 裁决与清除争议只能原子保存，普通更新没有解除争议的权限。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentOperation resolve(SupplierPaymentDisputeResolution decision) {
        var locked = jdbc.queryForList("SELECT id FROM supplier_payment_operation WHERE tenant_id=? AND id=? FOR UPDATE",
                String.class, decision.tenantId(), decision.paymentId().toString());
        if (locked.isEmpty()) throw conflict();
        var before = find(decision.tenantId(), decision.paymentId()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
        var after = decision.resolve(before, resolutionHistory(before));
        persist(after);
        jdbc.update("""
                INSERT INTO supplier_payment_dispute_resolution(tenant_id,id,payment_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, decision.tenantId(), decision.id().toString(), decision.paymentId().toString(), decision.disputedVersion(), decision.resolvedVersion(),
                decision.observation().status().name(), decision.resolvedBy(), preciseTimestamp(decision.observation().observedAt()), preciseTimestamp(decision.resolvedAt()), json.write(decision));
        return after;
    }

    /** 当前付款及完整连续历史共同决定是否曾到账或退回，后续回执不能覆盖这些事实。 */
    public SupplierPaymentOperation.ResolutionHistory resolutionHistory(String tenant, UUID id) {
        return resolutionHistory(find(tenant, id).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict));
    }

    /** 最后裁决仍核对两端原修订及当时历史；后来新的查询不改写旧决定。 */
    public Optional<SupplierPaymentDisputeResolution> latestResolution(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payment_dispute_resolution WHERE tenant_id=? AND payment_id=? ORDER BY resolved_version DESC LIMIT 1", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentDisputeResolution.class);
            if (!tenant.equals(value.tenantId()) || !id.equals(value.paymentId()) || !value.id().toString().equals(row.getString("id"))
                    || value.disputedVersion() != row.getLong("disputed_version") || value.resolvedVersion() != row.getLong("resolved_version")
                    || !value.observation().status().name().equals(row.getString("outcome")) || !value.resolvedBy().equals(row.getString("resolved_by"))
                    || !value.observation().observedAt().truncatedTo(ChronoUnit.MICROS).equals(instant(row.getTimestamp("observed_at")))
                    || !value.resolvedAt().truncatedTo(ChronoUnit.MICROS).equals(instant(row.getTimestamp("resolved_at")))) throw conflict();
            var before = revision(tenant, id, value.disputedVersion()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
            var after = revision(tenant, id, value.resolvedVersion()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
            if (!value.resolve(before, resolutionHistory(before)).equals(after)) throw conflict();
            return value;
        }, tenant, id.toString()).stream().findFirst();
    }

    private SupplierPaymentOperation.ResolutionHistory resolutionHistory(SupplierPaymentOperation current) {
        var command = current.command();
        return jdbc.query("SELECT version,state_json FROM supplier_payment_revision WHERE tenant_id=? AND operation_id=? AND version<=? ORDER BY version", rows -> {
            PaymentObservation firstSuccess = null, firstReturn = null; boolean fundingObserved = false;
            long version = 0; SupplierPaymentOperation previous = null;
            while (rows.next()) {
                var value = json.read(rows.getString("state_json"), SupplierPaymentOperation.class);
                if (++version != rows.getLong("version") || value.version() != version || !value.command().equals(command)) throw conflict();
                if (firstSuccess == null && value.status() == SupplierPaymentOperation.Status.SUCCEEDED) firstSuccess = value.observation();
                if (firstReturn == null && value.status() == SupplierPaymentOperation.Status.REVERSED) firstReturn = value.observation();
                fundingObserved |= funding(value.observation()) || funding(value.conflictingObservation()); previous = value;
            }
            if (!current.equals(previous)) throw conflict();
            return new SupplierPaymentOperation.ResolutionHistory(firstSuccess, firstReturn, fundingObserved);
        }, command.tenantId(), command.id().toString(), current.version());
    }

    private static boolean funding(PaymentObservation value) {
        return value != null && (value.status() == PaymentObservation.Status.SUCCEEDED || value.status() == PaymentObservation.Status.REVERSED);
    }
    // 外部回执保留原始纳秒；仅关系列主动截断，避免数据库四舍五入改写比较依据。
    private static Timestamp preciseTimestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }

    private void persist(SupplierPaymentOperation value) {
        var command = value.command();
        int changed = jdbc.update("""
                UPDATE supplier_payment_operation SET state_json=?,version=?,status=?,attempts=?,dispatches=?,highest_revision=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND command_json=? AND command_digest=?
                """, json.write(value), value.version(), value.status().name(), value.attempts(), value.dispatches(), value.highestRevision(), timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()), command.tenantId(), command.id().toString(), value.version() - 1, json.write(command), command.digest());
        if (changed != 1) throw conflict(); append(value);
    }

    /** 恢复时对照原授权、实际领取和原预留修订，不能仅信任当前状态字符串。 */
    public Optional<SupplierPaymentOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payment_operation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 后续结算按已保存的精确银行修订引用到账，不依赖可变化的当前指针。 */
    public Optional<SupplierPaymentOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_payment_revision WHERE tenant_id=? AND operation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentOperation.class);
            if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != version) throw conflict(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 回款固定首次实际成功修订，后续退回、查询或裁决不替换这一原始资金来源。 */
    public Optional<SupplierPaymentOperation> firstSuccessfulRevision(String tenant, UUID id) {
        var current = find(tenant, id).orElse(null);
        if (current == null) return Optional.empty();
        var history = resolutionHistory(current);
        if (history.firstSuccess() == null) return Optional.empty();
        return jdbc.query("SELECT version,state_json FROM supplier_payment_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPaymentOperation.class);
            if (value.version() != row.getLong("version") || !value.command().equals(current.command())) throw conflict();
            return value;
        }, tenant, id.toString()).stream().filter(value -> value.status() == SupplierPaymentOperation.Status.SUCCEEDED).findFirst();
    }

    /** 仅扫描到期队列或租约；已到账、查无和争议等待后续明确处理。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id,trace_id FROM supplier_payment_operation WHERE (status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?)
                OR (status IN ('CHECKING','SENDING','QUERYING') AND lease_until<=?) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), timestamp(now), timestamp(now));
    }

    private SupplierPaymentOperation restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), SupplierPaymentOperation.class); var command = value.command();
        if (!command.equals(json.read(row.getString("command_json"), SupplierPaymentCommand.class)) || !command.digest().equals(row.getString("command_digest"))
                || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || value.dispatches() != row.getInt("dispatches") || value.highestRevision() != row.getLong("highest_revision")
                || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) {
            throw new IllegalStateException("Persisted supplier bank operation is inconsistent");
        }
        var request = requests.revision(command.tenantId(), UUID.fromString(row.getString("execution_request_id")), row.getLong("execution_request_version")).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
        var original = holds.revision(command.tenantId(), command.id(), row.getLong("hold_version")).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict);
        if (!command.registeredFrom(original) || request.ready(command, value.createdAt()).status() != SupplierPaymentExecutionRequest.Status.READY
                || !command.holdCommand().authorization().equals(authorizations.find(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPaymentOperationRepository::conflict))) throw conflict();
        return value;
    }
    private void append(SupplierPaymentOperation value) {
        jdbc.update("INSERT INTO supplier_payment_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)", value.command().tenantId(), value.command().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Supplier bank command or persisted execution source changed"); }

    /**
     * 不带金额和账户的后台扫描标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) {
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
