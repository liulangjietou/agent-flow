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
 * 原还款复核只读队列和不可变修订，同一原件的不同操作者观察均保留。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcRepaymentReviewCheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 仓储只处理本地事实，不执行外部资金或会计操作。 */
    public JdbcRepaymentReviewCheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 意图先加入原借款事务，后台只处理已提交的原收款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepaymentReviewCheck value) {
        if (value.version() != 1 || value.status() != AdvanceRepaymentReviewCheck.Status.QUEUED) throw conflict();
        var input = value.input();
        jdbc.update("""
                INSERT INTO repayment_review_check(trace_id,tenant_id,id,advance_id,repayment_id,requested_by,input_json,state_json,version,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                """, DiagnosticContext.capture().traceId(), input.tenantId(), input.id().toString(), input.request().original().request().advanceId().toString(), input.request().repaymentId().toString(),
                input.requestedBy(), json.write(input), json.write(value), timestamp(input.requestedAt()), timestamp(value.updatedAt()));
        append(value);
    }
    /** 版本和原输入共同约束，迟到任务不能替换当前租约或二次裁决。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(AdvanceRepaymentReviewCheck value) {
        var input = value.input();
        int changed = jdbc.update("UPDATE repayment_review_check SET version=?,status=?,state_json=?,updated_at=?,lease_until=? WHERE tenant_id=? AND id=? AND version=? AND input_json=?",
                value.version(), value.status().name(), json.write(value), timestamp(value.updatedAt()), timestamp(value.leaseUntil()), input.tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 按租户定位精确查询，不从编号推断业务授权。 */
    public Optional<AdvanceRepaymentReviewCheck> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM repayment_review_check WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 财务只办理自己对这笔原还款最新明确发起的复核。 */
    public Optional<AdvanceRepaymentReviewCheck> latest(String tenant, UUID repaymentId, String actor) {
        return jdbc.query("SELECT * FROM repayment_review_check WHERE tenant_id=? AND repayment_id=? AND requested_by=? ORDER BY created_at DESC,id DESC LIMIT 1", row(), tenant, repaymentId.toString(), actor).stream().findFirst();
    }
    /** 不同财务的已完成观察共同约束最高外部版本和既有资金事实。 */
    public List<AdvanceRepaymentReviewCheck> history(String tenant, UUID repaymentId) {
        return jdbc.query("SELECT * FROM repayment_review_check WHERE tenant_id=? AND repayment_id=? AND status IN ('CHECKED','RESOLVED') ORDER BY updated_at DESC,id DESC", row(), tenant, repaymentId.toString());
    }
    /** 每次只领取有限数量，并由应用服务处理超时旧租约。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,r.process_instance_id AS process_instance_id FROM repayment_review_check q
                LEFT JOIN advance_repayment repayment ON repayment.tenant_id=q.tenant_id AND repayment.id=q.repayment_id AND repayment.advance_id=q.advance_id
                LEFT JOIN advance_repayment_check original_check ON original_check.tenant_id=repayment.tenant_id AND original_check.id=repayment.check_id AND original_check.advance_id=q.advance_id
                LEFT JOIN payment_authorization origin ON origin.tenant_id=original_check.tenant_id AND origin.id=original_check.payment_id
                LEFT JOIN approval_application a ON a.tenant_id=origin.tenant_id AND a.id=origin.application_id
                LEFT JOIN approval_submission_round r ON r.tenant_id=a.tenant_id AND r.application_id=a.id AND r.round_no=origin.round_no
                WHERE q.status='QUEUED' OR (q.status='RUNNING' AND q.lease_until<=?) ORDER BY q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), timestamp(now));
    }
    private RowMapper<AdvanceRepaymentReviewCheck> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceRepaymentReviewCheck.class); var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.request().repaymentId().toString().equals(row.getString("repayment_id")) || !input.request().original().request().advanceId().toString().equals(row.getString("advance_id"))
                    || !input.requestedBy().equals(row.getString("requested_by")) || !input.equals(json.read(row.getString("input_json"), AdvanceRepaymentReviewCheck.Input.class))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw new IllegalStateException("Persisted repayment review identity is inconsistent");
            return value;
        };
    }
    private void append(AdvanceRepaymentReviewCheck value) { jdbc.update("INSERT INTO repayment_review_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,?,?)", value.input().tenantId(), value.input().id().toString(), value.version(), json.write(value)); }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Repayment review version or original input changed"); }
    /**
     * 扫描只携带租户与任务标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 缺少原业务事实时保持空值，不继承工作线程残留值。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
