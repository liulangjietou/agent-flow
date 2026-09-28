package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import org.apache.commons.lang3.StringUtils;
import java.time.LocalDate;
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
    private long version = 1;

    /** 放款适配器确认成功后创建；外部放款引用由仓储按租户唯一约束。 */
    public EmployeeAdvance(UUID id, String tenantId, UUID legalEntityId, String employeeId, Money paidAmount,
                            String paymentReference, LocalDate paidOn, LocalDate dueOn) {
        this.id = Objects.requireNonNull(id); this.legalEntityId = Objects.requireNonNull(legalEntityId);
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                || StringUtils.isBlank(paymentReference) || paymentReference.length() > 128 || paidAmount == null || paidAmount.value().signum() <= 0
                || paidOn == null || dueOn == null || dueOn.isBefore(paidOn)) throw new DomainException("INVALID_ADVANCE", "Verified advance payment identity, amount and dates are required");
        this.tenantId = tenantId; this.employeeId = employeeId; this.paymentReference = paymentReference;
        this.paidOn = paidOn; this.dueOn = dueOn; balance = ReservedAmount.available(paidAmount);
    }

    /** 整单冲销按本位币预留，在额度账本中原子替换自己的原预留。 */
    public void reserve(long expectedVersion, ExpenseUse use, Money amount) {
        requireVersion(expectedVersion); requireWholeReport(use);
        var changed = balance.reserve(use, amount); balance = changed; version++;
    }

    /** 退回重提迁移已有预留，旧轮次不再具有可消费余额。 */
    public void move(long expectedVersion, ExpenseUse previous, ExpenseUse next, Money amount) {
        requireVersion(expectedVersion); requireWholeReport(previous); requireWholeReport(next);
        var changed = balance.move(previous, next, amount); balance = changed; version++;
    }

    /** 付款成功或零应付结算时才消耗预留。 */
    public void settle(long expectedVersion, ExpenseUse use) {
        requireVersion(expectedVersion); requireWholeReport(use);
        var changed = balance.consume(use); balance = changed; version++;
    }

    /** 逾期是日期和剩余未冲销额派生的提醒事实，不自动拒绝费用审批。 */
    public boolean overdue(LocalDate date) { return date.isAfter(dueOn) && balance.consumed().compareTo(balance.limit()) < 0; }

    /** 预留不等于冲销，状态仅反映真正已结算金额。 */
    public Status status() {
        return balance.consumed().value().signum() == 0 ? Status.PAID_OUT
                : balance.consumed().compareTo(balance.limit()) == 0 ? Status.SETTLED : Status.PARTIALLY_SETTLED;
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
        result.balance = state.balance(); result.version = state.version(); return result;
    }

    /** 保存不可变放款背景和可变余额，不复制派生状态字段。 */
    public State state() { return new State(id, tenantId, legalEntityId, employeeId, paymentReference, paidOn, dueOn, balance, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID legalEntityId() { return legalEntityId; }
    public String employeeId() { return employeeId; }
    public String paymentReference() { return paymentReference; }
    public LocalDate paidOn() { return paidOn; }
    public LocalDate dueOn() { return dueOn; }
    public ReservedAmount balance() { return balance; }
    public long version() { return version; }

    /**
     * 借款持久状态，实际放款金额保留为余额账本的固定上限。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID legalEntityId, String employeeId, String paymentReference,
                         LocalDate paidOn, LocalDate dueOn, ReservedAmount balance, long version) { }

    /**
     * 状态由已放款和实际冲销事实派生。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { PAID_OUT, PARTIALLY_SETTLED, SETTLED }
}
