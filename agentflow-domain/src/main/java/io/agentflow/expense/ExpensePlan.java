package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 独立事前申请的计划聚合；审批状态属于 Application，已授权可核销余额属于 ExpenseRequest。
 * @author owlzhangfq@gmail.com
 */
public final class ExpensePlan {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final String employeeId;
    private ExpensePlanContent content;
    private List<ExpensePlanRound> rounds = List.of();
    private long version = 1;

    private ExpensePlan(UUID id, String tenantId, UUID applicationId, String employeeId, ExpensePlanContent content) {
        if (id == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || content == null) throw invalid();
        this.id = id; this.tenantId = tenantId; this.applicationId = applicationId; this.employeeId = employeeId; this.content = content;
    }

    /** 创建只含计划内容的草稿，不生成任何可核销额度。 */
    public static ExpensePlan draft(UUID id, String tenantId, UUID applicationId, String employeeId, ExpensePlanContent content) {
        return new ExpensePlan(id, tenantId, applicationId, employeeId, content);
    }

    /** 申请可编辑状态由跨聚合编排确认，版本和历史隔离由本聚合负责。 */
    public void revise(long expectedVersion, ExpensePlanContent changed) {
        requireVersion(expectedVersion); content = Objects.requireNonNull(changed); version++;
    }

    /** 全部主数据与汇率校验通过后一次性追加轮次，失败不改变当前计划。 */
    public void freeze(long expectedVersion, int roundNo, FinanceCatalog catalog, Map<String, ExpenseExchangeRate> rates,
                       InitiatorContext initiator, Instant now) {
        requireVersion(expectedVersion);
        if (roundNo != rounds.size() + 1 || now == null || !rounds.isEmpty() && now.isBefore(rounds.get(rounds.size() - 1).submittedAt())) throw invalid();
        if (CollectionUtils.isEmpty(content.lines())) throw new DomainException("EXPENSE_PLAN_LINES_REQUIRED", "At least one planned expense line is required");
        if (catalog == null || !employeeId.equals(catalog.employeeId()) || !catalog.validUntil().isAfter(now)) {
            throw new DomainException("EXPENSE_PLAN_CATALOG_CHANGED", "A current catalog for the applicant is required");
        }
        if (initiator == null || !employeeId.equals(initiator.subject()) || !content.legalEntityId().equals(initiator.legalEntityId())) {
            throw new DomainException("EXPENSE_PLAN_INITIATOR_MISMATCH", "The selected appointment must belong to the applicant and planned legal entity");
        }
        var entity = catalog.legalEntity(content.legalEntityId()); var frozen = new ArrayList<ExpensePlanRound.FrozenLine>();
        for (var line : content.lines()) {
            requireCatalogLine(catalog, line);
            var rate = rates == null ? null : rates.get(line.amount().currency());
            if (rate == null || !entity.baseCurrency().equals(rate.toCurrency())) throw new DomainException("EXPENSE_PLAN_RATE_REQUIRED", "Each planned currency requires a rate to the legal entity base currency");
            var amount = rate.convert(line.amount());
            frozen.add(new ExpensePlanRound.FrozenLine(line, rate, amount, CostAllocation.apportion(line.allocations(), amount)));
        }
        var round = new ExpensePlanRound(roundNo, version, employeeId, now, content, entity, catalog.sourceVersion(), frozen);
        var changed = new ArrayList<>(rounds); changed.add(round); rounds = List.copyOf(changed); version++;
    }

    /** 实际审批完成编排调用；仓储还须核对本人申请已经批准，不能从草稿内容生成额度。 */
    public ExpenseRequest approvedRequest(int approvedRound) {
        var round = currentRound();
        if (round.roundNo() != approvedRound || version != round.submittedPlanVersion() + 1 || !content.equals(round.content())) throw invalid();
        String source = "APPROVAL:" + applicationId + ":" + approvedRound;
        var approved = round.lines().stream().map(line -> new ExpenseRequest.ApprovedLine(line.original().lineNo(), line.amount(), BigDecimal.ZERO, source)).toList();
        return new ExpenseRequest(id, tenantId, applicationId, round.legalEntity().id(), employeeId, approved);
    }

    /** 当前冻结轮次用于审批读模型，不能根据最新草稿重建历史。 */
    public ExpensePlanRound currentRound() {
        if (rounds.isEmpty()) throw new DomainException("EXPENSE_PLAN_NOT_SUBMITTED", "Expense plan has not been submitted");
        return rounds.get(rounds.size() - 1);
    }

    private void requireCatalogLine(FinanceCatalog catalog, ExpensePlanContent.Line line) {
        if (catalog.categories().stream().noneMatch(value -> value.code().equals(line.categoryCode()))
                || catalog.cities().stream().noneMatch(value -> value.code().equals(line.cityCode()))) {
            throw new DomainException("EXPENSE_PLAN_CATEGORY_UNAVAILABLE", "Planned category or city is not available");
        }
        for (var allocation : line.allocations()) {
            if (catalog.costCenters().stream().noneMatch(value -> value.legalEntityId().equals(content.legalEntityId()) && value.code().equals(allocation.costCenter()))
                    || allocation.projectCode() != null && catalog.projects().stream().noneMatch(value -> value.legalEntityId().equals(content.legalEntityId()) && value.code().equals(allocation.projectCode()))) {
                throw new DomainException("COST_OBJECT_UNAVAILABLE", "Planned cost objects are not available to the applicant");
            }
        }
    }
    private void requireVersion(long expectedVersion) { if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Expense plan version changed"); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PLAN", "Expense plan identity or frozen round is invalid"); }

    /** 恢复时核对连续轮次、递增财务版本与原申请人，不能混入另一份授权历史。 */
    public static ExpensePlan restore(State state) {
        var plan = draft(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content());
        if (state.version() < 1) throw invalid();
        long previousVersion = 0; Instant previousTime = null;
        for (int index = 0; index < state.rounds().size(); index++) {
            var round = state.rounds().get(index);
            if (round.roundNo() != index + 1 || !round.submittedBy().equals(state.employeeId()) || round.submittedPlanVersion() <= previousVersion
                    || round.submittedPlanVersion() >= state.version() || previousTime != null && round.submittedAt().isBefore(previousTime)) throw invalid();
            previousVersion = round.submittedPlanVersion(); previousTime = round.submittedAt();
        }
        plan.rounds = state.rounds(); plan.version = state.version();
        if (!plan.rounds.isEmpty() && plan.version == previousVersion + 1 && !plan.content.equals(plan.currentRound().content())) throw invalid();
        return plan;
    }

    /** 持久化完整计划与历史，不复制派生的可用余额。 */
    public State state() { return new State(id, tenantId, applicationId, employeeId, content, rounds, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public String employeeId() { return employeeId; }
    public ExpensePlanContent content() { return content; }
    public List<ExpensePlanRound> rounds() { return rounds; }
    public long version() { return version; }

    /**
     * 不可变的持久化快照，不与调用方共享历史集合。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, String employeeId, ExpensePlanContent content,
                        List<ExpensePlanRound> rounds, long version) {
        /** 历史集合必须完整保留。 */
        public State { rounds = List.copyOf(rounds); }
    }
}
