package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.finance.PaymentPersonnel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 只投影当前身份可读的原放款和独立退回，不公开付款账户、固定目标或完整命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceDisbursementReturnWorkspace {
    private final CurrentActor actors;
    private final AdvanceRequestService requests;
    private final AdvanceDisbursementReturnSources sources;
    private final JdbcDisbursementReturnCheckRepository checks;
    private final JdbcDisbursementResolutionRepository decisions;
    private final PaymentPersonnel personnel;
    private final AdvanceDisbursementReturnService service;
    /** 候选归查询财务，已确认回款允许原申请人读取。 */
    public AdvanceDisbursementReturnWorkspace(CurrentActor actors, AdvanceRequestService requests, AdvanceDisbursementReturnSources sources,
            JdbcDisbursementReturnCheckRepository checks, JdbcDisbursementResolutionRepository decisions, PaymentPersonnel personnel, AdvanceDisbursementReturnService service) {
        this.actors = actors; this.requests = requests; this.sources = sources; this.checks = checks; this.decisions = decisions; this.personnel = personnel; this.service = service;
    }
    /** 原轮次完整字段权限与当前任职共同约束，读取不排队、不确认、不续期。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View read(UUID advanceId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) { var raw = parameters.get("roundNo"); if (!raw.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(raw); }
        var detail = requests.read(advanceId, round); var actor = actors.actor(); var source = sources.find(actor.tenantId(), advanceId); var funding = source.funding();
        if (funding.authorization().terms().binding().roundNo() != detail.roundNo()) throw new DomainException("NOT_FOUND", "Original disbursement is unavailable in this round");
        boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(funding.advance().employeeId())
                && !actor.userId().equals(source.request().command().authorization().executedBy()) && personnel.eligible(actor.tenantId(), actor.userId(), funding.advance().legalEntityId());
        var latest = finance ? checks.latest(actor.tenantId(), advanceId, actor.userId()).orElse(null) : null;
        var decision = decisions.latest(actor.tenantId(), advanceId).orElse(null); var original = source.request().original();
        return new View(detail.applicationId(), advanceId, detail.roundNo(), AdvanceRepaymentWorkspace.balance(funding.advance()),
                new Original(original.authorizationId(), original.paymentReference(), original.paidAmount(), original.completedAt()), funding.advance().disbursementReturns(),
                finance && (latest == null || !latest.active()), latest == null ? null : check(source, latest),
                decision == null ? null : new Decision(decision.id(), decision.receipt().status(), decision.resolvedBy(), decision.resolvedAt(), decision.evidenceReference()));
    }
    private Check check(AdvanceDisbursementReturnSources.Source source, AdvanceDisbursementReturnCheck value) {
        var receipt = value.receipt(); var issue = service.confirmationIssue(source, value, Instant.now());
        return new Check(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue() == null ? null : value.issue().name(),
                receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(),
                        receipt.current() == null ? null : receipt.current().revision(), receipt.current() == null ? null : receipt.current().status(), receipt.returns()), issue == null, issue);
    }
    private static DomainException invalid() { return new DomainException("INVALID_DISBURSEMENT_RETURN_QUERY", "Disbursement return query is invalid"); }
    /**
     * 最小原放款身份与累计余额，不包括内部命令或银行账户引用。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID advanceId, int roundNo, AdvanceRepaymentWorkspace.Balance balance, Original original,
            List<AdvanceDisbursementReturn.Entry> returns, boolean canQuery, Check latestCheck, Decision latestDecision) { }
    /**
     * 付款号和银行交易号用于核验实际归属。
     * @author owlzhangfq@gmail.com
     */
    public record Original(UUID paymentId, String paymentReference, Money amount, Instant paidAt) { }
    /**
     * 候选只能由原查询发起人消费一次。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, AdvanceDisbursementReturnCheck.Status status, Instant requestedAt, Instant updatedAt, String issue, Evidence evidence, boolean canResolve, String confirmationIssue) { }
    /**
     * 银行入款与 ERP 借款贷方分录逐笔列出，累计事实不能当成新增金额。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(AdvanceDisbursementReturnPort.Status status, long revision, Instant observedAt, Instant validUntil, Long originalRevision,
            PaymentObservation.Status originalStatus, List<AdvanceDisbursementReturnPort.ReturnItem> returns) { }
    /**
     * 已确认材料只展示编号和审核身份。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(UUID id, AdvanceDisbursementReturnPort.Status outcome, String resolvedBy, Instant resolvedAt, String evidenceReference) { }
}
