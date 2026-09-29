package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                    INSERT INTO supplier_payment_operation(tenant_id,id,execution_request_id,execution_request_version,hold_version,command_json,command_digest,state_json,
                    version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at)
                    VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?)
                    """, command.tenantId(), command.id().toString(), input.id().toString(), request.version(), original.version(), json.write(command), command.digest(), json.write(value),
                    timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("SUPPLIER_PAYMENT_ALREADY_REGISTERED", "Original supplier authorization already has an immutable bank command"); }
        append(value);
    }

    /** 前版及原命令共同参与条件更新，迟到响应无法替换已保存的指令或新结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPaymentOperation value) {
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

    /** 仅扫描到期队列或租约；已到账、查无和争议等待后续明确处理。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM supplier_payment_operation WHERE (status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?)
                OR (status IN ('CHECKING','SENDING','QUERYING') AND lease_until<=?) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now), timestamp(now));
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
    public record Candidate(String tenantId, UUID id) { }
}
