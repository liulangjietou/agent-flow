package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实际回款、独立 ERP 调整与本地完成分别投影，历史记账不覆盖当前资金状态。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentWorkspace {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final SupplierAdjustmentAccess access;
    private final SupplierAdjustmentSources sources;
    private final JdbcSupplierPaymentReturnsRepository returns;
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final JdbcSupplierPayableAdjustmentRepository adjustments;
    private final JdbcSupplierAdjustmentCompletions completions;

    /** 只读事务不向银行或 ERP 发出请求，完整命令、账户和财务目标保留在服务端。 */
    public SupplierAdjustmentWorkspace(CurrentActor actors, SupplierAdjustmentAccess access, SupplierAdjustmentSources sources,
            JdbcSupplierPaymentReturnsRepository returns, JdbcSupplierAdjustmentPreparationRepository preparations,
            JdbcSupplierPayableAdjustmentRepository adjustments, JdbcSupplierAdjustmentCompletions completions) {
        this.actors = actors; this.access = access; this.sources = sources; this.returns = returns;
        this.preparations = preparations; this.adjustments = adjustments; this.completions = completions;
    }

    /** 当前资金和有界历史来自同一快照；游标只能指向同一原付款。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID paymentId, Map<String, String> parameters) {
        var query = pagination(parameters); var context = access.read(paymentId); var actor = actors.actor(); var tenant = actor.tenantId();
        var authorization = context.authorization(); var identity = authorization.source().reservation().source();
        var before = query.beforeId() == null ? null : adjustments.find(tenant, query.beforeId()).orElseThrow(SupplierAdjustmentWorkspace::invalid);
        if (before != null && !before.command().source().returns().request().command().id().equals(paymentId)) throw invalid();
        var rows = adjustments.page(tenant, paymentId, before, query.limit());
        var items = rows.stream().limit(query.limit()).map(value -> operation(value, context.finance())).toList();
        var active = adjustments.active(tenant, paymentId).orElse(null);
        var pending = preparations.active(tenant, paymentId).orElseGet(() -> preparations.latest(tenant, paymentId).orElse(null));
        var ledger = returns.find(tenant, paymentId).orElse(null); var amount = authorization.source().amount();
        var returned = ledger == null ? Money.zero(amount.currency()) : ledger.totalReturned();
        var accounted = ledger == null ? Money.zero(amount.currency()) : ledger.entries().stream().limit(ledger.accountedEntryCount())
                .map(entry -> entry.proof().amount()).reduce(Money.zero(amount.currency()), Money::plus);
        var candidate = context.finance() ? preview(tenant, paymentId, actor.userId()) : null;
        var completed = ledger == null || ledger.accounting() == null ? null : completion(tenant, ledger.accounting().operationId());
        var bank = context.payment();
        return new View(paymentId, identity.requestId(), identity.applicationId(), identity.round().roundNo(), identity.round().content().legalEntityId(),
                authorization.payable().supplierName(), amount, authorization.payable().account().maskedAccount(),
                bank == null ? null : new Bank(bank.version(), bank.status(), bank.command().cashier(), bank.updatedAt(), bank.conflictingObservation() != null),
                ledger == null ? 0 : ledger.version(), ledger != null && ledger.reviewRequired(), returned, accounted, returned.minus(accounted), amount.minus(returned),
                candidate == null ? null : minimumDate(candidate),
                pending == null ? null : new Preparation(pending.input().id(), pending.version(), pending.status(), pending.input().financeActor(), pending.input().accountingDate(), pending.updatedAt(), pending.issue()),
                active == null ? null : active.command().id(), items, rows.size() > query.limit() ? items.get(items.size() - 1).id() : null, completed,
                candidate != null && active == null && (pending == null || !pending.active()));
    }

    private Operation operation(SupplierPayableAdjustmentOperation value, boolean finance) {
        var command = value.command(); var source = command.source(); var tenant = command.tenantId();
        var retired = adjustments.retirement(tenant, command.id()).orElse(null); var observed = value.observation(); var posting = observed == null ? null : observed.posting();
        boolean actionable = finance && retired == null;
        return new Operation(command.id(), value.version(), value.status(), command.financeActor(), command.period().request().accountingDate(), command.period().periodReference(),
                source.returns().version(), source.newReturned(), source.returns().totalReturned(), source.netPaid(), source.recognizesOriginalPayment(),
                command.registeredAt(), value.updatedAt(), value.attempts(), value.dispatches(), observed == null ? null : observed.status(), value.conflictingObservation() != null,
                value.failure() == null ? null : value.failure().name(), observed == null ? null : observed.rejection(),
                posting == null ? null : new Posting(posting.adjustmentReference(), posting.recognitionVoucherReference(), posting.returnedAmount(), posting.totalReturned(), posting.netPaid(),
                        posting.payableSettledBefore(), posting.payableSettledAfter(), posting.entries().stream().map(entry -> new Entry(entry.transactionReference(), entry.amount(), entry.voucherReference(), entry.entryReference())).toList(), posting.adjustedAt()),
                retired == null ? null : new Retirement(retired.retiredBy(), retired.retiredAt(), retired.basis()), completion(tenant, command.id()),
                new Actions(actionable && !value.running() && value.status() != SupplierPayableAdjustmentOperation.Status.QUEUED && value.dispatches() > 0,
                        actionable && value.status() == SupplierPayableAdjustmentOperation.Status.NOT_FOUND && value.highestRevision() == 0 && value.conflictingObservation() == null
                                && source.equals(preview(tenant, source.returns().request().command().id(), command.financeActor())),
                        actionable && value.retirementBasis() != null));
    }
    private SupplierPayableAdjustmentSource preview(String tenant, UUID paymentId, String finance) {
        try { return sources.preview(tenant, paymentId, finance); } catch (DomainException unavailable) { return null; }
    }
    private Completion completion(String tenant, UUID id) {
        return completions.find(tenant, id).map(value -> new Completion(id, value.operation().version(), value.after().version(), value.after().accountedEntryCount(), value.completedAt())).orElse(null);
    }
    private static LocalDate minimumDate(SupplierPayableAdjustmentSource source) {
        var zone = ZoneId.of(source.returns().request().command().holdCommand().authorization().source().reservation().source().round().legalEntity().timeZone());
        var minimum = source.newReturns().stream().map(entry -> entry.proof().receivedAt().atZone(zone).toLocalDate()).max(LocalDate::compareTo).orElseThrow();
        if (source.settlement() != null && source.settlement().observation().posting().accountingDate().isAfter(minimum)) minimum = source.settlement().observation().posting().accountingDate();
        if (source.previous() != null && source.previous().observation().posting().accountingDate().isAfter(minimum)) minimum = source.previous().observation().posting().accountingDate();
        return minimum;
    }
    private static Query pagination(Map<String, String> parameters) {
        if (!Set.of("limit", "beforeId").containsAll(parameters.keySet())) throw invalid();
        try {
            var raw = parameters.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT)); if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
            int limit = Integer.parseInt(raw); if (limit > MAX_LIMIT) throw invalid();
            UUID before = parameters.containsKey("beforeId") ? UUID.fromString(parameters.get("beforeId")) : null;
            if (before != null && !before.toString().equals(parameters.get("beforeId"))) throw invalid(); return new Query(limit, before);
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_ADJUSTMENT_QUERY", "Adjustment history requires a bounded limit and a canonical cursor from the same original payment"); }

    /**
     * 固定原付款范围内的历史页参数。
     * @author owlzhangfq@gmail.com
     */
    private record Query(int limit, UUID beforeId) { }
    /**
     * 已登记、已入账和待入账金额独立；历史完成不能消除当前复核状态。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID paymentId, UUID requestId, UUID applicationId, int roundNo, UUID legalEntityId, String supplierName, Money amount, String maskedPayeeAccount,
            Bank bank, long returnVersion, boolean reviewRequired, Money totalReturned, Money accountedReturned, Money pendingReturned, Money netPaid, LocalDate minimumAccountingDate,
            Preparation preparation, UUID activeAdjustmentId, List<Operation> items, UUID nextBeforeId, Completion completion, boolean canPrepare) { }
    /**
     * 原银行当前状态单独展示，不泄露完整账户与命令。
     * @author owlzhangfq@gmail.com
     */
    public record Bank(long version, SupplierPaymentOperation.Status status, String cashier, Instant updatedAt, boolean disputed) { }
    /**
     * 准备完成仅代表命令已保存。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id, long version, SupplierAdjustmentPreparation.Status status, String financeActor, LocalDate accountingDate, Instant updatedAt, SupplierAdjustmentPreparation.Issue issue) { }
    /**
     * 每次调整展示固定资金范围及自己的本地完成事实，之后追加入款仍属于新调整。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(UUID id, long version, SupplierPayableAdjustmentOperation.Status status, String financeActor, LocalDate accountingDate, String periodReference,
            long returnVersion, Money returnedAmount, Money totalReturned, Money netPaid, boolean recognizesOriginalPayment, Instant createdAt, Instant updatedAt, int attempts, int dispatches,
            SupplierPayableAdjustmentObservation.Status observedStatus, boolean disputed, String issue, SupplierPayableAdjustmentObservation.Rejection rejection,
            Posting posting, Retirement retirement, Completion completion, Actions actions) { }
    /**
     * 原付款凭证及本次回款分录可核对，内部预留和账本版本不外显。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(String adjustmentReference, String recognitionVoucherReference, Money returnedAmount, Money totalReturned, Money netPaid,
            Money payableSettledBefore, Money payableSettledAfter, List<Entry> entries, Instant adjustedAt) { }
    /**
     * 银行资金与独立 ERP 分录的逐笔对应。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(String transactionReference, Money amount, String voucherReference, String entryReference) { }
    /**
     * 安全结束不修改原资金和历史记账。
     * @author owlzhangfq@gmail.com
     */
    public record Retirement(String retiredBy, Instant retiredAt, SupplierPayableAdjustmentOperation.RetirementBasis basis) { }
    /**
     * 本地完成定位真实 ERP 成功修订及其后继资金账本。
     * @author owlzhangfq@gmail.com
     */
    public record Completion(UUID adjustmentId, long adjustmentVersion, long returnVersion, int accountedEntries, Instant completedAt) { }
    /**
     * 仅供展示，实际办理仍核对当前权限和版本。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean query, boolean retryOriginal, boolean retire) { }
}
