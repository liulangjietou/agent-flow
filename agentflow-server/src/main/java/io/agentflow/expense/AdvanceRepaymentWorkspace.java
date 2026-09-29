package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentPersonnel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 借款详情中的余额、还款原件摘要及有界历史，沿用原轮次完整字段权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRepaymentWorkspace {
    private static final int PAGE_SIZE = 25;
    private final CurrentActor actors;
    private final AdvanceRequestService requests;
    private final EmployeeAdvanceRepository balances;
    private final AdvanceRepaymentSources sources;
    private final JdbcAdvanceRepaymentCheckRepository checks;
    private final JdbcAdvanceRepaymentRepository repayments;
    private final PaymentPersonnel personnel;
    private final AdvanceRepaymentService service;
    /** 只在原借款已可读后查询资金材料，管理员不获得额外旁路。 */
    public AdvanceRepaymentWorkspace(CurrentActor actors, AdvanceRequestService requests, EmployeeAdvanceRepository balances, AdvanceRepaymentSources sources,
            JdbcAdvanceRepaymentCheckRepository checks, JdbcAdvanceRepaymentRepository repayments, PaymentPersonnel personnel, AdvanceRepaymentService service) {
        this.actors = actors; this.requests = requests; this.balances = balances; this.sources = sources; this.checks = checks; this.repayments = repayments; this.personnel = personnel; this.service = service;
    }
    /** 读取和刷新均无副作用，未到账借款只显示空余额。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View read(UUID id, Map<String, String> parameters) {
        if (!Set.of("roundNo", "beforeId").containsAll(parameters.keySet())) throw invalid();
        Integer round = null; UUID before = null;
        try {
            if (parameters.containsKey("roundNo")) { var raw = parameters.get("roundNo"); if (!raw.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(raw); }
            if (parameters.containsKey("beforeId")) { var raw = parameters.get("beforeId"); before = UUID.fromString(raw); if (!before.toString().equals(raw)) throw invalid(); }
        } catch (IllegalArgumentException malformed) { throw invalid(); }
        var detail = requests.read(id, round); var actor = actors.actor(); var advance = balances.find(actor.tenantId(), id).orElse(null);
        if (advance == null) { if (before != null) throw invalid(); return new View(detail.applicationId(), id, detail.roundNo(), null, false, null, List.of(), null); }
        var source = sources.find(actor.tenantId(), id);
        if (source.authorization().terms().binding().roundNo() != detail.roundNo()) return new View(detail.applicationId(), id, detail.roundNo(), null, false, null, List.of(), null);
        boolean finance = actor.hasRole("FINANCE") && !actor.userId().equals(advance.employeeId()) && personnel.eligible(actor.tenantId(), actor.userId(), advance.legalEntityId());
        var latest = finance ? checks.latest(actor.tenantId(), id, actor.userId()).orElse(null) : null;
        var rows = repayments.list(actor.tenantId(), id, before, PAGE_SIZE + 1); var page = rows.stream().limit(PAGE_SIZE).map(value -> recorded(value, advance)).toList();
        boolean query = finance && source.payment().settleable() && !advance.paymentReviewRequired() && (latest == null || !latest.active());
        return new View(detail.applicationId(), id, detail.roundNo(), balance(advance), query, latest == null ? null : check(source, latest), page,
                rows.size() > PAGE_SIZE ? page.get(page.size() - 1).id() : null);
    }
    private Check check(AdvanceRepaymentSources.Source source, AdvanceRepaymentCheck value) {
        var issue = service.confirmationIssue(source, value, Instant.now()); var receipt = value.receipt();
        return new Check(value.input().id(), value.version(), value.status(), value.input().request().receiptReference(), value.input().requestedAt(), value.updatedAt(),
                receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(), receipt.funding(), receipt.posting()),
                value.issue() == null ? null : value.issue().name(), issue == null, issue);
    }
    /** 本人资金列表与借款详情使用同一余额投影，已还款和报销冲销分别显示。 */
    public static Balance balance(EmployeeAdvance value) { return new Balance(value.version(), value.status(), value.balance().limit(), value.available(), value.balance().reserved(), value.balance().consumed(), value.repaid(), value.outstanding(), value.receivedRepayments(), value.returnedRepayments(), value.returnedDisbursements()); }
    /** 原收款一直保留，退回和逐笔冻结作为独立事实同时展示。 */
    public static Recorded recorded(AdvanceRepayment value, EmployeeAdvance advance) {
        var returns = advance.repaymentReturns().stream().filter(entry -> entry.repaymentId().equals(value.id())).toList();
        var receipt = value.receipt(); return new Recorded(value.id(), receipt.request().receiptReference(), receipt.funding().channel(), value.amount(), receipt.funding().receivedAt(),
                receipt.posting().voucherReference(), receipt.posting().entryReference(), receipt.posting().accountingDate(), receipt.posting().postedAt(), value.recordedBy(), value.recordedAt(),
                advance.repaymentReviews().contains(value.id()), returns.isEmpty() ? null : returns.get(0), returns.stream().skip(1).toList());
    }
    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REPAYMENT_QUERY", "Repayment history query is invalid"); }
    /**
     * 轮次权限之下的最小工作区，不复制原账户、外部地址及理由自由文本。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID advanceId, int roundNo, Balance balance, boolean canQuery, Check latestCheck, List<Recorded> records, UUID nextBeforeId) { }
    /**
     * 保持借出等于报销冲销、已还款及未还款的守恒关系。
     * @author owlzhangfq@gmail.com
     */
    public record Balance(long version, EmployeeAdvance.Status status, Money paid, Money available, Money reserved, Money offset, Money repaid, Money outstanding, Money receivedRepayments, Money returnedRepayments, Money returnedDisbursements) { }
    /**
     * 仅当前财务可见自己的最新查询，查询完成仍需明确确认。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, AdvanceRepaymentCheck.Status status, String receiptReference, Instant requestedAt, Instant updatedAt, Evidence evidence, String issue, boolean canRecord, String confirmationIssue) { }
    /**
     * 确认前显示原资金和会计依据，不接收页面修改。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(AdvanceRepaymentPort.Status status, long revision, Instant observedAt, Instant validUntil, AdvanceRepaymentPort.Funding funding, AdvanceRepaymentPort.Posting posting) { }
    /**
     * 已确认历史保留记账依据，金额由原收款派生。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Recorded(UUID id, String receiptReference, AdvanceRepaymentPort.Channel channel, Money amount, Instant receivedAt, String voucherReference,
                           String entryReference, java.time.LocalDate accountingDate, Instant postedAt, String recordedBy, Instant recordedAt, boolean reviewRequired,
                           AdvanceRepaymentResolution.ReturnEntry returned, List<AdvanceRepaymentResolution.ReturnEntry> additionalReturns) { }
}
