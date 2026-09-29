package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
 * 冲销操作与全部执行修订独立持久化，原凭证只允许绑定一份原命令。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherReversalOperationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherOperationRepository originals;
    /** 仓储核对被消费的准备和原修订，不信任调用方传入的过账声明。 */
    public JdbcVoucherReversalOperationRepository(JdbcTemplate jdbc, JsonUtil json, JdbcVoucherReversalPreparationRepository preparations, JdbcVoucherOperationRepository originals) {
        this.jdbc = jdbc; this.json = json; this.preparations = preparations; this.originals = originals;
    }
    /** 原件冻结与命令登记由同一事务完成；同一原件的并发授权只能成功一次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherReversalOperation value) {
        var input = value.input(); var command = input.command(); var original = command.source().command();
        if (value.version() != 1 || value.status() != VoucherReversalOperation.Status.QUEUED || value.attempts() != 0) throw conflict();
        var prepared = preparations.find(original.tenantId(), command.id()).orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        var source = originals.revision(original.tenantId(), original.id(), input.originalVersion()).orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        if (prepared.status() != VoucherReversalPreparation.Status.AUTHORIZED || !command.equals(prepared.command())
                || !prepared.input().targetDigest().equals(input.targetDigest()) || prepared.input().operationVersion() != input.originalVersion()
                || !source.usablePosted() || !source.input().command().equals(original) || !source.input().targetDigest().equals(input.targetDigest())
                || !originals.find(original.tenantId(), original.id()).filter(source::equals).isPresent()) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO voucher_reversal_operation(tenant_id,id,operation_id,original_version,preparation_version,input_json,command_digest,state_json,
                    version,status,attempts,highest_revision,created_at,updated_at,next_attempt_at)
                    VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',0,0,?,?,?)
                    """, original.tenantId(), command.id().toString(), original.id().toString(), input.originalVersion(), prepared.version(), json.write(input), command.digest(), json.write(value),
                    timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("VOUCHER_REVERSAL_OPERATION_EXISTS", "The original voucher already has an immutable reversal command"); }
        append(value);
    }
    /** 乐观版本和固定输入保护租约，迟到结果不能覆盖新执行者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherReversalOperation value) {
        var command = value.input().command();
        int count = jdbc.update("""
                UPDATE voucher_reversal_operation SET version=?,status=?,attempts=?,highest_revision=?,state_json=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND command_digest=?
                """, value.version(), value.status().name(), value.attempts(), value.highestRevision(), json.write(value), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()),
                command.source().command().tenantId(), command.id().toString(), value.version() - 1, json.write(value.input()), command.digest());
        if (count != 1) throw conflict(); append(value);
    }
    /** 只按持久原租户查找，不提供跨租户操作号旁路。 */
    public Optional<VoucherReversalOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM voucher_reversal_operation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 原件到独立命令唯一绑定，失败不会删除原操作或允许换编号。 */
    public Optional<VoucherReversalOperation> forOriginal(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM voucher_reversal_operation WHERE tenant_id=? AND operation_id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 每批十项，过期领取交给状态机转为原编号查询。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM voucher_reversal_operation WHERE (status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?)
                OR (status IN ('POSTING','QUERYING') AND lease_until<=?) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now), timestamp(now));
    }
    private RowMapper<VoucherReversalOperation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), VoucherReversalOperation.class); var input = value.input(); var command = input.command(); var original = command.source().command();
            if (!input.equals(json.read(row.getString("input_json"), VoucherReversalOperation.Input.class)) || !command.digest().equals(row.getString("command_digest"))
                    || !original.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                    || !original.id().toString().equals(row.getString("operation_id")) || input.originalVersion() != row.getLong("original_version")
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts") || value.highestRevision() != row.getLong("highest_revision")
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) {
                throw new IllegalStateException("Persisted reversal operation identity is inconsistent");
            }
            return value;
        };
    }
    private void append(VoucherReversalOperation value) {
        var command = value.input().command(); jdbc.update("INSERT INTO voucher_reversal_operation_revision(tenant_id,reversal_id,version,state_json) VALUES(?,?,?,?)",
                command.source().command().tenantId(), command.id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Reversal command, consumed preparation or original voucher changed"); }
    /**
     * 扫描不读取完整会计或支付内容。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
