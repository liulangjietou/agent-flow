package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 报销聚合拥有费用事实、财务版本、轮次及核减；审批状态仍由申请聚合拥有。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseReport {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final String employeeId;
    private ExpenseContent content;
    private long version;
    private List<ExpenseRound> rounds;

    private ExpenseReport(State state) {
        id = Objects.requireNonNull(state.id()); tenantId = actor(state.tenantId(), 64);
        applicationId = Objects.requireNonNull(state.applicationId()); employeeId = actor(state.employeeId(), 128);
        content = Objects.requireNonNull(state.content()); version = state.version(); rounds = List.copyOf(state.rounds());
        if (version < 1) throw new DomainException("INVALID_EXPENSE_VERSION", "Financial version must be positive");
    }

    /** 应用服务先建立同租户、同申请人的一对一申请绑定。 */
    public static ExpenseReport draft(UUID id, String tenantId, UUID applicationId, String employeeId, ExpenseContent content) {
        return new ExpenseReport(new State(id, tenantId, applicationId, employeeId, content, 1, List.of()));
    }

    /** 从完整持久快照恢复，不能用当前制度替换历史轮次。 */
    public static ExpenseReport restore(State state) { return new ExpenseReport(state); }

    /** 修改待提交内容；应用服务须先由申请聚合核对草稿、退回或撤回状态。 */
    public void revise(long expectedVersion, ExpenseContent revised) {
        requireVersion(expectedVersion);
        content = Objects.requireNonNull(revised); version++;
    }

    /** 提交事实一次性冻结；所有跨聚合占用与申请状态变更由同一短事务编排。 */
    public void freeze(long expectedVersion, int roundNo, String baseCurrency, EmployeeAccountSnapshot account,
                       Map<Integer, ExpenseAssessment> assessments, String submittedBy, Instant at) {
        requireVersion(expectedVersion);
        String actor = actor(submittedBy, 128);
        Money.zero(baseCurrency);
        if (!employeeId.equals(actor) || account == null || !employeeId.equals(account.employeeId())
                || !content.legalEntityId().equals(account.legalEntityId())) {
            throw new DomainException("EXPENSE_ACCOUNT_MISMATCH", "Only the applicant's verified legal-entity account may be used");
        }
        requireTime(at, rounds.isEmpty() ? null : latestEvent(currentRound()));
        if (roundNo != rounds.size() + 1) throw new DomainException("EXPENSE_ROUND_CONFLICT", "Financial rounds must be consecutive");
        if (content.lines().isEmpty() || assessments == null || assessments.size() != content.lines().size()) {
            throw new DomainException("EXPENSE_PRECHECK_REQUIRED", "Every expense line requires current submission facts");
        }
        var original = new ArrayList<ExpenseRound.FrozenLine>();
        var approved = new ArrayList<ExpenseRound.ApprovedLine>();
        for (var line : content.lines()) {
            var fact = assessments.get(line.lineNo());
            var frozenLine = freezeLine(line, fact, baseCurrency);
            original.add(frozenLine);
            approved.add(new ExpenseRound.ApprovedLine(line.lineNo(), frozenLine.claimedBase(), frozenLine.deductibleTaxBase(),
                    CostAllocation.apportion(line.allocations(), frozenLine.claimedBase())));
        }
        var frozen = new ExpenseRound(roundNo, version, actor, at, content, baseCurrency, account,
                original, approved, content.advanceOffsets(), List.of());
        if (frozen.offsetTotal().compareTo(frozen.approvedGross()) > 0) {
            throw new DomainException("ADVANCE_OFFSET_EXCEEDS_EXPENSE", "Advance offsets exceed approved expenses");
        }
        var updated = new ArrayList<>(rounds); updated.add(frozen);
        rounds = List.copyOf(updated); version++;
    }

    /** 只允许减额，先验证全部行再替换整个轮次，失败不能留下半次核减。 */
    public ExpenseAdjustment reduce(long expectedVersion, List<Reduction> reductions, String adjustedBy,
                                     String reasonCode, String comment, Instant at) {
        requireVersion(expectedVersion);
        var round = requireFrozenRound();
        String actor = actor(adjustedBy, 128);
        requireTime(at, latestEvent(round));
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}") || StringUtils.isBlank(comment) || comment.length() > 2000
                || CollectionUtils.isEmpty(reductions) || reductions.size() > ExpenseContent.MAX_LINES) {
            throw new DomainException("INVALID_EXPENSE_ADJUSTMENT", "A bounded reduction list, reason code and comment are required");
        }
        var requested = new HashMap<Integer, Reduction>();
        for (var reduction : reductions) {
            if (reduction == null || requested.put(reduction.lineNo(), reduction) != null) throw invalidReduction();
        }
        var approved = new ArrayList<ExpenseRound.ApprovedLine>();
        var changes = new ArrayList<ExpenseAdjustment.LineChange>();
        for (int index = 0; index < round.approvedLines().size(); index++) {
            var before = round.approvedLines().get(index);
            var reduction = requested.remove(before.lineNo());
            if (reduction == null) { approved.add(before); continue; }
            validateReduction(before, reduction);
            if (before.gross().compareTo(reduction.approvedGross()) != 0 || before.tax().compareTo(reduction.approvedTax()) != 0) {
                changes.add(new ExpenseAdjustment.LineChange(before.lineNo(), before.gross(), reduction.approvedGross(), before.tax(), reduction.approvedTax()));
            }
            approved.add(new ExpenseRound.ApprovedLine(before.lineNo(), reduction.approvedGross(), reduction.approvedTax(),
                    CostAllocation.apportion(round.originalLines().get(index).original().allocations(), reduction.approvedGross())));
        }
        if (!requested.isEmpty() || changes.isEmpty()) throw invalidReduction();
        Money gross = approved.stream().map(ExpenseRound.ApprovedLine::gross).reduce(Money.zero(round.baseCurrency()), Money::plus);
        var offsets = new ArrayList<>(round.advanceOffsets());
        var offsetChanges = new ArrayList<ExpenseAdjustment.OffsetChange>();
        if (round.offsetTotal().compareTo(gross) > 0) {
            Money excess = round.offsetTotal().minus(gross);
            for (int index = offsets.size() - 1; index >= 0 && excess.value().signum() > 0; index--) {
                var before = offsets.get(index); Money released = before.amount().min(excess);
                if (released.value().signum() == 0) continue;
                Money remaining = before.amount().minus(released); excess = excess.minus(released);
                offsets.set(index, new AdvanceOffset(before.advanceId(), remaining));
                offsetChanges.add(new ExpenseAdjustment.OffsetChange(before.advanceId(), before.amount(), remaining));
            }
        }
        var adjustment = new ExpenseAdjustment(UUID.randomUUID(), version, actor, at, reasonCode, comment, changes, offsetChanges);
        var audit = new ArrayList<>(round.adjustments()); audit.add(adjustment);
        var updated = new ArrayList<>(rounds);
        updated.set(updated.size() - 1, new ExpenseRound(round.roundNo(), round.submittedFinancialVersion(), round.submittedBy(), round.submittedAt(),
                round.content(), round.baseCurrency(), round.account(), round.originalLines(), approved, offsets, audit));
        rounds = List.copyOf(updated); version++;
        return adjustment;
    }

    private static ExpenseRound.FrozenLine freezeLine(ExpenseLine line, ExpenseAssessment fact, String baseCurrency) {
        if (fact == null || !baseCurrency.equals(fact.exchangeRate().toCurrency())
                || !line.claimedGross().currency().equals(fact.exchangeRate().fromCurrency())
                || !baseCurrency.equals(fact.deductibleTax().currency())) {
            throw new DomainException("EXPENSE_PRECHECK_REQUIRED", "Submission facts do not match this expense line");
        }
        Money gross = fact.exchangeRate().convert(line.claimedGross());
        if (!fact.policy().assessedGross().equals(gross)
                || fact.deductibleTax().compareTo(fact.exchangeRate().convert(line.claimedTax())) > 0) {
            throw new DomainException("EXPENSE_PRECHECK_REQUIRED", "Submission facts do not match this expense line");
        }
        if (fact.policy().decision() == ExpensePolicySnapshot.Decision.DENIED) {
            throw new DomainException("EXPENSE_POLICY_DENIED", "Expense policy denies this expense line");
        }
        if (fact.policy().decision() == ExpensePolicySnapshot.Decision.REQUIRES_EXCEPTION && StringUtils.isBlank(line.exceptionReason())) {
            throw new DomainException("EXPENSE_EXCEPTION_REASON_REQUIRED", "An over-limit expense requires an explicit reason");
        }
        // 制度与可抵扣额已经按本位币核定，不能再次乘汇率。
        return new ExpenseRound.FrozenLine(line, fact, gross, fact.deductibleTax());
    }

    private static void validateReduction(ExpenseRound.ApprovedLine before, Reduction reduction) {
        if (reduction.approvedGross() == null || reduction.approvedTax() == null
                || reduction.approvedGross().compareTo(before.gross()) > 0 || reduction.approvedTax().compareTo(before.tax()) > 0
                || reduction.approvedTax().compareTo(reduction.approvedGross()) > 0) throw invalidReduction();
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Financial version has changed");
    }

    private static String actor(String value, int maxLength) {
        if (StringUtils.isBlank(value) || value.length() > maxLength) throw new DomainException("INVALID_EXPENSE_ACTOR", "A valid expense actor is required");
        return value;
    }

    private static void requireTime(Instant at, Instant previous) {
        if (at == null || previous != null && at.isBefore(previous)) throw new DomainException("INVALID_EXPENSE_TIME", "Financial event time precedes the previous event");
    }

    private static Instant latestEvent(ExpenseRound round) {
        return round.adjustments().isEmpty() ? round.submittedAt() : round.adjustments().get(round.adjustments().size() - 1).adjustedAt();
    }

    private static DomainException invalidReduction() {
        return new DomainException("INVALID_EXPENSE_REDUCTION", "Each requested expense and tax amount must be a reduction of an existing line");
    }

    /** 读取最近一次提交的财务轮次；草稿没有可审核轮次。 */
    public ExpenseRound currentRound() {
        if (rounds.isEmpty()) throw new DomainException("EXPENSE_NOT_SUBMITTED", "Expense report has no submitted financial round");
        return rounds.get(rounds.size() - 1);
    }

    /** 当前版本必须已经冻结或核减，补正中的旧轮次不能冒充新版本财务事实。 */
    public ExpenseRound requireFrozenRound() {
        var round = currentRound();
        long frozenVersion = round.adjustments().isEmpty() ? round.submittedFinancialVersion() + 1
                : round.adjustments().get(round.adjustments().size() - 1).previousFinancialVersion() + 1;
        if (version != frozenVersion || !content.equals(round.content())) {
            throw new DomainException("EXPENSE_NOT_FROZEN", "Current financial version has not been frozen");
        }
        return round;
    }

    /** 完整持久状态，版本更新由仓储以乐观锁完成。 */
    public State state() { return new State(id, tenantId, applicationId, employeeId, content, version, rounds); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public String employeeId() { return employeeId; }
    public ExpenseContent content() { return content; }
    public long version() { return version; }
    public List<ExpenseRound> rounds() { return rounds; }

    /**
     * 财务提交的本币核定输入；不能携带替换发票、收款人或成本对象。
     * @author owlzhangfq@gmail.com
     */
    public record Reduction(int lineNo, Money approvedGross, Money approvedTax) { }

    /**
     * 聚合持久快照，与审批状态分开保存且拥有独立版本。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, String employeeId, ExpenseContent content,
                        long version, List<ExpenseRound> rounds) {
        /** 恢复边界也拒绝可变的轮次列表。 */
        public State { rounds = List.copyOf(rounds); }
    }
}
