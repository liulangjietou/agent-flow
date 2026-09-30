package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已实际放款的员工借款拥有自己的余额，审批通过本身不能生成可冲销资金。
 * @author owlzhangfq@gmail.com
 */
public final class EmployeeAdvance {
    private final UUID id;
    private final String tenantId;
    private final UUID legalEntityId;
    private final String employeeId;
    private final String paymentReference;
    private final LocalDate paidOn;
    private final LocalDate dueOn;
    private ReservedAmount balance;
    private boolean paymentReviewRequired;
    private List<UUID> voucherReviews = List.of();
    private List<UUID> repaymentReviews = List.of();
    private List<AdvanceRepayment.Entry> repayments = List.of();
    private List<AdvanceRepaymentResolution.ReturnEntry> repaymentReturns = List.of();
    private List<AdvanceDisbursementReturn.Entry> disbursementReturns = List.of();
    private long version = 1;

    /** 放款适配器确认成功后创建；外部放款引用由仓储按租户唯一约束。 */
    public EmployeeAdvance(UUID id, String tenantId, UUID legalEntityId, String employeeId, Money paidAmount,
                            String paymentReference, LocalDate paidOn, LocalDate dueOn) {
        this.id = Objects.requireNonNull(id); this.legalEntityId = Objects.requireNonNull(legalEntityId);
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                || StringUtils.isBlank(paymentReference) || paymentReference.length() > 128 || paidAmount == null || paidAmount.value().signum() <= 0
                || paidOn == null || dueOn == null) throw new DomainException("INVALID_ADVANCE", "Verified advance payment identity, amount and dates are required");
        this.tenantId = tenantId; this.employeeId = employeeId; this.paymentReference = paymentReference;
        this.paidOn = paidOn; this.dueOn = dueOn; balance = ReservedAmount.available(paidAmount);
    }

    /** 整单冲销按本位币预留，在额度账本中原子替换自己的原预留。 */
    public void reserve(long expectedVersion, ExpenseUse use, Money amount) {
        requireVersion(expectedVersion); requireWholeReport(use);
        if (reviewRequired() && amount.compareTo(balance.reservedFor(use)) > 0) throw review();
        var changed = balance.reserve(use, amount); requireRepaymentCapacity(changed); balance = changed; version++;
    }

    /** 退回重提迁移已有预留，旧轮次不再具有可消费余额。 */
    public void move(long expectedVersion, ExpenseUse previous, ExpenseUse next, Money amount) {
        requireVersion(expectedVersion); requireWholeReport(previous); requireWholeReport(next);
        if (reviewRequired() && amount.value().signum() > 0) throw review();
        var changed = balance.move(previous, next, amount); requireRepaymentCapacity(changed); balance = changed; version++;
    }

    /** 付款成功或零应付结算时才消耗预留。 */
    public void settle(long expectedVersion, ExpenseUse use) {
        requireVersion(expectedVersion); requireWholeReport(use);
        if (reviewRequired()) throw review();
        var changed = balance.consume(use); balance = changed; version++;
    }

    /** 取消已核销报销增加原借款未还额，原放款、还款、其他预留和独立冻结全部保留。 */
    public void reverseOffset(long expectedVersion, ExpenseUse use, UUID adjustmentId, Instant at) {
        requireVersion(expectedVersion); requireWholeReport(use);
        balance = balance.reverseConsumption(use, adjustmentId, at); version++;
    }

    /** 部分取消恢复原借款抵扣差额，增加未还金额而不解除资金、凭证和还款冻结。 */
    public void reduceOffset(long expectedVersion, ExpenseUse use, Money amount, UUID adjustmentId, Instant at) {
        requireVersion(expectedVersion); requireWholeReport(use);
        balance = balance.reduceConsumption(use, amount, adjustmentId, at); version++;
    }

    /** 逾期是日期和剩余未冲销额派生的提醒事实，不自动拒绝费用审批。 */
    public boolean overdue(LocalDate date) { return !date.isBefore(paidOn) && date.isAfter(dueOn) && outstanding().value().signum() > 0; }

    /** 预留不等于冲销，状态仅反映真正已结算金额。 */
    public Status status() {
        if (paymentReviewRequired) return Status.PAYMENT_REVIEW;
        if (voucherReviewRequired()) return Status.VOUCHER_REVIEW;
        if (repaymentReviewRequired()) return Status.REPAYMENT_REVIEW;
        if (returnedDisbursements().equals(balance.limit())) return Status.RETURNED;
        return outstanding().value().signum() == 0 ? Status.SETTLED
                : balance.consumed().plus(repaid()).plus(returnedDisbursements()).value().signum() == 0 ? Status.PAID_OUT : Status.PARTIALLY_SETTLED;
    }

    /** 退票或资金事实冲突只冻结后续使用，不抹掉真实放款和已经完成的冲销。 */
    public void requirePaymentReview(long expectedVersion) {
        requireVersion(expectedVersion);
        if (!paymentReviewRequired) { paymentReviewRequired = true; version++; }
    }

    /** 财务裁决恢复同一笔原放款；原额度、预留和已核销金额必须完整保留。 */
    public void resolvePaymentReview(long expectedVersion, EmployeeAdvance confirmed) {
        requireVersion(expectedVersion);
        if (!paymentReviewRequired || confirmed == null || !sameDisbursement(confirmed) || !disbursementReturns.isEmpty()) throw paymentReview();
        paymentReviewRequired = false; version++;
    }

    /** 原挂账和付款凭证各自冻结，重复事件不新增版本，也不改变原资金账本。 */
    public void requireVoucherReview(long expectedVersion, UUID voucherId) {
        requireVersion(expectedVersion); Objects.requireNonNull(voucherId);
        if (!voucherReviews.contains(voucherId)) {
            var next = new ArrayList<>(voucherReviews); next.add(voucherId); voucherReviews = List.copyOf(next); version++;
        }
    }

    /** 应用层验证原凭证的有效过账裁决后，只解除该凭证的冻结。 */
    public void resolveVoucherReview(long expectedVersion, UUID voucherId) {
        requireVersion(expectedVersion);
        if (!voucherReviews.contains(voucherId)) throw voucherReview();
        voucherReviews = voucherReviews.stream().filter(id -> !id.equals(voucherId)).toList(); version++;
    }

    /** 原放款真实退回只追加新入款；已有冲销、净还款和预留必须先核清，不能覆盖或变成负数。 */
    public void resolveDisbursementReview(long expectedVersion, AdvanceDisbursementReturn decision) {
        requireVersion(expectedVersion);
        if (!paymentReviewRequired || decision == null || !decision.belongsTo(this)
                || disbursementReturns.stream().anyMatch(entry -> !decision.receipt().returns().contains(entry.proof()))) throw disbursementSourceChanged();
        var next = new ArrayList<>(disbursementReturns);
        for (var entry : decision.entries()) {
            if (disbursementReturns.stream().noneMatch(previous -> previous.proof().equals(entry.proof()))) next.add(entry);
        }
        requireValidDisbursementReturns(next, balance.limit());
        var total = next.stream().map(AdvanceDisbursementReturn.Entry::amount).reduce(Money.zero(balance.limit().currency()), Money::plus);
        if (total.plus(repaid()).compareTo(balance.available()) > 0) throw new DomainException("DISBURSEMENT_RETURN_CONFLICTS_WITH_USAGE", "Disbursement return cannot replace existing offsets, net repayments or reservations");
        disbursementReturns = List.copyOf(next); paymentReviewRequired = false; version++;
    }

    /** 页面可用额度与领域占用规则保持一致，冻结不会改变原余额账本。 */
    public Money available() { return reviewRequired() ? Money.zero(balance.limit().currency()) : balance.available().minus(repaid()).minus(returnedDisbursements()); }

    /** 分别保留报销冲销和实际还款，两者共同决定未还余额。 */
    public Money repaid() { return receivedRepayments().minus(returnedRepayments()); }
    /** 原始收款合计不因后来真实退回而减写。 */
    public Money receivedRepayments() { return repayments.stream().map(AdvanceRepayment.Entry::amount).reduce(Money.zero(balance.limit().currency()), Money::plus); }
    /** 独立退回合计与原收款分别保存，净还款才减少当前未还余额。 */
    public Money returnedRepayments() { return repaymentReturns.stream().map(AdvanceRepaymentResolution.ReturnEntry::amount).reduce(Money.zero(balance.limit().currency()), Money::plus); }
    /** 银行退回原放款与员工主动还款分开，不降低原放款账本上限。 */
    public Money returnedDisbursements() { return disbursementReturns.stream().map(AdvanceDisbursementReturn.Entry::amount).reduce(Money.zero(balance.limit().currency()), Money::plus); }
    /** 已预留金额仍是尚未归还的借款，不因预留提前结清。 */
    public Money outstanding() { return balance.limit().minus(balance.consumed()).minus(repaid()).minus(returnedDisbursements()); }

    /** 完整采纳一笔已收且已入账的还款；不得侵占其他报销的原预留。 */
    public void repay(long expectedVersion, AdvanceRepayment repayment) {
        requireVersion(expectedVersion);
        if (reviewRequired()) throw review();
        if (repayment == null || !repayment.belongsTo(this)) throw new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "Repayment does not belong to this original disbursement");
        if (repayments.stream().anyMatch(item -> item.id().equals(repayment.id()) || item.receiptReference().equals(repayment.receipt().request().receiptReference()))) {
            throw new DomainException("ADVANCE_REPAYMENT_ALREADY_RECORDED", "The repayment receipt is already included in this advance");
        }
        if (repayment.amount().compareTo(available()) > 0) throw insufficient();
        var entries = new ArrayList<>(repayments); entries.add(repayment.entry()); repayments = List.copyOf(entries); version++;
    }

    /** 已确认还款出现撤销或不一致时独立冻结，原放款裁决不能解除这个冻结。 */
    public void requireRepaymentReview(long expectedVersion, UUID repaymentId) {
        requireVersion(expectedVersion);
        if (repayments.stream().noneMatch(entry -> entry.id().equals(repaymentId))) throw repaymentSourceChanged();
        if (!repaymentReviews.contains(repaymentId)) {
            var next = new ArrayList<>(repaymentReviews); next.add(repaymentId); repaymentReviews = List.copyOf(next); version++;
        }
    }
    /** 只解除本笔还款争议；真实退款首次加回欠款，原预留、冲销及其他冻结完整保留。 */
    public void resolveRepaymentReview(long expectedVersion, AdvanceRepaymentResolution resolution) {
        requireVersion(expectedVersion);
        if (resolution == null || !resolution.belongsTo(this) || !repaymentReviews.contains(resolution.receipt().request().repaymentId())) throw repaymentSourceChanged();
        var repaymentId = resolution.receipt().request().repaymentId();
        var existing = repaymentReturns.stream().filter(entry -> entry.repaymentId().equals(repaymentId)).toList();
        if (existing.stream().anyMatch(entry -> !entry.matches(resolution))) throw repaymentSourceChanged();
        var next = new ArrayList<>(repaymentReturns);
        for (var entry : resolution.returnEntries()) {
            if (existing.stream().noneMatch(previous -> previous.proof().equals(entry.proof()))) next.add(entry);
        }
        requireValidReturns(repayments, next); repaymentReturns = List.copyOf(next);
        repaymentReviews = repaymentReviews.stream().filter(id -> !id.equals(repaymentId)).toList(); version++;
    }
    private boolean reviewRequired() { return paymentReviewRequired || voucherReviewRequired() || repaymentReviewRequired(); }
    private DomainException review() { return paymentReviewRequired ? paymentReview() : voucherReviewRequired() ? voucherReview() : new DomainException("ADVANCE_REPAYMENT_REVIEW_REQUIRED", "Repayment evidence requires finance review before further use"); }
    private void requireRepaymentCapacity(ReservedAmount proposed) { if (proposed.available().compareTo(repaid().plus(returnedDisbursements())) < 0) throw insufficient(); }
    private static DomainException insufficient() { return new DomainException("INSUFFICIENT_FINANCIAL_BALANCE", "Repayment, reservations and offsets exceed the original advance"); }

    /** 重复确认同一放款时只比较不可变依据，不能覆盖后续预留或冲销。 */
    public boolean sameDisbursement(EmployeeAdvance other) {
        return id.equals(other.id) && tenantId.equals(other.tenantId) && legalEntityId.equals(other.legalEntityId)
                && employeeId.equals(other.employeeId) && paymentReference.equals(other.paymentReference)
                && paidOn.equals(other.paidOn) && dueOn.equals(other.dueOn) && balance.limit().equals(other.balance.limit());
    }

    private static void requireWholeReport(ExpenseUse use) { if (use == null || use.lineNo() != 0) throw new DomainException("INVALID_ADVANCE_OFFSET", "An advance offset belongs to the whole report"); }
    private void requireVersion(long expectedVersion) { if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Advance version has changed"); }

    /** 恢复同一笔实际放款与额度账本，预留不能改写外部放款引用。 */
    public static EmployeeAdvance restore(State state) {
        if (state.version() < 1 || state.balance() == null) throw new DomainException("INVALID_ADVANCE", "Persisted advance balance is invalid");
        var result = new EmployeeAdvance(state.id(), state.tenantId(), state.legalEntityId(), state.employeeId(), state.balance().limit(),
                state.paymentReference(), state.paidOn(), state.dueOn());
        if (java.util.stream.Stream.concat(state.balance().reservations().stream(), state.balance().consumptions().stream())
                .anyMatch(item -> item.use().lineNo() != 0)) throw new DomainException("INVALID_ADVANCE", "Advance uses must belong to whole reports");
        var repaid = state.repayments() == null ? List.<AdvanceRepayment.Entry>of() : List.copyOf(state.repayments());
        if (repaid.stream().map(AdvanceRepayment.Entry::id).distinct().count() != repaid.size()
                || repaid.stream().map(AdvanceRepayment.Entry::receiptReference).distinct().count() != repaid.size()) throw new DomainException("INVALID_ADVANCE", "Persisted repayments must have unique receipts");
        var returned = state.repaymentReturns() == null ? List.<AdvanceRepaymentResolution.ReturnEntry>of() : List.copyOf(state.repaymentReturns());
        requireValidReturns(repaid, returned);
        // 旧快照只有一个冻结标记，保守地将当时所有还款标为待核对，逐笔裁决后才能恢复。
        var reviews = state.repaymentReviews() == null ? state.repaymentReviewRequired() ? repaid.stream().map(AdvanceRepayment.Entry::id).toList() : List.<UUID>of() : List.copyOf(state.repaymentReviews());
        if (reviews.stream().distinct().count() != reviews.size() || state.repaymentReviewRequired() != !reviews.isEmpty()
                || reviews.stream().anyMatch(id -> repaid.stream().noneMatch(item -> item.id().equals(id)))) throw repaymentSourceChanged();
        var returnedDisbursements = state.disbursementReturns() == null ? List.<AdvanceDisbursementReturn.Entry>of() : List.copyOf(state.disbursementReturns());
        requireValidDisbursementReturns(returnedDisbursements, state.balance().limit());
        var voucherReviews = state.voucherReviews() == null ? List.<UUID>of() : List.copyOf(state.voucherReviews());
        if (voucherReviews.stream().distinct().count() != voucherReviews.size()) throw voucherReview();
        result.balance = state.balance(); result.repayments = repaid; result.repaymentReturns = returned; result.repaymentReviews = reviews;
        result.disbursementReturns = returnedDisbursements; result.voucherReviews = voucherReviews; result.requireRepaymentCapacity(result.balance);
        result.version = state.version(); result.paymentReviewRequired = state.paymentReviewRequired(); return result;
    }

    private static void requireValidReturns(List<AdvanceRepayment.Entry> repayments, List<AdvanceRepaymentResolution.ReturnEntry> returns) {
        if (returns.stream().map(entry -> entry.proof().fundsIdentity()).distinct().count() != returns.size()
                || returns.stream().map(entry -> entry.proof().postingIdentity()).distinct().count() != returns.size()) {
            throw new DomainException("REPAYMENT_RETURN_ALREADY_RECORDED", "Returned funds or accounting entry already belongs to a repayment adjustment");
        }
        if (returns.stream().anyMatch(entry -> repayments.stream().noneMatch(original -> original.id().equals(entry.repaymentId()) && original.amount().currency().equals(entry.amount().currency())))) throw repaymentSourceChanged();
        for (var original : repayments) {
            var total = returns.stream().filter(entry -> entry.repaymentId().equals(original.id())).map(AdvanceRepaymentResolution.ReturnEntry::amount)
                    .reduce(Money.zero(original.amount().currency()), Money::plus);
            if (total.compareTo(original.amount()) > 0) throw repaymentSourceChanged();
        }
    }

    private static void requireValidDisbursementReturns(List<AdvanceDisbursementReturn.Entry> entries, Money original) {
        if (entries.size() > io.agentflow.finance.AdvanceDisbursementReturnPort.MAX_RETURN_ENTRIES
                || entries.stream().anyMatch(entry -> !entry.amount().currency().equals(original.currency()))
                || entries.stream().map(entry -> entry.proof().fundsIdentity()).distinct().count() != entries.size()
                || entries.stream().map(entry -> entry.proof().postingIdentity()).distinct().count() != entries.size()) throw disbursementSourceChanged();
    }

    /** 保存不可变放款背景和可变余额，不复制派生状态字段。 */
    public State state() { return new State(id, tenantId, legalEntityId, employeeId, paymentReference, paidOn, dueOn, balance, version, paymentReviewRequired, repayments, repaymentReviewRequired(), repaymentReviews, repaymentReturns, disbursementReturns, voucherReviews); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID legalEntityId() { return legalEntityId; }
    public String employeeId() { return employeeId; }
    public String paymentReference() { return paymentReference; }
    public LocalDate paidOn() { return paidOn; }
    public LocalDate dueOn() { return dueOn; }
    public ReservedAmount balance() { return balance; }
    public long version() { return version; }
    public boolean paymentReviewRequired() { return paymentReviewRequired; }
    public boolean voucherReviewRequired() { return !voucherReviews.isEmpty(); }
    public List<UUID> voucherReviews() { return voucherReviews; }
    public boolean repaymentReviewRequired() { return !repaymentReviews.isEmpty(); }
    public List<AdvanceRepayment.Entry> repayments() { return repayments; }
    public List<UUID> repaymentReviews() { return repaymentReviews; }
    public List<AdvanceRepaymentResolution.ReturnEntry> repaymentReturns() { return repaymentReturns; }
    public List<AdvanceDisbursementReturn.Entry> disbursementReturns() { return disbursementReturns; }
    private static DomainException disbursementSourceChanged() { return new DomainException("DISBURSEMENT_RETURN_SOURCE_CHANGED", "Disbursement adjustment must preserve the original payment and every recorded return"); }
    private static DomainException repaymentSourceChanged() { return new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "Repayment decision must retain original recorded receipt and adjustment identity"); }
    private static DomainException paymentReview() { return new DomainException("ADVANCE_PAYMENT_REVIEW_REQUIRED", "Disputed advance payment requires finance review before further use"); }
    private static DomainException voucherReview() { return new DomainException("ADVANCE_VOUCHER_REVIEW_REQUIRED", "Original advance voucher requires finance review before further use"); }

    /**
     * 借款持久状态，实际放款金额保留为余额账本的固定上限。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID legalEntityId, String employeeId, String paymentReference,
                         LocalDate paidOn, LocalDate dueOn, ReservedAmount balance, long version, boolean paymentReviewRequired,
                         List<AdvanceRepayment.Entry> repayments, boolean repaymentReviewRequired, List<UUID> repaymentReviews,
                         List<AdvanceRepaymentResolution.ReturnEntry> repaymentReturns, List<AdvanceDisbursementReturn.Entry> disbursementReturns, List<UUID> voucherReviews) {
        /** 兼容旧银行退回快照；冻结补录由有原凭证证据的升级迁移执行。 */
        public State(UUID id, String tenantId, UUID legalEntityId, String employeeId, String paymentReference, LocalDate paidOn, LocalDate dueOn,
                ReservedAmount balance, long version, boolean paymentReviewRequired, List<AdvanceRepayment.Entry> repayments,
                boolean repaymentReviewRequired, List<UUID> repaymentReviews, List<AdvanceRepaymentResolution.ReturnEntry> repaymentReturns,
                List<AdvanceDisbursementReturn.Entry> disbursementReturns) {
            this(id, tenantId, legalEntityId, employeeId, paymentReference, paidOn, dueOn, balance, version, paymentReviewRequired, repayments, repaymentReviewRequired, repaymentReviews, repaymentReturns, disbursementReturns, List.of());
        }
        /** 兼容旧单元调用和旧 JSON，历史没有银行退回时保持原余额。 */
        public State(UUID id, String tenantId, UUID legalEntityId, String employeeId, String paymentReference, LocalDate paidOn, LocalDate dueOn,
                ReservedAmount balance, long version, boolean paymentReviewRequired, List<AdvanceRepayment.Entry> repayments,
                boolean repaymentReviewRequired, List<UUID> repaymentReviews, List<AdvanceRepaymentResolution.ReturnEntry> repaymentReturns) {
            this(id, tenantId, legalEntityId, employeeId, paymentReference, paidOn, dueOn, balance, version, paymentReviewRequired, repayments, repaymentReviewRequired, repaymentReviews, repaymentReturns, List.of());
        }
    }

    /**
     * 状态由已放款和实际冲销事实派生。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PAID_OUT, PARTIALLY_SETTLED, SETTLED, RETURNED, PAYMENT_REVIEW, VOUCHER_REVIEW, REPAYMENT_REVIEW }
}
