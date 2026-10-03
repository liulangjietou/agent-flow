package io.agentflow.budget;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 财务明确发起台账复核、授权原子调整及恢复，人工决定与最小审计同事务保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentFinanceActions {
    private final CurrentActor actors;
    private final BudgetAdjustmentFinanceAccess access;
    private final ApprovedBudgetAdjustmentSources sources;
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    private final BudgetAdjustmentReviewService reviewService;
    private final BudgetAdjustmentExecutionService execution;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 外部调用留在后台，公开入口只保存具名决定与固定编号。 */
    public BudgetAdjustmentFinanceActions(CurrentActor actors, BudgetAdjustmentFinanceAccess access, ApprovedBudgetAdjustmentSources sources,
            JdbcBudgetAdjustmentReviewRepository reviews, BudgetAdjustmentReviewService reviewService, BudgetAdjustmentExecutionService execution,
            JdbcTemplate jdbc, JsonUtil json) {
        this.actors = actors; this.access = access; this.sources = sources; this.reviews = reviews; this.reviewService = reviewService;
        this.execution = execution; this.jdbc = jdbc; this.json = json;
    }
    /** 显示的批准双版本必须保持，客户端不能夹带预算余额或新目标。 */
    @Transactional
    public Receipt review(UUID requestId, ReviewInput input) {
        var source = lockedSource(requestId, input.roundNo(), input.applicationVersion(), input.requestVersion()); var at = now();
        var value = reviewService.register(source, actors.actor().userId(), at);
        var event = audit(source, value.input().id(), value.version(), "BUDGET_ADJUSTMENT_REVIEW", null, value.status().name(), input.comment(), at);
        return new Receipt(requestId, source.applicationId(), input.roundNo(), "REVIEW", value.input().id(), value.version(), null, null, event);
    }
    /** 同一财务明确确认刚展示的台账，金额与原版本全部由服务端持久证据派生。 */
    @Transactional
    public Receipt authorize(UUID requestId, AuthorizeInput input) {
        var source = lockedSource(requestId, input.roundNo(), input.applicationVersion(), input.requestVersion()); var tenant = actors.actor().tenantId();
        var review = reviews.find(tenant, input.reviewId()).orElseThrow(BudgetAdjustmentFinanceActions::notFound);
        if (!review.input().source().equals(source)) throw conflict();
        var at = now(); var value = reviewService.authorize(tenant, input.reviewId(), input.reviewVersion(), actors.actor().userId(), input.comment().trim(), at);
        var event = audit(source, value.command().id(), value.version(), "BUDGET_ADJUSTMENT_AUTHORIZE", null, value.status().name(), input.comment(), at);
        return new Receipt(requestId, source.applicationId(), input.roundNo(), "AUTHORIZE", review.input().id(), review.version() + 1, value.command().id(), value.version(), event);
    }
    /** 查询、查无原号重发和安全结束都保留原授权，不能改写额度或伪造已生效。 */
    @Transactional
    public Receipt act(UUID id, ActionInput input) {
        var initial = access.requireOperation(id); sources.lock(initial.command().source());
        var current = access.requireOperation(id); var tenant = actors.actor().tenantId(); var at = now(); long version; String after;
        if (input.action() == Action.RETIRE) {
            var retired = execution.retire(tenant, id, input.operationVersion(), actors.actor().userId(), at); version = retired.operationVersion(); after = "RETIRED";
        } else {
            var next = input.action() == Action.QUERY ? execution.query(tenant, id, input.operationVersion(), at)
                    : execution.resend(tenant, id, input.operationVersion(), actors.actor().userId(), at);
            version = next.version(); after = next.status().name();
        }
        var source = current.command().source();
        var event = audit(source, id, version, "BUDGET_ADJUSTMENT_" + input.action().name(), current.status().name(), after, input.comment(), at);
        return new Receipt(source.requestId(), source.applicationId(), source.round().roundNo(), input.action().name(), null, null, id, version, event);
    }
    private ApprovedBudgetAdjustment lockedSource(UUID id, int roundNo, long applicationVersion, long requestVersion) {
        access.requireFinance(id, roundNo); var tenant = actors.actor().tenantId(); sources.lock(sources.derive(tenant, id));
        var context = access.requireFinance(id, roundNo); var source = sources.derive(tenant, id);
        if (context.view().applicationVersion() != applicationVersion || context.view().requestVersion() != requestVersion
                || source.round().roundNo() != roundNo || source.approval().applicationVersion() != applicationVersion || source.approvedRequestVersion() != requestVersion) throw conflict();
        return source;
    }
    private UUID audit(ApprovedBudgetAdjustment source, UUID aggregate, long version, String action, String previous, String current, String comment, Instant at) {
        var event = UUID.randomUUID(); var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", source.requestId()); payload.put("roundNo", source.round().roundNo()); payload.put("authorizedRole", "FINANCE");
        payload.put("applicationVersion", source.approval().applicationVersion()); payload.put("requestVersion", source.approvedRequestVersion());
        payload.put("previousStatus", previous); payload.put("currentStatus", current); payload.put("comment", comment.trim());
        jdbc.update("""
                INSERT INTO audit_event(id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,application_id,action,actor_id,payload_json,occurred_at)
                VALUES(?,?,?,'BudgetAdjustmentExecution',?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), source.tenantId(), event.toString(), aggregate.toString(), version, source.applicationId().toString(), action,
                actors.actor().userId(), json.write(payload), Timestamp.from(at)); return event;
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed budget approval or original ledger review changed"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original budget finance review was not found"); }
    /**
     * 恢复动作只操作原指令编号，查无不构成自动重发授权。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { QUERY, RETRY, RETIRE }
    /**
     * 复核请求只包含已展示的批准版本与人工说明。
     * @author owlzhangfq@gmail.com
     */
    public record ReviewInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long requestVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 拒绝通过读取请求注入外部余额、角色或目的地。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown budget finance review field"); }
    }
    /**
     * 原复核身份与版本是必需字段，客户端不能提供任何预算变动明细。
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizeInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long requestVersion,
                                 @NotNull UUID reviewId, @Positive long reviewVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 自报额度或已生效状态不能被静默忽略。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown budget finance authorization field"); }
    }
    /**
     * 当前操作版本避免旧页面恢复后执行过时决定。
     * @author owlzhangfq@gmail.com
     */
    public record ActionInput(@NotNull Action action, @Positive long operationVersion, @NotBlank @Size(max = 2000) String comment) {
        /** 不能由客户端声明原指令无副作用或实际额度已经变动。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown budget execution action field"); }
    }
    /**
     * 幂等结果仅定位决定，敏感台账与当前执行状态须经权限接口重新读取。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(UUID requestId, UUID applicationId, int roundNo, String action, UUID reviewId, Long reviewVersion, UUID operationId, Long operationVersion, UUID auditEventId) { }
}
