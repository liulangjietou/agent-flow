package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开准备和授权补齐实时角色、字段、版本及审计，底层准备服务只编排实际财务证据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentDecisions {
    private final CurrentActor actors;
    private final ExpenseResourceAdjustmentAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePartialPreparationService preparing;
    private final ExpensePartialAdjustmentAudit audit;
    /** 原报销锁覆盖公开鉴权后的重新检查、单侧决定、永久命令和审计。 */
    public ExpensePartialAdjustmentDecisions(CurrentActor actors, ExpenseResourceAdjustmentAccess access, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcExpensePartialAdjustmentRepository adjustments,
            ExpensePartialPreparationService preparing, ExpensePartialAdjustmentAudit audit) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements; this.adjustments = adjustments; this.preparing = preparing; this.audit = audit;
    }
    /** 只保存本人选择的一侧读取意图，页面不能指定外部命令或读取结果。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt prepare(UUID id, PrepareInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion(), input.settlementVersion());
        var adjustment = adjustment(report, input.adjustmentId(), input.adjustmentVersion()); var actor = actors.actor();
        var value = preparing.register(actor.tenantId(), adjustment.id(), adjustment.version(), input.side(), input.accountingDate(), actor.userId(), input.evidenceReference(), input.reason(), Instant.now());
        var event = audit.record(report, value.input().id(), value.version(), ExpensePartialAdjustmentAudit.Action.PREPARE, input.reason(), value.updatedAt());
        return new ExpensePartialAdjustmentAudit.Receipt(id, input.roundNo(), adjustment.id(), adjustment.version(), value.input().id(), value.version(), event);
    }
    /** 本人明确确认才消费真实准备；审计失败也回滚准备、调整和固定命令。 */
    @Transactional
    public ExpensePartialAdjustmentAudit.Receipt authorize(UUID id, AuthorizeInput input) {
        var report = locked(id, input.roundNo(), input.applicationVersion(), input.businessVersion(), input.settlementVersion());
        var adjustment = adjustment(report, input.adjustmentId(), input.adjustmentVersion()); var actor = actors.actor();
        var after = preparing.authorize(actor.tenantId(), adjustment.id(), adjustment.version(), input.preparationId(), input.preparationVersion(), actor.userId(), Instant.now());
        var event = audit.record(report, after.id(), after.version(), ExpensePartialAdjustmentAudit.Action.AUTHORIZE, input.comment(), after.updatedAt());
        return new ExpensePartialAdjustmentAudit.Receipt(id, input.roundNo(), after.id(), after.version(), input.preparationId(), Math.incrementExact(input.preparationVersion()), event);
    }
    private ExpenseReport locked(UUID id, int round, long applicationVersion, long businessVersion, long settlementVersion) {
        var context = access.locked(id, round); access.requireVersions(context, round, applicationVersion, businessVersion);
        var report = reports.find(actors.actor().tenantId(), id).orElseThrow(ExpensePartialAdjustmentDecisions::conflict);
        var settlement = settlements.find(report.tenantId(), id).orElseThrow(ExpensePartialAdjustmentDecisions::conflict);
        if (settlement.version() != settlementVersion || !settlement.resourcesConsumed()) throw conflict(); settlement.requireReport(report); return report;
    }
    private ExpensePartialAdjustment adjustment(ExpenseReport report, UUID id, long version) {
        var value = adjustments.find(report.tenantId(), id).orElseThrow(ExpensePartialAdjustmentDecisions::conflict);
        if (!value.input().basis().reportId().equals(report.id()) || value.version() != version
                || value.input().basis().funding().financial().settlement().input().source().roundNo() != report.requireFrozenRound().roundNo()) throw conflict();
        return value;
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Displayed partial adjustment, original settlement or round changed"); }

    /**
     * 准备意图绑定当前调整和原财务修订，只允许一侧及明确日期。
     * @author owlzhangfq@gmail.com
     */
    public record PrepareInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @NotNull ExpensePartialAdjustmentPreparation.Side side, @NotNull LocalDate accountingDate,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cntrl}]+") String evidenceReference,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment preparation field"); }
    }
    /**
     * 精确消费页面展示的就绪修订，不能传入成功快照或延长有效期。
     * @author owlzhangfq@gmail.com
     */
    public record AuthorizeInput(@Positive int roundNo, @Positive long applicationVersion, @Positive long businessVersion, @Positive long settlementVersion,
            @NotNull UUID adjustmentId, @Positive long adjustmentVersion, @NotNull UUID preparationId, @Positive long preparationVersion,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\p{Cntrl}]+") String comment) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown partial adjustment authorization field"); }
    }
}
