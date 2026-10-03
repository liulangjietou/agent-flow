package io.agentflow.procurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentObservation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原采购轮次权限下的回款投影，金额、资金凭据与 ERP 调整状态各自保持。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentReturnWorkspace {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 50;
    private final CurrentActor actors;
    private final SupplierSettlementAccess access;
    private final SupplierPaymentReturnSources sources;
    private final JdbcSupplierPaymentReturnsRepository ledgers;
    private final JdbcSupplierPaymentReturnCheckRepository checks;
    private final JdbcSupplierPaymentReturnRepository registrations;
    private final SupplierPaymentReturnService service;

    /** 敏感字段权限复用原采购读取，财务自己的候选和全部已登记事实分开展示。 */
    public SupplierPaymentReturnWorkspace(CurrentActor actors, SupplierSettlementAccess access, SupplierPaymentReturnSources sources,
            JdbcSupplierPaymentReturnsRepository ledgers, JdbcSupplierPaymentReturnCheckRepository checks,
            JdbcSupplierPaymentReturnRepository registrations, SupplierPaymentReturnService service) {
        this.actors = actors; this.access = access; this.sources = sources; this.ledgers = ledgers; this.checks = checks; this.registrations = registrations; this.service = service;
    }

    /** 读取不发起外部查询，也不把浏览页面视为确认银行资金。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID paymentId, Map<String, String> parameters) {
        var page = pagination(parameters); var context = access.read(paymentId); var actor = actors.actor();
        var source = sources.find(actor.tenantId(), paymentId).orElse(null); var bank = context.payment();
        var identity = context.authorization().source().reservation().source();
        if (source == null) return new View(paymentId, identity.requestId(), identity.applicationId(), identity.round().roundNo(),
                bank == null ? null : bank.version(), bank == null ? null : bank.status(), 0, false, null, null, null, List.of(), false, null, List.of(), null);
        var ledger = ledgers.find(actor.tenantId(), paymentId).orElse(null); var original = source.request().original();
        var latest = context.finance() ? checks.latest(actor.tenantId(), paymentId, actor.userId()).orElse(null) : null;
        var returned = ledger == null ? Money.zero(original.paidAmount().currency()) : ledger.totalReturned();
        var history = registrations.page(actor.tenantId(), paymentId, page.beforeVersion(), page.limit() + 1);
        var visible = history.stream().limit(page.limit()).map(SupplierPaymentReturnWorkspace::registration).toList();
        return new View(paymentId, identity.requestId(), identity.applicationId(), identity.round().roundNo(), bank.version(), bank.status(), ledger == null ? 0 : ledger.version(),
                ledger != null && ledger.reviewRequired(), new Original(original.paymentReference(), original.receiptReference(), original.paidAmount(), original.completedAt()),
                returned, original.paidAmount().minus(returned), ledger == null ? List.of() : ledger.entries().stream().map(value -> new Returned(value.registrationId(), funding(value.proof()))).toList(),
                context.finance() && (latest == null || !latest.active()), latest == null ? null : check(source, ledger, latest), visible,
                history.size() > page.limit() ? visible.get(visible.size() - 1).returnVersion() : null);
    }

    private Check check(SupplierPaymentReturnSources.Source source, SupplierPaymentReturns ledger, SupplierPaymentReturnCheck value) {
        var receipt = value.receipt(); var issue = service.registrationIssue(source, ledger, value, Instant.now());
        var recorded = ledger == null ? List.<SupplierPaymentReturnPort.BankReceipt>of() : ledger.entries().stream().map(SupplierPaymentReturns.Entry::proof).toList();
        var evidence = receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(),
                receipt.current() == null ? null : receipt.current().revision(), receipt.current() == null ? null : receipt.current().status(),
                receipt.returns().stream().map(SupplierPaymentReturnWorkspace::funding).toList(), receipt.totalReturned(),
                receipt.returns().stream().filter(item -> !recorded.contains(item)).map(SupplierPaymentReturnPort.BankReceipt::amount).reduce(Money.zero(source.request().command().amount().currency()), Money::plus));
        return new Check(value.input().id(), value.version(), value.status(), value.input().requestedAt(), value.updatedAt(), value.issue() == null ? null : value.issue().name(), evidence, issue == null, issue);
    }
    private static Registration registration(JdbcSupplierPaymentReturnRepository.Registered item) {
        var value = item.decision(); return new Registration(value.id(), item.returnVersion(), value.receipt().status(), value.receipt().totalReturned(), value.registeredBy(), value.registeredAt(), value.evidenceReference(), value.reason());
    }
    private static Funding funding(SupplierPaymentReturnPort.BankReceipt value) { return new Funding(value.transactionReference(), value.amount(), value.receivedAt()); }
    private static Page pagination(Map<String, String> parameters) {
        if (!Set.of("beforeVersion", "limit").containsAll(parameters.keySet())) throw invalid();
        try {
            int limit = parameters.containsKey("limit") ? Math.toIntExact(positive(parameters.get("limit"))) : DEFAULT_LIMIT;
            if (limit > MAX_LIMIT) throw invalid();
            return new Page(parameters.containsKey("beforeVersion") ? positive(parameters.get("beforeVersion")) : null, limit);
        } catch (ArithmeticException | NumberFormatException malformed) { throw invalid(); }
    }
    private static long positive(String value) { if (!value.matches("[1-9][0-9]{0,18}")) throw invalid(); return Long.parseLong(value); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_RETURN_QUERY", "Supplier return history only accepts positive beforeVersion and limit up to 50"); }

    /**
     * 原付款扣除已登记回款仅是资金净额，不构成新的付款授权。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID paymentId, UUID requestId, UUID applicationId, int roundNo, Long operationVersion, SupplierPaymentOperation.Status bankStatus,
            long returnVersion, boolean reviewRequired, Original original, Money totalReturned, Money netPaid, List<Returned> returns, boolean canQuery,
            Check latestCheck, List<Registration> registrations, Long nextBeforeVersion) { }
    /**
     * 原交易及原回单可用于核对，完整命令和账号引用不进入投影。
     * @author owlzhangfq@gmail.com
     */
    public record Original(String paymentReference, String receiptReference, Money amount, Instant paidAt) { }
    /**
     * 实际银行流水的最小投影，入款公司账号仍由服务端校验。
     * @author owlzhangfq@gmail.com
     */
    public record Funding(String transactionReference, Money amount, Instant receivedAt) { }
    /**
     * 每笔已登记资金保留首次确认的决定编号。
     * @author owlzhangfq@gmail.com
     */
    public record Returned(UUID registrationId, Funding funding) { }
    /**
     * 只有发起查询的当前独立财务可查看待办理候选。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, SupplierPaymentReturnCheck.Status status, Instant requestedAt, Instant updatedAt, String issue,
            Evidence evidence, boolean canRegister, String registrationIssue) { }
    /**
     * 已读取累计资金和本次尚未登记金额分别展示，外部修订不可自填。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(SupplierPaymentReturnPort.Status outcome, long revision, Instant observedAt, Instant validUntil, Long bankRevision,
            PaymentObservation.Status bankStatus, List<Funding> returns, Money totalReturned, Money newReturned) { }
    /**
     * 有界登记历史保存明确结论和财务凭据，不充当 ERP 调整记录。
     * @author owlzhangfq@gmail.com
     */
    public record Registration(UUID id, long returnVersion, SupplierPaymentReturnPort.Status outcome, Money totalReturned, String registeredBy,
            Instant registeredAt, String evidenceReference, String reason) { }
    /**
     * 历史分页只影响展示，不影响当前累计资金及办理版本。
     * @author owlzhangfq@gmail.com
     */
    private record Page(Long beforeVersion, int limit) { }
}
