package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.ExpensePaymentReturnPort;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按原轮次字段权限展示报销退回，完整付款命令、账户与目标摘要不进入公开响应。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePaymentReturnWorkspace {
    private final CurrentActor actors;
    private final ExpenseSettlementAccess access;
    private final ExpensePaymentReturnSources sources;
    private final JdbcExpensePaymentReturnsRepository ledgers;
    private final JdbcExpensePaymentReturnCheckRepository checks;
    private final JdbcExpensePaymentReturnRepository registrations;
    private final ExpensePaymentReturnService service;
    public ExpensePaymentReturnWorkspace(CurrentActor actors, ExpenseSettlementAccess access, ExpensePaymentReturnSources sources,
            JdbcExpensePaymentReturnsRepository ledgers, JdbcExpensePaymentReturnCheckRepository checks, JdbcExpensePaymentReturnRepository registrations, ExpensePaymentReturnService service) {
        this.actors = actors; this.access = access; this.sources = sources; this.ledgers = ledgers; this.checks = checks; this.registrations = registrations; this.service = service;
    }
    /** 只读不会发起查询；自己的候选与全部已登记事实分开投影。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View read(UUID reportId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid(); Integer round = null;
        if (parameters.containsKey("roundNo")) {
            var value = parameters.get("roundNo"); if (!value.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(value);
        }
        var context = access.read(reportId, round); var actor = actors.actor(); var source = sources.find(actor.tenantId(), reportId);
        if (context.roundNo() != source.request().command().binding().roundNo()) throw new DomainException("NOT_FOUND", "Original expense payment is unavailable in this round");
        var ledger = ledgers.find(actor.tenantId(), reportId).orElse(null); var original = source.request().original();
        boolean finance = access.canManage(context, reportId) && !actor.userId().equals(source.request().command().authorization().executedBy());
        var latest = finance ? checks.latest(actor.tenantId(), reportId, actor.userId()).orElse(null) : null;
        var returned = ledger == null ? Money.zero(original.paidAmount().currency()) : ledger.totalReturned();
        return new View(reportId, source.report().applicationId(), context.roundNo(), source.settlement().version(), source.settlement().status(), ledger == null ? 0 : ledger.version(),
                ledger != null && ledger.reviewRequired(), new Original(original.authorizationId(), original.paymentReference(), original.paidAmount(), original.completedAt()), returned,
                original.paidAmount().minus(returned), ledger == null ? List.of() : ledger.entries(), finance && (latest == null || !latest.active()),
                latest == null ? null : check(source, ledger, latest), registrations.history(actor.tenantId(), reportId).stream().map(value -> new Registration(value.id(),
                        value.receipt().status(), value.receipt().totalReturned(), value.registeredBy(), value.registeredAt(), value.evidenceReference(), value.reason())).toList());
    }
    private Check check(ExpensePaymentReturnSources.Source source, ExpensePaymentReturns ledger, ExpensePaymentReturnCheck value) {
        var receipt = value.receipt(); var issue = service.registrationIssue(source, ledger, value, Instant.now());
        var recorded = ledger == null ? List.<ExpensePaymentReturnPort.ReturnItem>of() : ledger.entries().stream().map(ExpensePaymentReturns.Entry::proof).toList();
        var evidence = receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(),
                receipt.current() == null ? null : receipt.current().revision(), receipt.current() == null ? null : receipt.current().status(), receipt.returns(), receipt.totalReturned(),
                receipt.returns().stream().filter(item -> !recorded.contains(item)).map(item -> item.funding().amount()).reduce(Money.zero(source.request().command().amount().currency()), Money::plus));
        return new Check(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue() == null ? null : value.issue().name(), evidence, issue == null, issue);
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PAYMENT_RETURN_QUERY", "Expense payment return query only accepts a positive roundNo"); }

    /**
     * 原净付款与累计实退分别显示，净额只表示原付款扣除登记退回，不代表重新付款授权。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, long settlementVersion, ExpenseSettlement.Status settlementStatus, long returnVersion,
                       boolean reviewRequired, Original original, Money totalReturned, Money netPaid, List<ExpensePaymentReturns.Entry> returns,
                       boolean canQuery, Check latestCheck, List<Registration> registrations) { }
    /**
     * 原交易的最小归属依据，不展示银行账户引用。
     * @author owlzhangfq@gmail.com
     */
    public record Original(UUID paymentId, String paymentReference, Money amount, Instant paidAt) { }
    /**
     * 候选只对原查询财务展示，确认入口仍须重读权限及版本。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, ExpensePaymentReturnCheck.Status status, Instant requestedAt, Instant updatedAt, String issue,
                        Evidence evidence, boolean canRegister, String registrationIssue) { }
    /**
     * 累计及本次新增金额明确区分，每笔必须同时有银行入款和应付贷方。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(ExpensePaymentReturnPort.Status status, long revision, Instant observedAt, Instant validUntil, Long originalRevision,
                           PaymentObservation.Status originalStatus, List<ExpensePaymentReturnPort.ReturnItem> returns, Money totalReturned, Money newReturned) { }
    /**
     * 独立财务材料与当时累计结果保留供原轮次复核。
     * @author owlzhangfq@gmail.com
     */
    public record Registration(UUID id, ExpensePaymentReturnPort.Status outcome, Money totalReturned, String registeredBy, Instant registeredAt, String evidenceReference, String reason) { }
}
