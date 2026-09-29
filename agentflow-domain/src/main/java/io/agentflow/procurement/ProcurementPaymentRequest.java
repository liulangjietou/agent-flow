package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 采购付款拥有自己的内容和批准轮次；审批完成不创造银行付款、应付核销或预算消费。
 * @author owlzhangfq@gmail.com
 */
public final class ProcurementPaymentRequest {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final String employeeId;
    private ProcurementPaymentContent content;
    private List<ProcurementPaymentRound> rounds = List.of();
    private Approval approval;
    private long version = 1;

    private ProcurementPaymentRequest(UUID id, String tenantId, UUID applicationId, String employeeId, ProcurementPaymentContent content) {
        if (id == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || content == null) throw invalid();
        this.id = id; this.tenantId = tenantId; this.applicationId = applicationId; this.employeeId = employeeId; this.content = content;
    }

    /** 创建草稿只有申请意图，尚未获得外部应付匹配事实。 */
    public static ProcurementPaymentRequest draft(UUID id, String tenantId, UUID applicationId, String employeeId, ProcurementPaymentContent content) {
        return new ProcurementPaymentRequest(id, tenantId, applicationId, employeeId, content);
    }

    /** 应用服务先核对可编辑审批状态，实体只保护自身版本与批准事实。 */
    public void revise(long expectedVersion, ProcurementPaymentContent changed) {
        requireMutable(expectedVersion); content = Objects.requireNonNull(changed); version++;
    }

    /** 完整验证原应付和发起任职后一次追加轮次，不在领域内调用外部财务系统。 */
    public void freeze(long expectedVersion, int roundNo, FinanceCatalog catalog, String targetDigest,
                       ProcurementPayablePort.Payable payable, InitiatorContext initiator, Instant now) {
        requireMutable(expectedVersion);
        if (roundNo != rounds.size() + 1 || now == null || !rounds.isEmpty() && now.isBefore(currentRound().submittedAt())) throw invalid();
        if (catalog == null || !employeeId.equals(catalog.employeeId()) || !catalog.validUntil().isAfter(now)) {
            throw new DomainException("PROCUREMENT_CATALOG_CHANGED", "A current finance catalog for the procurement applicant is required");
        }
        if (initiator == null || initiator.appointmentId() == null || !employeeId.equals(initiator.subject())
                || !content.legalEntityId().equals(initiator.legalEntityId())) {
            throw new DomainException("PROCUREMENT_INITIATOR_MISMATCH", "Selected appointment must match the procurement applicant and legal entity");
        }
        var round = new ProcurementPaymentRound(roundNo, version, employeeId, now, content, catalog.legalEntity(content.legalEntityId()),
                catalog.sourceVersion(), targetDigest, payable);
        var changed = new ArrayList<>(rounds); changed.add(round); rounds = List.copyOf(changed); version++;
    }

    /** 最终批准固定本轮应付与申请金额，实际支付必须另行授权且复核未付余额。 */
    public void approve(long expectedVersion, int roundNo, long applicationVersion, String actor, Instant now) {
        requireMutable(expectedVersion); var round = currentRound();
        if (roundNo != round.roundNo() || version != round.submittedRequestVersion() + 1 || !content.equals(round.content())
                || now == null || now.isBefore(round.submittedAt())) throw invalid();
        approval = new Approval(roundNo, applicationVersion, actor, now); version++;
    }

    /** 未提交草稿不允许被当作已有批准来源。 */
    public ProcurementPaymentRound currentRound() {
        if (rounds.isEmpty()) throw new DomainException("PROCUREMENT_NOT_SUBMITTED", "Procurement payment has not been submitted");
        return rounds.get(rounds.size() - 1);
    }

    /** 恢复时核对完整连续轮次和批准版本，禁止草稿覆盖原批准内容。 */
    public static ProcurementPaymentRequest restore(State state) {
        var request = draft(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content());
        if (state.version() < 1) throw invalid();
        long previousVersion = 0; Instant previousTime = null;
        for (int index = 0; index < state.rounds().size(); index++) {
            var round = state.rounds().get(index);
            if (round.roundNo() != index + 1 || !round.submittedBy().equals(state.employeeId()) || round.submittedRequestVersion() <= previousVersion
                    || round.submittedRequestVersion() >= state.version() || previousTime != null && round.submittedAt().isBefore(previousTime)) throw invalid();
            previousVersion = round.submittedRequestVersion(); previousTime = round.submittedAt();
        }
        request.rounds = state.rounds(); request.version = state.version(); request.approval = state.approval();
        if (!request.rounds.isEmpty() && request.version == previousVersion + 1 && !request.content.equals(request.currentRound().content())) throw invalid();
        if (request.approval != null && (request.rounds.isEmpty() || request.approval.roundNo() != request.currentRound().roundNo()
                || request.version != previousVersion + 2 || !request.content.equals(request.currentRound().content())
                || request.approval.approvedAt().isBefore(request.currentRound().submittedAt()))) throw invalid();
        return request;
    }

    private void requireMutable(long expectedVersion) {
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Procurement payment request version changed");
        if (approval != null) throw new DomainException("PROCUREMENT_ALREADY_APPROVED", "Approved procurement payment terms cannot be changed");
    }
    private static DomainException invalid() { return new DomainException("INVALID_PROCUREMENT_PAYMENT", "Procurement payment identity, history and approval must remain consistent"); }

    /** 持久化只记录本申请事实，不以审批状态推导付款成功。 */
    public State state() { return new State(id, tenantId, applicationId, employeeId, content, rounds, approval, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public String employeeId() { return employeeId; }
    public ProcurementPaymentContent content() { return content; }
    public List<ProcurementPaymentRound> rounds() { return rounds; }
    public Approval approval() { return approval; }
    public long version() { return version; }

    /**
     * 审批版本与批准人固定，后续财务授权不覆盖此记录。
     * @author owlzhangfq@gmail.com
     */
    public record Approval(int roundNo, long applicationVersion, String approvedBy, Instant approvedAt) {
        /** 批准必须来自实际轮次和具名处理人。 */
        public Approval {
            if (roundNo < 1 || applicationVersion < 1 || StringUtils.isBlank(approvedBy) || approvedBy.length() > 128 || approvedAt == null) throw invalid();
        }
    }

    /**
     * 全部轮次保留不可变副本，后续草稿不能覆盖旧匹配证据。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, String employeeId, ProcurementPaymentContent content,
                        List<ProcurementPaymentRound> rounds, Approval approval, long version) {
        /** 外部不得通过共享集合修改领域历史。 */
        public State { rounds = List.copyOf(rounds); }
    }
}
