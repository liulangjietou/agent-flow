package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePartialAdjustmentAuditMapper;
import io.agentflow.finance.ExpenseAdjustmentFundingSource;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 部分调整公开决定和对应持久变化同事务审计，不复制账户或原财务命令。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpensePartialAdjustmentAudit {
    private final ExpensePartialAdjustmentAuditMapper sqlMapper;
    private final JsonUtil json;
    private final CurrentActor actors;

    /** 操作身份始终取认证上下文，不能由页面指定。 */
    public ExpensePartialAdjustmentAudit(
            ExpensePartialAdjustmentAuditMapper sqlMapper, JsonUtil json, CurrentActor actors) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.actors = actors;
    }

    /** 原件查询以原报销定位，创建及后续决定以真实调整或准备版本定位。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(ExpenseReport report, UUID id, long version, Action action, String comment, Instant at) {
        return write(report, id, version, action, comment, at, Map.of());
    }

    /** 来源确认绑定原调整相邻修订和实际采用的原件版本，不能用一句通用备注替代复核依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID confirmation(ExpenseReport report, ExpensePartialAdjustment before, ExpensePartialAdjustment after,
            ExpenseAdjustmentFundingSource source, String comment, Instant at) {
        var financial = source.financial();
        var versions = Map.of("settlementVersion", financial.settlement().version(), "budgetVersion", financial.consumption().version(),
                "accrualVersion", financial.accrual().version(), "paymentVersion", source.payment() == null ? 0 : source.payment().version(),
                "paymentVoucherVersion", source.paymentVoucher() == null ? 0 : source.paymentVoucher().version(), "returnsVersion", source.returns() == null ? 0 : source.returns().version());
        return write(report, after.id(), after.version(), Action.CONFIRM_CURRENT, comment, at, Map.of("beforeVersion", before.version(), "sourceVersions", versions));
    }

    /** 财务裁决审计引用实际具名证明与相邻根修订，不复制完整财务候选。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID dispute(ExpenseReport report, ExpensePartialDisputeResolution decision, ExpensePartialAdjustment after) {
        return write(report, after.id(), after.version(), Action.RESOLVE_DISPUTE, decision.reason(), decision.resolvedAt(),
                Map.of("resolutionId", decision.id(), "side", decision.side(), "outcome", decision.outcome(), "beforeVersion", decision.beforeVersion()));
    }

    private UUID write(
            ExpenseReport report,
            UUID id,
            long version,
            Action action,
            String comment,
            Instant at,
            Map<String, Object> details) {
        var payload = new java.util.LinkedHashMap<String, Object>(details);
        payload.put("reportId", report.id());
        payload.put("roundNo", report.requireFrozenRound().roundNo());
        payload.put("authorizedRole", "FINANCE");
        payload.put("comment", comment);
        var event = UUID.randomUUID();
        sqlMapper.write(
                UUID.randomUUID().toString(),
                report.tenantId(),
                event.toString(),
                id.toString(),
                version,
                report.applicationId().toString(),
                "EXPENSE_PARTIAL_" + action.name(),
                actors.actor().userId(),
                json.write(payload),
                Timestamp.from(at));
        return event;
    }

    /**
     * 明确区分只读刷新、固定意图和实际写入授权。
     *
     * @author owlzhangfq@gmail.com
     */
    public enum Action {
        ORIGINAL_QUERY,
        CREATE,
        PREPARE,
        AUTHORIZE,
        QUERY_BUDGET,
        QUERY_ACCRUAL,
        RESEND_BUDGET,
        RESEND_ACCRUAL,
        CONFIRM_CURRENT,
        SOURCE_QUERY,
        RETIRE,
        RESOLVE_DISPUTE
    }

    /**
     * 幂等回执只携带已保存的定位，不携带敏感原件或可直接外发的命令。
     *
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(
            UUID reportId,
            int roundNo,
            UUID adjustmentId,
            Long adjustmentVersion,
            UUID preparationId,
            Long preparationVersion,
            UUID auditEventId) {}
}
