package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

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
 * 原放款复核只读队列和不可变修订，同一原件的不同操作者观察均保留。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDisbursementReturnCheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 仓储只处理本地事实，不执行外部资金或会计操作。 */
    public JdbcDisbursementReturnCheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 意图先加入原借款事务，后台只处理已提交的原付款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceDisbursementReturnCheck value) {
        if (value.version() != 1 || value.status() != AdvanceDisbursementReturnCheck.Status.QUEUED) throw conflict();
        var input = value.input();
        jdbc.update("""
                INSERT INTO disbursement_return_check(trace_id,tenant_id,id,advance_id,payment_id,payment_version,requested_by,input_json,state_json,version,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                """, DiagnosticContext.capture().traceId(), input.tenantId(), input.id().toString(), input.request().command().binding().businessId().toString(), input.request().command().id().toString(), input.paymentVersion(),
                input.requestedBy(), json.write(input), json.write(value), timestamp(input.requestedAt()), timestamp(value.updatedAt()));
        append(value);
    }
    /** 版本和原输入共同约束，迟到任务不能替换当前租约或二次裁决。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(AdvanceDisbursementReturnCheck value) {
        var input = value.input();
        int changed = jdbc.update("UPDATE disbursement_return_check SET version=?,status=?,state_json=?,updated_at=?,lease_until=? WHERE tenant_id=? AND id=? AND version=? AND input_json=?",
                value.version(), value.status().name(), json.write(value), timestamp(value.updatedAt()), timestamp(value.leaseUntil()), input.tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 按租户定位精确查询，不从编号推断业务授权。 */
    public Optional<AdvanceDisbursementReturnCheck> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM disbursement_return_check WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 财务只办理自己对这笔原放款最新明确发起的复核。 */
    public Optional<AdvanceDisbursementReturnCheck> latest(String tenant, UUID advanceId, String actor) {
        return jdbc.query("SELECT * FROM disbursement_return_check WHERE tenant_id=? AND advance_id=? AND requested_by=? ORDER BY created_at DESC,id DESC LIMIT 1", row(), tenant, advanceId.toString(), actor).stream().findFirst();
    }
    /** 不同财务的已完成观察共同约束最高外部版本和既有资金事实。 */
    public List<AdvanceDisbursementReturnCheck> history(String tenant, UUID advanceId) {
        return jdbc.query("SELECT * FROM disbursement_return_check WHERE tenant_id=? AND advance_id=? AND status IN ('CHECKED','RESOLVED') ORDER BY updated_at DESC,id DESC", row(), tenant, advanceId.toString());
    }
    /** 每次只领取有限数量，并由应用服务处理超时旧租约。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id,trace_id FROM disbursement_return_check WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY created_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), timestamp(now));
    }
    private RowMapper<AdvanceDisbursementReturnCheck> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceDisbursementReturnCheck.class); var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.request().command().id().toString().equals(row.getString("payment_id")) || input.paymentVersion() != row.getLong("payment_version")
                    || !input.request().command().binding().businessId().toString().equals(row.getString("advance_id"))
                    || !input.requestedBy().equals(row.getString("requested_by")) || !input.equals(json.read(row.getString("input_json"), AdvanceDisbursementReturnCheck.Input.class))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw new IllegalStateException("Persisted disbursement return identity is inconsistent");
            return value;
        };
    }
    private void append(AdvanceDisbursementReturnCheck value) { jdbc.update("INSERT INTO disbursement_return_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,?,?)", value.input().tenantId(), value.input().id().toString(), value.version(), json.write(value)); }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Disbursement return version or original input changed"); }
    /**
     * 扫描只携带租户与任务标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) {
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
