package io.agentflow.budget;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
 * 财务授权指令、单次台账消费和执行队列原子保存，已发送的原操作不可被第二条授权替代。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetAdjustmentOperationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApprovedBudgetAdjustmentSources sources;
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    /** 来源和台账均引用真实持久修订，传输适配器不参与此事务。 */
    public JdbcBudgetAdjustmentOperationRepository(JdbcTemplate jdbc, JsonUtil json, ApprovedBudgetAdjustmentSources sources, JdbcBudgetAdjustmentReviewRepository reviews) {
        this.jdbc = jdbc; this.json = json; this.sources = sources; this.reviews = reviews;
    }
    /** 原授权入队、首版证据和复核消费必须一起成功，不能只留下可发送命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetAdjustmentOperation value, UUID reviewId) {
        var command = value.command(); var source = command.source(); sources.lock(source); sources.requireCurrent(source);
        if (!value.equals(BudgetAdjustmentOperation.queue(command, value.createdAt()))) throw conflict();
        var review = reviews.find(command.tenantId(), reviewId).orElseThrow(JdbcBudgetAdjustmentOperationRepository::conflict);
        if (!reviews.latest(command.tenantId(), source.requestId(), command.authorizedBy()).filter(review::equals).isPresent()) throw conflict();
        var consumed = review.consume(command, command.authorizedAt());
        try {
            jdbc.update("""
                    INSERT INTO budget_adjustment_operation(trace_id,tenant_id,id,request_id,application_id,employee_id,request_version,authorized_by,review_id,review_version,
                    command_json,command_digest,state_json,version,status,attempts,created_at,updated_at,next_attempt_at,active_request_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?, ?,1,'QUEUED',0,?,?,?,?)
                    """, DiagnosticContext.capture().traceId(), source.tenantId(), command.id().toString(), source.requestId().toString(), source.applicationId().toString(), source.employeeId(), source.approvedRequestVersion(),
                    command.authorizedBy(), reviewId.toString(), review.version(), json.write(command), command.digest(), json.write(value),
                    timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), source.requestId().toString());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED", "Approved budget request already has an original active adjustment command"); }
        append(value); reviews.update(consumed);
    }
    /** 下一版必须能由原状态推导；重复领取、迟到回执及安全结束后的更新都会失败。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetAdjustmentOperation value) {
        var command = value.command(); sources.lock(command.source());
        var before = find(command.tenantId(), command.id()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::conflict);
        if (!before.command().equals(command) || before.version() + 1 != value.version() || !successor(before, value).equals(value)) throw conflict();
        int changed = jdbc.update("""
                UPDATE budget_adjustment_operation SET state_json=?,version=?,status=?,attempts=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND command_json=? AND command_digest=? AND active_request_id=request_id AND retired_version IS NULL
                """, json.write(value), value.version(), value.status().name(), value.attempts(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()),
                command.tenantId(), command.id().toString(), before.version(), json.write(command), command.digest());
        if (changed != 1) throw conflict(); append(value);
    }
    public Optional<BudgetAdjustmentOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM budget_adjustment_operation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }
    /** 唯一活动指令包含已成功和未知结果，不能因出队就解除重复执行保护。 */
    public Optional<BudgetAdjustmentOperation> activeForRequest(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM budget_adjustment_operation WHERE tenant_id=? AND active_request_id=?", this::restore, tenant, requestId.toString()).stream().findFirst();
    }
    /** 工作区按实际登记时间展示最近一次决定，随机 UUID 只用来打破同刻排序。 */
    public Optional<BudgetAdjustmentOperation> latestForRequest(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM budget_adjustment_operation WHERE tenant_id=? AND request_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                this::restore, tenant, requestId.toString()).stream().findFirst();
    }
    /** 原消息的事实必须存在于同一指令的持久历史，后续原号查询不抹去之前的未知或查无。 */
    public List<BudgetAdjustmentOperation> history(String tenant, UUID id) {
        return jdbc.query("SELECT state_json,version FROM budget_adjustment_operation_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetAdjustmentOperation.class);
            if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != row.getLong("version")) throw inconsistent();
            return value;
        }, tenant, id.toString());
    }
    /** 按真实指令版本保留外部证据，后来查询不覆盖既有修订。 */
    public Optional<BudgetAdjustmentOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM budget_adjustment_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetAdjustmentOperation.class);
            if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != version) throw inconsistent();
            return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }
    /** 有界读取原申请的执行历史，结束的授权也保留其原编号。 */
    public List<BudgetAdjustmentOperation> list(String tenant, UUID requestId, UUID before, int limit) {
        String sql = "SELECT * FROM budget_adjustment_operation WHERE tenant_id=? AND request_id=? " + (before == null ? "" : "AND id<? ") + "ORDER BY id DESC LIMIT ?";
        return before == null ? jdbc.query(sql, this::restore, tenant, requestId.toString(), limit)
                : jdbc.query(sql, this::restore, tenant, requestId.toString(), before.toString(), limit);
    }
    /** 一批十个原标识，安全结束的旧指令永不被重新领取。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,NULL AS process_instance_id FROM budget_adjustment_operation q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                WHERE q.retired_version IS NULL AND
                ((q.status IN ('QUEUED','UNKNOWN') AND q.next_attempt_at<=?) OR (q.status IN ('EXECUTING','QUERYING') AND q.lease_until<=?))
                ORDER BY COALESCE(q.next_attempt_at,q.lease_until),q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), timestamp(now), timestamp(now));
    }

    /** 批准快照还原并校验后，只按其中的原轮次查实例，不读取申请当前轮次。 */
    public String findProcessInstance(ApprovedBudgetAdjustment source) {
        return jdbc.query("SELECT process_instance_id FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?",
                (row, index) -> row.getString("process_instance_id"), source.tenantId(), source.applicationId().toString(), source.round().roundNo())
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    /** 结束证明引用当前已停止的安全修订，任何失败都回滚释放独占。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String tenant, BudgetAdjustmentRetirement decision) {
        var original = find(tenant, decision.operationId()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::conflict); sources.lock(original.command().source());
        var current = find(tenant, decision.operationId()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::conflict);
        var proof = revision(tenant, decision.operationId(), decision.operationVersion()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::conflict);
        if (!decision.matches(current) || !current.equals(proof) || retirement(tenant, decision.operationId()).isPresent()) throw conflict();
        jdbc.update("INSERT INTO budget_adjustment_retirement(tenant_id,operation_id,operation_version,state_json) VALUES(?,?,?,?)",
                tenant, decision.operationId().toString(), decision.operationVersion(), json.write(decision));
        int changed = jdbc.update("UPDATE budget_adjustment_operation SET active_request_id=NULL,retired_version=? WHERE tenant_id=? AND id=? AND version=? AND active_request_id=request_id AND retired_version IS NULL",
                decision.operationVersion(), tenant, decision.operationId().toString(), decision.operationVersion());
        if (changed != 1) throw conflict();
    }
    /** 结束记录恢复时再次核对原始无副作用证明。 */
    public Optional<BudgetAdjustmentRetirement> retirement(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM budget_adjustment_retirement WHERE tenant_id=? AND operation_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetAdjustmentRetirement.class);
            if (!value.operationId().equals(id) || value.operationVersion() != row.getLong("operation_version")
                    || !value.matches(revision(tenant, id, value.operationVersion()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::inconsistent))) throw inconsistent();
            return value;
        }, tenant, id.toString()).stream().findFirst();
    }
    private BudgetAdjustmentOperation restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), BudgetAdjustmentOperation.class); var command = value.command(); var source = command.source();
        if (!command.equals(json.read(row.getString("command_json"), BudgetAdjustmentCommand.class)) || !command.digest().equals(row.getString("command_digest"))
                || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                || !source.requestId().toString().equals(row.getString("request_id")) || !source.applicationId().toString().equals(row.getString("application_id"))
                || !source.employeeId().equals(row.getString("employee_id")) || source.approvedRequestVersion() != row.getLong("request_version")
                || !command.authorizedBy().equals(row.getString("authorized_by")) || value.version() != row.getLong("version")
                || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw inconsistent();
        var reviewId = UUID.fromString(row.getString("review_id"));
        var ready = reviews.revision(command.tenantId(), reviewId, row.getLong("review_version")).orElseThrow(JdbcBudgetAdjustmentOperationRepository::inconsistent);
        if (!ready.consume(command, command.authorizedAt()).supports(command)) throw inconsistent();
        if (!reviews.find(command.tenantId(), reviewId).orElseThrow(JdbcBudgetAdjustmentOperationRepository::inconsistent).supports(command)) throw inconsistent();
        Long retired = row.getObject("retired_version", Long.class);
        if (!Objects.equals(retired == null ? source.requestId().toString() : null, row.getString("active_request_id"))) throw inconsistent();
        if (retired != null && (retired != value.version() || !retirement(command.tenantId(), command.id()).orElseThrow(JdbcBudgetAdjustmentOperationRepository::inconsistent).matches(value))) throw inconsistent();
        return value;
    }
    private static BudgetAdjustmentOperation successor(BudgetAdjustmentOperation before, BudgetAdjustmentOperation value) {
        var at = value.updatedAt();
        if (before.running()) {
            if (before.expired(at)) return before.expire(at);
            if (value.failure() != null && value.status() == BudgetAdjustmentOperation.Status.UNKNOWN) return before.unavailable(value.failure(), at);
            var incoming = value.status() == BudgetAdjustmentOperation.Status.RECONCILING ? value.conflictingObservation() : value.observation();
            if (incoming == null) throw conflict();
            return before.complete(new FinanceResult.Success<>(incoming), at);
        }
        if (value.running()) return before.claim(at, Duration.between(at, value.leaseUntil()));
        if (value.status() == BudgetAdjustmentOperation.Status.EXPIRED) return before.claim(at, Duration.ofSeconds(1));
        if (value.status() == BudgetAdjustmentOperation.Status.QUEUED) return before.retryNotFound(at);
        if (value.status() == BudgetAdjustmentOperation.Status.VOIDED) return before.voidBeforeSend(at);
        if (value.status() == BudgetAdjustmentOperation.Status.UNKNOWN) return before.requestQuery(at);
        throw conflict();
    }
    private void append(BudgetAdjustmentOperation value) {
        jdbc.update("INSERT INTO budget_adjustment_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)",
                value.command().tenantId(), value.command().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget command, ledger evidence, operation or retirement version changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted budget adjustment operation identity is inconsistent"); }
    /**
     * 扫描候选不携带台账金额或审批正文。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 缺少原业务事实时保持空值，不继承工作线程残留值。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
