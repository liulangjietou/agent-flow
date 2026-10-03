package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.PaymentPersonnel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原还款、净余额及追加决定的最小投影，候选外部材料仅向当前独立财务展示。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentReviewWorkspace {
    private final CurrentActor actors;
    private final AdvanceRequestService requests;
    private final AdvanceRepaymentSources sources;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final JdbcRepaymentReviewCheckRepository checks;
    private final JdbcRepaymentResolutionRepository decisions;
    private final PaymentPersonnel personnel;
    private final AdvanceRepaymentReviewService service;
    /** 原轮次完整字段权限决定是否可以读取任何金融原件。 */
    public AdvanceRepaymentReviewWorkspace(CurrentActor actors, AdvanceRequestService requests, AdvanceRepaymentSources sources, JdbcAdvanceRepaymentRepository repayments,
            JdbcRepaymentReviewCheckRepository checks, JdbcRepaymentResolutionRepository decisions, PaymentPersonnel personnel, AdvanceRepaymentReviewService service) {
        this.actors = actors; this.requests = requests; this.sources = sources; this.repayments = repayments; this.checks = checks; this.decisions = decisions; this.personnel = personnel; this.service = service;
    }
    /** 读取没有副作用，申请人可以核对自己的原记录和已确认退回。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View read(UUID advanceId, UUID repaymentId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) { var raw = parameters.get("roundNo"); if (!raw.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(raw); }
        var detail = requests.read(advanceId, round); var actor = actors.actor(); var source = sources.find(actor.tenantId(), advanceId);
        var original = repayments.find(actor.tenantId(), repaymentId).orElseThrow(AdvanceRepaymentReviewWorkspace::notFound);
        if (!original.belongsTo(source.advance()) || source.authorization().terms().binding().roundNo() != detail.roundNo()) throw notFound();
        boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(source.advance().employeeId()) && personnel.eligible(actor.tenantId(), actor.userId(), source.advance().legalEntityId());
        var latest = finance ? checks.latest(actor.tenantId(), repaymentId, actor.userId()).orElse(null) : null;
        var decision = decisions.latest(actor.tenantId(), repaymentId).orElse(null);
        return new View(detail.applicationId(), advanceId, detail.roundNo(), AdvanceRepaymentWorkspace.balance(source.advance()), AdvanceRepaymentWorkspace.recorded(original, source.advance()),
                finance && (latest == null || !latest.active()), latest == null ? null : check(source, latest), decision == null ? null : new Decision(decision.id(), decision.receipt().status(), decision.resolvedBy(), decision.resolvedAt(), decision.evidenceReference()));
    }
    private Check check(AdvanceRepaymentSources.Source source, AdvanceRepaymentReviewCheck value) {
        var receipt = value.receipt(); var issue = service.confirmationIssue(source, value, Instant.now());
        return new Check(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue() == null ? null : value.issue().name(),
                receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(), receipt.current() == null ? null : receipt.current().revision(), receipt.fundsReturn(), receipt.posting(), receipt.additionalReturns()), issue == null, issue);
    }
    private static DomainException invalid() { return new DomainException("INVALID_REPAYMENT_REVIEW_QUERY", "Repayment review query is invalid"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original repayment is unavailable in the current scope"); }
    /**
     * 不公开外部地址、账户、目标摘要或自由文本材料。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID advanceId, int roundNo, AdvanceRepaymentWorkspace.Balance balance, AdvanceRepaymentWorkspace.Recorded original, boolean canQuery, Check latestCheck, Decision latestDecision) { }
    /**
     * 候选证据只允许原查询发起人裁决，不以查看动作续期。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, AdvanceRepaymentReviewCheck.Status status, Instant requestedAt, Instant updatedAt, String issue, Evidence evidence, boolean canResolve, String confirmationIssue) { }
    /**
     * 资金与借方分录均明确展示，金额只读。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(AdvanceRepaymentAdjustmentPort.Status status, long revision, Instant observedAt, Instant validUntil, Long originalRevision,
            AdvanceRepaymentAdjustmentPort.FundsReturn fundsReturn, AdvanceRepaymentAdjustmentPort.ReturnPosting posting, List<AdvanceRepaymentAdjustmentPort.ReturnItem> additionalReturns) { }
    /**
     * 已确认决定保留独立操作者与核验材料编号。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, AdvanceRepaymentAdjustmentPort.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
}
