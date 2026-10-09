package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.procurement.mapper.SupplierFinanceActionsMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 财务明确办理原应付复核、授权及预留恢复，人工决定和最小审计同事务保存。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierFinanceActions {
    private final CurrentActor actors;
    private final SupplierPaymentAccess access;
    private final ApprovedSupplierPaymentSources sources;
    private final JdbcSupplierPayableReviewRepository reviews;
    private final SupplierPayableReviewService reviewService;
    private final JdbcSupplierPayableHoldRepository holds;
    private final SupplierPayableHoldService holdService;
    private final SupplierFinanceActionsMapper sqlMapper;
    private final JsonUtil json;

    /** 外部读取和预留由独立执行器处理，此入口不等待 ERP。 */
    public SupplierFinanceActions(
            CurrentActor actors,
            SupplierPaymentAccess access,
            ApprovedSupplierPaymentSources sources,
            JdbcSupplierPayableReviewRepository reviews,
            SupplierPayableReviewService reviewService,
            JdbcSupplierPayableHoldRepository holds,
            SupplierPayableHoldService holdService,
            SupplierFinanceActionsMapper sqlMapper,
            JsonUtil json) {
        this.actors = actors;
        this.access = access;
        this.sources = sources;
        this.reviews = reviews;
        this.reviewService = reviewService;
        this.holds = holds;
        this.holdService = holdService;
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 展示的批准双版本必须仍然成立，读取意图不能替换目标、账户或人员。 */
    @Transactional
    public Receipt review(UUID requestId, ReviewInput input) {
        var source = lockedSource(requestId, input.roundNo(), input.applicationVersion(), input.requestVersion()); var now = now();
        var value = reviewService.register(source, actors.actor().userId(), now);
        var event = audit(source, value.input().id(), value.version(), "SUPPLIER_PAYABLE_REVIEW", null, value.status().name(), input.comment(), now);
        return new Receipt(requestId, source.reservation().source().applicationId(), input.roundNo(), "REVIEW", value.input().id(), value.version(), null, null, event);
    }

    /** 只消费本申请、本轮和本人发起的新鲜证据，不接受前端金融事实。 */
    @Transactional
    public Receipt authorize(UUID requestId, AuthorizeInput input) {
        var source = lockedSource(requestId, input.roundNo(), input.applicationVersion(), input.requestVersion()); var tenant = actors.actor().tenantId();
        var review = reviews.find(tenant, input.reviewId()).orElseThrow(SupplierFinanceActions::notFound);
        if (!review.input().source().equals(source)) throw conflict();
        var now = now(); var hold = reviewService.authorize(tenant, input.reviewId(), input.reviewVersion(), actors.actor().userId(), now);
        var event = audit(source, hold.command().id(), hold.version(), "SUPPLIER_PAYMENT_AUTHORIZE", null, hold.status().name(), input.comment(), now);
        return new Receipt(requestId, source.reservation().source().applicationId(), input.roundNo(), "AUTHORIZE", review.input().id(), review.version() + 1, hold.command().id(), hold.version(), event);
    }

    /** 查询、原号重试及安全结束各自遵循领域状态，不能借结束动作释放真实 ERP 预留。 */
    @Transactional
    public Receipt act(UUID authorizationId, ActionInput input) {
        var initial = access.requireAuthorization(authorizationId); sources.lock(initial);
        var authorization = access.requireAuthorization(authorizationId); var tenant = actors.actor().tenantId(); var now = now();
        var before = holds.find(tenant, authorizationId).orElseThrow(SupplierFinanceActions::notFound);
        long version; String after;
        if (input.action() == Action.RETIRE) {
            var retired = holdService.retire(tenant, authorizationId, input.holdVersion(), actors.actor().userId(), now);
            version = retired.operationVersion(); after = "RETIRED";
        } else {
            var next = input.action() == Action.QUERY ? holdService.query(tenant, authorizationId, input.holdVersion(), now)
                    : holdService.resend(tenant, authorizationId, input.holdVersion(), now);
            version = next.version(); after = next.status().name();
        }
        var event = audit(authorization.source(), authorizationId, version, "SUPPLIER_HOLD_" + input.action().name(), before.status().name(), after, input.comment(), now);
        var source = authorization.source().reservation().source();
        return new Receipt(source.requestId(), source.applicationId(), source.round().roundNo(), input.action().name(), null, null, authorizationId, version, event);
    }

    private ApprovedProcurementPayment lockedSource(UUID requestId, int roundNo, long applicationVersion, long requestVersion) {
        access.requireFinance(requestId, roundNo); var tenant = actors.actor().tenantId(); sources.lock(sources.derive(tenant, requestId));
        var context = access.requireFinance(requestId, roundNo); var source = sources.derive(tenant, requestId);
        if (context.view().applicationVersion() != applicationVersion || context.view().requestVersion() != requestVersion
                || source.approval().roundNo() != roundNo || source.approval().applicationVersion() != applicationVersion || source.approvedRequestVersion() != requestVersion) throw conflict();
        return source;
    }

    private UUID audit(
            ApprovedProcurementPayment approved,
            UUID aggregateId,
            long version,
            String action,
            String previous,
            String current,
            String comment,
            Instant now) {
        var source = approved.reservation().source();
        var event = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", source.requestId());
        payload.put("roundNo", source.round().roundNo());
        payload.put("authorizedRole", "FINANCE");
        payload.put("applicationVersion", approved.approval().applicationVersion());
        payload.put("requestVersion", approved.approvedRequestVersion());
        payload.put("previousStatus", previous);
        payload.put("currentStatus", current);
        payload.put("comment", comment.trim());
        sqlMapper.audit(
                UUID.randomUUID().toString(),
                source.tenantId(),
                event.toString(),
                aggregateId.toString(),
                version,
                source.applicationId().toString(),
                action,
                actors.actor().userId(),
                json.write(payload),
                Timestamp.from(now));
        return event;
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed approved procurement source or payable review changed"); }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original supplier payable review or hold not found"); }

    /**
     * 三类人工恢复动作均只操作原授权号。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum Action {
        QUERY,
        RETRY,
        RETIRE
    }

    /**
     * 复核请求只包含已展示版本和人工说明。
     *
     * @author owlzhangfq@gmail.com
     */
    public record ReviewInput(
            @Positive int roundNo,
            @Positive long applicationVersion,
            @Positive long requestVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 拒绝客户端夹带新金额、账户或审批结果。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier payable review field"); }
    }

    /**
     * 财务授权必须携带刚展示的本人复核身份与版本。
     *
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizeInput(
            @Positive int roundNo,
            @Positive long applicationVersion,
            @Positive long requestVersion,
            @NotNull UUID reviewId,
            @Positive long reviewVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 不静默忽略金融事实，所有金额与账户来自服务端证据。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier payment authorization field"); }
    }

    /**
     * 当前预留版本避免页面恢复后执行过时决定。
     *
     * @author owlzhangfq@gmail.com
     */
    public record ActionInput(
            @NotNull Action action,
            @Positive long holdVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 页面不能声明原预留不存在或支付已经结束。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown supplier hold action field"); }
    }

    /**
     * 缓存回执仅定位已保存的人工决定，资金状态通过当前权限重新读取。
     *
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Receipt(
            UUID requestId,
            UUID applicationId,
            int roundNo,
            String action,
            UUID reviewId,
            Long reviewVersion,
            UUID authorizationId,
            Long holdVersion,
            UUID auditEventId) {}
}
