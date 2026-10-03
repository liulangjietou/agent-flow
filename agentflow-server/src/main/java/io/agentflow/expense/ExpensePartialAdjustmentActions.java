package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.ExpenseAdjustmentFundingSource;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前独立财务明确恢复或结束原调整；公开决定、实际状态变化及审计在同一原报销事务中保存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentActions {
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePartialAdjustmentFinance finance;
    private final ExpensePartialAdjustmentSources sources;
    private final ExpensePartialOriginalQueries originals;
    private final ExpensePartialAdjustmentAudit audit;
    /** 编排层复用差额执行和原件查询，状态是否合法仍由各自领域对象判断。 */
    public ExpensePartialAdjustmentActions(CurrentActor actors, ExpenseResourceAdjustmentAccess access, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcExpensePartialAdjustmentRepository adjustments, ExpensePartialAdjustmentFinance finance,
            ExpensePartialAdjustmentSources sources, ExpensePartialOriginalQueries originals, ExpensePartialAdjustmentAudit audit) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements; this.adjustments = adjustments;
        this.finance = finance; this.sources = sources; this.originals = originals; this.audit = audit;
    }

    /** 已发出差额可独立查询，只有原授权人才能明确重发同号；来源确认不替代两侧争议裁决。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt act(UUID id, OperationInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion(), input.settlementVersion());
        var before = adjustment(report, input.adjustmentId(), input.adjustmentVersion()); var actor = actors.actor(); var now = now();
        before.input().basis().funding().requireAuthorization(actor.userId(), now);
        ExpensePartialAdjustment after; UUID event;
        if (input.action() == Action.CONFIRM_CURRENT) {
            var source = confirmationSource(before, now); after = before.confirmCurrent(now); adjustments.update(after);
            event = audit.confirmation(report, before, after, source, input.comment(), now);
        } else {
            after = switch (input.action()) {
                case QUERY_BUDGET -> finance.queryBudget(actor.tenantId(), before.id(), before.version(), now);
                case QUERY_ACCRUAL -> finance.queryAccrual(actor.tenantId(), before.id(), before.version(), now);
                case RESEND_BUDGET -> {
                    requireOwner(before.budget() == null ? null : before.budget().input().command().authorizedBy());
                    yield finance.resendBudget(actor.tenantId(), before.id(), before.version(), actor.userId(), now);
                }
                case RESEND_ACCRUAL -> {
                    requireOwner(before.accrual() == null ? null : before.accrual().input().command().authorizedBy());
                    yield finance.resendAccrual(actor.tenantId(), before.id(), before.version(), actor.userId(), now);
                }
                case CONFIRM_CURRENT -> throw new IllegalStateException("Current source confirmation must include its exact audit evidence");
            };
            event = audit.record(report, after.id(), after.version(), ExpensePartialAdjustmentAudit.Action.valueOf(input.action().name()), input.comment(), after.updatedAt());
        }
        return receipt(report, after, event);
    }

    /** 原件未知或已变化时仍可显式安排原号查询；本入口不执行原付款或凭证重发。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt queryOriginals(UUID id, SourceQueryInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion(), input.settlementVersion());
        var value = adjustment(report, input.adjustmentId(), input.adjustmentVersion()); var now = now();
        value.input().basis().funding().requireAuthorization(actors.actor().userId(), now);
        originals.query(settlements.find(report.tenantId(), id).orElseThrow(ExpensePartialAdjustmentActions::conflict),
                input.accrualVersion(), input.paymentVersion(), input.paymentVoucherVersion(), now);
        var event = audit.record(report, value.id(), value.version(), ExpensePartialAdjustmentAudit.Action.SOURCE_QUERY, input.comment(), now);
        return receipt(report, value, event);
    }

    /** 无实际效果的两侧可具名结束；停止队列、释放回款占用与审计共同提交。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt retire(UUID id, RetireInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion(), input.settlementVersion());
        var before = adjustment(report, input.adjustmentId(), input.adjustmentVersion()); var now = now(); currentSource(before, now);
        var after = before.retire(actors.actor().userId(), input.evidenceReference(), input.reason(), now); adjustments.update(after);
        var event = audit.record(report, after.id(), after.version(), ExpensePartialAdjustmentAudit.Action.RETIRE, input.reason(), now);
        return receipt(report, after, event);
    }

    /** 纯领域预览不保存、不外发；真正动作仍须在锁内重新核对显示版本及当前资格。 */
    public List<Action> availableActions(ExpensePartialAdjustment value, Instant at) {
        if (value.retirement() != null) return List.of(); var actor = actors.actor().userId();
        return Arrays.stream(Action.values()).filter(action -> {
            try {
                value.input().basis().funding().requireAuthorization(actor, at);
                switch (action) {
                    case QUERY_BUDGET -> { if (value.budget() == null || value.budget().attempts() == 0) return false; value.withBudget(value.budget().requestQuery(at), at); }
                    case QUERY_ACCRUAL -> { if (value.accrual() == null || value.accrual().attempts() == 0) return false; value.withAccrual(value.accrual().requestQuery(at), at); }
                    case RESEND_BUDGET -> {
                        if (value.budget() == null || !actor.equals(value.budget().input().command().authorizedBy())) return false;
                        finance.requireSendSources(value, actor, value.budget().input().targetDigest(), at); value.withBudget(value.budget().retryNotFound(at), at);
                    }
                    case RESEND_ACCRUAL -> {
                        if (value.accrual() == null || !actor.equals(value.accrual().input().command().authorizedBy())) return false;
                        finance.requireSendSources(value, actor, value.accrual().input().targetDigest(), at); value.withAccrual(value.accrual().retryNotFound(at), at);
                    }
                    case CONFIRM_CURRENT -> { value.confirmCurrent(at); confirmationSource(value, at); }
                }
                return true;
            } catch (DomainException unavailable) { return false; }
        }).toList();
    }

    /** 结束预览使用同一来源与领域约束，不因队列尚未执行就忽略另一侧事实。 */
    public boolean canRetire(ExpensePartialAdjustment value, Instant at) {
        try {
            currentSource(value, at); value.retire(actors.actor().userId(), value.input().evidenceReference(), value.input().reason(), at); return true;
        } catch (DomainException unavailable) { return false; }
    }

    /** 当前原件可查询不要求它们已经成功，否则未知来源无法通过原号恢复。 */
    public boolean canQueryOriginals(ExpensePartialAdjustment value, Instant at) {
        if (value.retirement() != null) return false;
        try {
            value.input().basis().funding().requireAuthorization(actors.actor().userId(), at);
            return settlements.find(value.input().basis().tenantId(), value.input().basis().reportId()).map(settlement -> originals.available(settlement, at)).orElse(false);
        } catch (DomainException unavailable) { return false; }
    }
    private ExpenseAdjustmentFundingSource confirmationSource(ExpensePartialAdjustment value, Instant at) {
        var source = adjustments.confirmationSource(value); source.requireAuthorization(actors.actor().userId(), at); return source;
    }
    private ExpenseAdjustmentFundingSource currentSource(ExpensePartialAdjustment value, Instant at) {
        var source = sources.current(value.input().basis().funding()); source.requireAuthorization(actors.actor().userId(), at); return source;
    }
    private ExpenseReport locked(UUID id, int round, long applicationVersion, long businessVersion, long settlementVersion) {
        var context = access.locked(id, round); access.requireVersions(context, round, applicationVersion, businessVersion);
        var report = reports.find(actors.actor().tenantId(), id).orElseThrow(ExpensePartialAdjustmentActions::conflict);
        var settlement = settlements.find(report.tenantId(), id).orElseThrow(ExpensePartialAdjustmentActions::conflict);
        if (settlement.version() != settlementVersion || !settlement.resourcesConsumed()) throw conflict(); settlement.requireReport(report); return report;
    }
    private ExpensePartialAdjustment adjustment(ExpenseReport report, UUID id, long version) {
        var value = adjustments.find(report.tenantId(), id).orElseThrow(ExpensePartialAdjustmentActions::conflict);
        if (value.retirement() != null || value.version() != version || !value.input().basis().reportId().equals(report.id())
                || value.input().basis().funding().financial().settlement().input().source().roundNo() != report.requireFrozenRound().roundNo()) throw conflict();
        return value;
    }
    private void requireOwner(String original) {
        if (original == null) throw conflict();
        if (!actors.actor().userId().equals(original)) throw new DomainException("FORBIDDEN", "Only the original authorizer can resend this partial adjustment command");
    }
    private static ExpensePartialAdjustmentAudit.Receipt receipt(ExpenseReport report, ExpensePartialAdjustment value, UUID event) {
        return new ExpensePartialAdjustmentAudit.Receipt(report.id(), report.requireFrozenRound().roundNo(), value.id(), value.version(), null, null, event);
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed partial adjustment, original settlement or round changed"); }

    /**
     * 查询与重发按侧明确指定；确认当前来源不能选择或覆盖有争议的外部结果。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { QUERY_BUDGET, QUERY_ACCRUAL, RESEND_BUDGET, RESEND_ACCRUAL, CONFIRM_CURRENT }
    /**
     * 根修订随每一次单侧状态改变推进，精确绑定页面已显示的两侧实际操作。
     * @author owlzhangfq@gmail.com
     */
    public record OperationInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @NotNull Action action,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String comment) {
        /** 决定只引用已保存的操作，不接受客户端注入财务结果。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment action field"); }
    }
    /**
     * 活动调整重查同时绑定原件修订，零应付也须明确传入两个零版本。
     * @author owlzhangfq@gmail.com
     */
    public record SourceQueryInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @Positive long accrualVersion, @NotNull @Min(0) Long paymentVersion,
            @NotNull @Min(0) Long paymentVoucherVersion, @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String comment) {
        /** 查询的原件身份由结算恢复，请求不能替换交易或凭证。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial source query field"); }
    }
    /**
     * 结束保留办理人和原证明，不接受客户端提供预算或 ERP 的成功状态。
     * @author owlzhangfq@gmail.com
     */
    public record RetireInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) {
        /** 结束依据来自实际状态，不接受客户端强制覆盖效果或占用。 */
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment retirement field"); }
    }
}
