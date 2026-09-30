package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 预算调整拥有独立草稿、不可变提交轮次及批准事实，不修改费用申请或预算实际台账。
 * @author owlzhangfq@gmail.com
 */
public final class BudgetAdjustmentRequest {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final String employeeId;
    private BudgetAdjustmentContent content;
    private List<BudgetAdjustmentRound> rounds = List.of();
    private Approval approval;
    private long version = 1;

    private BudgetAdjustmentRequest(UUID id, String tenantId, UUID applicationId, String employeeId, BudgetAdjustmentContent content) {
        if (id == null || applicationId == null || invalidText(tenantId, 64) || invalidText(employeeId, 128) || content == null) throw invalid();
        this.id = id;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.employeeId = employeeId;
        this.content = content;
    }

    /** 草稿只包含员工意图，任何预算事实均需从外部系统另行读取。 */
    public static BudgetAdjustmentRequest draft(UUID id, String tenantId, UUID applicationId, String employeeId, BudgetAdjustmentContent content) {
        return new BudgetAdjustmentRequest(id, tenantId, applicationId, employeeId, content);
    }

    /** 应用服务负责草稿、退回和撤回状态；实体保护自身版本及已有批准。 */
    public void revise(long expectedVersion, BudgetAdjustmentContent changed) {
        requireMutable(expectedVersion);
        if (changed == null) throw invalid();
        content = changed;
        version++;
    }

    /** 所有领域校验通过后才追加轮次，失败不会留下部分冻结事实。 */
    public void freeze(long expectedVersion, int roundNo, FinanceCatalog catalog, String targetDigest,
                       BudgetLedgerPort.Snapshot ledger, InitiatorContext initiator, Instant now) {
        requireMutable(expectedVersion);
        if (roundNo != rounds.size() + 1 || now == null || !rounds.isEmpty() && now.isBefore(currentRound().submittedAt())) throw invalid();
        if (catalog == null || !employeeId.equals(catalog.employeeId()) || !catalog.validUntil().isAfter(now)) {
            throw new DomainException("BUDGET_CATALOG_CHANGED", "A current finance catalog for the budget adjustment applicant is required");
        }
        if (initiator == null || initiator.appointmentId() == null || !employeeId.equals(initiator.subject())
                || !content.legalEntityId().equals(initiator.legalEntityId())) {
            throw new DomainException("BUDGET_INITIATOR_MISMATCH", "Selected appointment must match the budget adjustment applicant and legal entity");
        }
        var round = new BudgetAdjustmentRound(roundNo, version, employeeId, now, content, catalog.legalEntity(content.legalEntityId()),
                catalog.sourceVersion(), targetDigest, ledger);
        var changed = new ArrayList<>(rounds);
        changed.add(round);
        rounds = List.copyOf(changed);
        version++;
    }

    /** 只有当前未改写的提交轮次可以获批，实际预算执行仍需独立授权及外部确认。 */
    public void approve(long expectedVersion, int roundNo, long applicationVersion, String actor, Instant now) {
        requireMutable(expectedVersion);
        var round = currentRound();
        if (roundNo != round.roundNo() || version != round.submittedRequestVersion() + 1 || !content.equals(round.content())
                || now == null || now.isBefore(round.submittedAt())) throw invalid();
        approval = new Approval(roundNo, applicationVersion, actor, now);
        version++;
    }

    /** 草稿不能伪装为已有审批依据。 */
    public BudgetAdjustmentRound currentRound() {
        if (rounds.isEmpty()) throw new DomainException("BUDGET_ADJUSTMENT_NOT_SUBMITTED", "Budget adjustment has not been submitted");
        return rounds.get(rounds.size() - 1);
    }

    /** 持久化恢复核对轮次、主体与版本连续性，不用当前台账替换历史依据。 */
    public static BudgetAdjustmentRequest restore(State state) {
        var request = draft(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content());
        if (state.version() < 1) throw invalid();
        long previousVersion = 0;
        Instant previousTime = null;
        for (int index = 0; index < state.rounds().size(); index++) {
            var round = state.rounds().get(index);
            if (round.roundNo() != index + 1 || !round.submittedBy().equals(state.employeeId())
                    || round.submittedRequestVersion() <= previousVersion || round.submittedRequestVersion() >= state.version()
                    || previousTime != null && round.submittedAt().isBefore(previousTime)) throw invalid();
            previousVersion = round.submittedRequestVersion();
            previousTime = round.submittedAt();
        }
        request.rounds = state.rounds();
        request.version = state.version();
        request.approval = state.approval();
        if (!request.rounds.isEmpty() && request.version == previousVersion + 1 && !request.content.equals(request.currentRound().content())) throw invalid();
        if (request.approval != null && (request.rounds.isEmpty() || request.approval.roundNo() != request.currentRound().roundNo()
                || request.version != previousVersion + 2 || !request.content.equals(request.currentRound().content())
                || request.approval.approvedAt().isBefore(request.currentRound().submittedAt()))) throw invalid();
        return request;
    }

    private void requireMutable(long expectedVersion) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Budget adjustment request version changed");
        if (approval != null) throw new DomainException("BUDGET_ADJUSTMENT_ALREADY_APPROVED", "Approved budget adjustment terms cannot be changed");
    }

    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_BUDGET_ADJUSTMENT", "Budget adjustment identity, history and approval must remain consistent");
    }

    /** 状态只保存申请和审批事实，不派生实际预算执行成功。 */
    public State state() { return new State(id, tenantId, applicationId, employeeId, content, rounds, approval, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public String employeeId() { return employeeId; }
    public BudgetAdjustmentContent content() { return content; }
    public List<BudgetAdjustmentRound> rounds() { return rounds; }
    public Approval approval() { return approval; }
    public long version() { return version; }

    /**
     * 最终人工批准引用实际申请版本，后续预算办理不能覆盖批准人及时间。
     * @author owlzhangfq@gmail.com
     */
    public record Approval(int roundNo, long applicationVersion, String approvedBy, Instant approvedAt) {
        /** 批准事实必须具有实际轮次及具名人员。 */
        public Approval {
            if (roundNo < 1 || applicationVersion < 1 || invalidText(approvedBy, 128) || approvedAt == null) throw invalid();
        }
    }

    /**
     * 保存全部提交历史，草稿变化不改写原审批快照。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, String employeeId, BudgetAdjustmentContent content,
                        List<BudgetAdjustmentRound> rounds, Approval approval, long version) {
        /** 禁止通过共享可变集合追加或覆盖轮次。 */
        public State { rounds = List.copyOf(rounds); }
    }
}
