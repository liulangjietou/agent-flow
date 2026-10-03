package io.agentflow.budget;

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
 * 批准后财务台账读取及单次消费持久化，旧 READY 不能覆盖较新的财务读取。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetAdjustmentReviewRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApprovedBudgetAdjustmentSources sources;
    /** 使用实际批准来源和统一申请锁，不依赖内存维护最新证据。 */
    public JdbcBudgetAdjustmentReviewRepository(JdbcTemplate jdbc, JsonUtil json, ApprovedBudgetAdjustmentSources sources) {
        this.jdbc = jdbc; this.json = json; this.sources = sources;
    }
    /** 初始只能登记读取意图，并由数据库仲裁同一财务的活动读取。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetAdjustmentReview value) {
        var input = value.input(); var source = input.source(); sources.lock(source); sources.requireCurrent(source);
        if (!BudgetAdjustmentReview.queue(input).equals(value) || input.attempt() != latestAttempt(source.tenantId(), source.requestId(), input.requestedBy()) + 1) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO budget_adjustment_review(tenant_id,id,request_id,application_id,employee_id,request_version,requested_by,attempt_no,
                    requested_at,input_json,state_json,version,status,updated_at,active_request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                    """, source.tenantId(), input.id().toString(), source.requestId().toString(), source.applicationId().toString(), source.employeeId(),
                    source.approvedRequestVersion(), input.requestedBy(), input.attempt(), timestamp(input.requestedAt()), json.write(input), json.write(value),
                    timestamp(value.updatedAt()), source.requestId().toString());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("BUDGET_ADJUSTMENT_REVIEW_ACTIVE", "The finance actor already has an active budget ledger review"); }
        append(value);
    }
    /** 只接受原读取的下一步；消费必须对应同事务保存的精确指令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetAdjustmentReview value) {
        var input = value.input(); var tenant = input.source().tenantId(); sources.lock(input.source());
        var before = find(tenant, input.id()).orElseThrow(JdbcBudgetAdjustmentReviewRepository::conflict);
        if (!before.input().equals(input) || before.version() + 1 != value.version()) throw conflict();
        BudgetAdjustmentReview expected;
        if (value.status() == BudgetAdjustmentReview.Status.CONSUMED) {
            if (latestAttempt(tenant, input.source().requestId(), input.requestedBy()) != input.attempt()) throw conflict();
            expected = before.consume(consumedCommand(value), value.updatedAt());
        } else if (value.status() == BudgetAdjustmentReview.Status.VOIDED) expected = before.voidSource(value.updatedAt());
        else if (value.status() == BudgetAdjustmentReview.Status.RUNNING) expected = before.claim(value.updatedAt(), Duration.between(value.updatedAt(), value.leaseUntil()));
        else if (value.status() == BudgetAdjustmentReview.Status.READY) expected = before.complete(new FinanceResult.Success<>(value.ledger()), value.updatedAt());
        else if (value.status() == BudgetAdjustmentReview.Status.UNAVAILABLE) expected = before.fail(value.issue(), value.updatedAt());
        else {
            if (value.status() != BudgetAdjustmentReview.Status.BLOCKED || before.status() != BudgetAdjustmentReview.Status.RUNNING
                    || value.issue() != BudgetAdjustmentReview.Issue.LEDGER_CHANGED && value.issue() != BudgetAdjustmentReview.Issue.LEDGER_REJECTED
                    || !value.updatedAt().isBefore(before.leaseUntil()) || value.updatedAt().isBefore(before.updatedAt())) throw conflict();
            expected = new BudgetAdjustmentReview(input, before.version() + 1, value.status(), value.updatedAt(), before.startedAt(), before.leaseUntil(), null, null, null, value.issue());
        }
        if (!expected.equals(value)) throw conflict();
        int changed = jdbc.update("""
                UPDATE budget_adjustment_review SET state_json=?,version=?,status=?,updated_at=?,started_at=?,lease_until=?,checked_at=?,active_request_id=?,consumed_operation_id=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=?
                """, json.write(value), value.version(), value.status().name(), timestamp(value.updatedAt()), timestamp(value.startedAt()), timestamp(value.leaseUntil()), timestamp(value.checkedAt()),
                active(value), value.consumedOperationId() == null ? null : value.consumedOperationId().toString(), tenant, input.id().toString(), before.version(), json.write(input));
        if (changed != 1) throw conflict(); append(value);
    }
    public Optional<BudgetAdjustmentReview> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM budget_adjustment_review WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }
    /** 原 READY 修订固定指令采用的依据，后来消费不覆盖它。 */
    public Optional<BudgetAdjustmentReview> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM budget_adjustment_review_revision WHERE tenant_id=? AND review_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetAdjustmentReview.class);
            if (!value.input().source().tenantId().equals(tenant) || !value.input().id().equals(id) || value.version() != version) throw inconsistent();
            return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }
    public long latestAttempt(String tenant, UUID requestId, String actor) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM budget_adjustment_review WHERE tenant_id=? AND request_id=? AND requested_by=?",
                Long.class, tenant, requestId.toString(), actor);
    }
    public Optional<BudgetAdjustmentReview> latest(String tenant, UUID requestId, String actor) {
        return jdbc.query("SELECT * FROM budget_adjustment_review WHERE tenant_id=? AND request_id=? AND requested_by=? ORDER BY attempt_no DESC LIMIT 1",
                this::restore, tenant, requestId.toString(), actor).stream().findFirst();
    }
    /** 只扫描十个标识，租约到期只落失败，不把旧结果重新排队。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id FROM budget_adjustment_review WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY requested_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now));
    }
    private BudgetAdjustmentReview restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), BudgetAdjustmentReview.class); var input = value.input(); var source = input.source();
        if (!input.equals(json.read(row.getString("input_json"), BudgetAdjustmentReview.Input.class)) || !source.tenantId().equals(row.getString("tenant_id"))
                || !input.id().toString().equals(row.getString("id")) || !source.requestId().toString().equals(row.getString("request_id"))
                || !source.applicationId().toString().equals(row.getString("application_id")) || !source.employeeId().equals(row.getString("employee_id"))
                || source.approvedRequestVersion() != row.getLong("request_version") || !input.requestedBy().equals(row.getString("requested_by"))
                || input.attempt() != row.getLong("attempt_no") || !input.requestedAt().equals(instant(row.getTimestamp("requested_at")))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.startedAt(), instant(row.getTimestamp("started_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))
                || !Objects.equals(value.checkedAt(), instant(row.getTimestamp("checked_at"))) || !Objects.equals(active(value), row.getString("active_request_id"))
                || !Objects.equals(value.consumedOperationId() == null ? null : value.consumedOperationId().toString(), row.getString("consumed_operation_id"))) throw inconsistent();
        if (value.status() == BudgetAdjustmentReview.Status.CONSUMED && !value.supports(consumedCommand(value))) throw inconsistent();
        return value;
    }
    private BudgetAdjustmentCommand consumedCommand(BudgetAdjustmentReview value) {
        return jdbc.query("SELECT command_json FROM budget_adjustment_operation WHERE tenant_id=? AND id=? AND review_id=?", (row, index) ->
                json.read(row.getString("command_json"), BudgetAdjustmentCommand.class), value.input().source().tenantId(), value.consumedOperationId().toString(), value.input().id().toString())
                .stream().findFirst().orElseThrow(JdbcBudgetAdjustmentReviewRepository::conflict);
    }
    private void append(BudgetAdjustmentReview value) {
        jdbc.update("INSERT INTO budget_adjustment_review_revision(tenant_id,review_id,version,state_json) VALUES(?,?,?,?)",
                value.input().source().tenantId(), value.input().id().toString(), value.version(), json.write(value));
    }
    private static String active(BudgetAdjustmentReview value) { return value.active() ? value.input().source().requestId().toString() : null; }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget ledger review input, version or single evidence consumption changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted budget adjustment review identity is inconsistent"); }
    /**
     * 后台只传原租户及读取标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
