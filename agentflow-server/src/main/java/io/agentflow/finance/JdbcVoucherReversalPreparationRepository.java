package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
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
 * 冲销准备队列保留原件、日期及操作者，不把准备完成等同于批准发送。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherReversalPreparationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 仓储只处理本地事实，不执行外部资金或会计操作。 */
    public JdbcVoucherReversalPreparationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 意图先加入原申请事务，后台只处理已提交的原凭证。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherReversalPreparation value) {
        if (value.version() != 1 || value.status() != VoucherReversalPreparation.Status.QUEUED) throw conflict();
        var input = value.input();
        jdbc.update("""
                INSERT INTO voucher_reversal_preparation(tenant_id,id,operation_id,original_version,requested_by,input_json,state_json,version,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,1,'QUEUED',?,?)
                """, input.source().command().tenantId(), input.id().toString(), input.source().command().id().toString(), input.originalVersion(),
                input.requestedBy(), json.write(input), json.write(value), timestamp(input.requestedAt()), timestamp(value.updatedAt()));
        append(value);
    }
    /** 版本和原输入共同约束，迟到任务不能替换当前租约或重复授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherReversalPreparation value) {
        var input = value.input();
        int changed = jdbc.update("UPDATE voucher_reversal_preparation SET version=?,status=?,state_json=?,updated_at=?,lease_until=? WHERE tenant_id=? AND id=? AND version=? AND input_json=?",
                value.version(), value.status().name(), json.write(value), timestamp(value.updatedAt()), timestamp(value.leaseUntil()), input.source().command().tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 按租户定位精确查询，不从编号推断业务授权。 */
    public Optional<VoucherReversalPreparation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM voucher_reversal_preparation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 财务只办理自己对这笔原凭证最新明确发起的复核。 */
    public Optional<VoucherReversalPreparation> latest(String tenant, UUID operationId, String actor) {
        return jdbc.query("SELECT * FROM voucher_reversal_preparation WHERE tenant_id=? AND operation_id=? AND requested_by=? ORDER BY created_at DESC,id DESC LIMIT 1", row(), tenant, operationId.toString(), actor).stream().findFirst();
    }
    /** 每次只领取有限数量，并由应用服务处理超时旧租约。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id FROM voucher_reversal_preparation WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY created_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now));
    }
    private RowMapper<VoucherReversalPreparation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), VoucherReversalPreparation.class); var input = value.input();
            if (!input.source().command().tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.source().command().id().toString().equals(row.getString("operation_id")) || input.originalVersion() != row.getLong("original_version")
                    || !input.requestedBy().equals(row.getString("requested_by")) || !input.equals(json.read(row.getString("input_json"), VoucherReversalPreparation.Input.class))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw new IllegalStateException("Persisted voucher reversal preparation identity is inconsistent");
            return value;
        };
    }
    private void append(VoucherReversalPreparation value) { jdbc.update("INSERT INTO voucher_reversal_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,?,?)", value.input().source().command().tenantId(), value.input().id().toString(), value.version(), json.write(value)); }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Voucher reversal version or original input changed"); }
    /**
     * 扫描只携带租户与任务标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
