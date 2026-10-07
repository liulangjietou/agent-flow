package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
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
 * 凭证准备的每次尝试独立留痕，成功必须与同轮实际凭证同时提交。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherPreparationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与最终审批及实际凭证登记共用数据库事务。 */
    public JdbcVoucherPreparationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 同轮只允许一项活动准备，历史失败保留后才能新建只读尝试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherPreparation value) {
        if (value.status() != VoucherPreparation.Status.QUEUED || value.version() != 1) throw conflict();
        var input = value.input(); var source = input.source();
        jdbc.update("""
                INSERT INTO voucher_preparation(tenant_id,id,business_type,business_id,application_id,round_no,kind,application_version,business_version,
                employee_id,attempt_no,input_json,state_json,version,status,active_application_id,created_at,payment_operation_id,payment_version,trace_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?,?,?,?)
                """, source.tenantId(), input.id().toString(), source.businessType().name(), source.businessId().toString(), source.applicationId().toString(), source.roundNo(),
                source.kind().name(), source.applicationVersion(), source.businessVersion(), source.employeeId(), input.attempt(), json.write(input), json.write(value),
                source.applicationId().toString(), timestamp(value.createdAt()), source.paymentOperationId() == null ? null : source.paymentOperationId().toString(), source.paymentVersion(), DiagnosticContext.capture().traceId());
        append(value);
    }
    /** 固定原输入和前一版本，落库失败连同凭证登记一并回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherPreparation value) {
        var input = value.input(); var source = input.source();
        int changed = jdbc.update("""
                UPDATE voucher_preparation SET state_json=?,version=?,status=?,active_application_id=?,operation_id=?,started_at=?,lease_until=?,completed_at=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=?
                """, json.write(value), value.version(), value.status().name(), value.active() ? source.applicationId().toString() : null,
                value.result() == null || value.result().operationId() == null ? null : value.result().operationId().toString(), timestamp(value.startedAt()), timestamp(value.leaseUntil()),
                timestamp(value.completedAt()), source.tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 精确租户读取，不用其他轮次准备结果替代。 */
    public Optional<VoucherPreparation> find(String tenant, UUID id) { return jdbc.query("SELECT * FROM voucher_preparation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst(); }
    /** 固定业务来源决定类型，取本轮最后一次已持久尝试。 */
    public Optional<VoucherPreparation> latest(VoucherPreparation.Source source) {
        return latest(source.tenantId(), source.applicationId(), source.roundNo(), source.kind());
    }
    /** 历史读取沿用指定轮次，不要求用当前财务版本伪造旧来源。 */
    public Optional<VoucherPreparation> latest(String tenant, UUID applicationId, int round, VoucherCommand.Kind kind) {
        return jdbc.query("SELECT * FROM voucher_preparation WHERE tenant_id=? AND application_id=? AND round_no=? AND kind=? ORDER BY attempt_no DESC LIMIT 1", row(), tenant, applicationId.toString(), round, kind.name()).stream().findFirst();
    }
    /** 有界扫描只读取身份，领取事务重新获取原输入。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id,trace_id FROM voucher_preparation WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY created_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), timestamp(now));
    }
    private RowMapper<VoucherPreparation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), VoucherPreparation.class); var input = value.input(); var source = input.source();
            var operation = value.result() == null || value.result().operationId() == null ? null : value.result().operationId().toString();
            if (!input.equals(json.read(row.getString("input_json"), VoucherPreparation.Input.class)) || !input.id().toString().equals(row.getString("id"))
                    || !source.tenantId().equals(row.getString("tenant_id")) || !source.businessType().name().equals(row.getString("business_type"))
                    || !source.businessId().toString().equals(row.getString("business_id")) || !source.applicationId().toString().equals(row.getString("application_id"))
                    || !source.employeeId().equals(row.getString("employee_id")) || !source.kind().name().equals(row.getString("kind")) || source.roundNo() != row.getInt("round_no")
                    || source.applicationVersion() != row.getLong("application_version") || source.businessVersion() != row.getLong("business_version")
                    || !Objects.equals(source.paymentOperationId() == null ? null : source.paymentOperationId().toString(), row.getString("payment_operation_id"))
                    || !Objects.equals(source.paymentVersion(), row.getObject("payment_version", Long.class))
                    || input.attempt() != row.getLong("attempt_no") || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !Objects.equals(value.active() ? source.applicationId().toString() : null, row.getString("active_application_id"))
                    || !Objects.equals(operation, row.getString("operation_id")) || !value.createdAt().equals(instant(row.getTimestamp("created_at")))
                    || !Objects.equals(value.startedAt(), instant(row.getTimestamp("started_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(value.completedAt(), instant(row.getTimestamp("completed_at")))) throw new IllegalStateException("Persisted voucher preparation identity is inconsistent");
            return value;
        };
    }
    private void append(VoucherPreparation value) { jdbc.update("INSERT INTO voucher_preparation_revision(tenant_id,preparation_id,version,state_json) VALUES(?,?,?,?)", value.input().source().tenantId(), value.input().id().toString(), value.version(), json.write(value)); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Voucher preparation input or version changed"); }
    /**
     * 扫描不加载财务明细或个人信息。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) { }
}
