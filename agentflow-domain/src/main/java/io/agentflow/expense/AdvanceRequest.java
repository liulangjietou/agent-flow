package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.PaymentCommand;
import io.agentflow.finance.PaymentOperation;
import io.agentflow.organization.InitiatorContext;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 借款申请只记录待审约定和批准依据；实际到账后才由结算生成 EmployeeAdvance。
 * @author owlzhangfq@gmail.com
 */
public final class AdvanceRequest {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final String employeeId;
    private AdvanceRequestContent content;
    private List<AdvanceRequestRound> rounds = List.of();
    private Approval approval;
    private long version = 1;

    private AdvanceRequest(UUID id, String tenantId, UUID applicationId, String employeeId, AdvanceRequestContent content) {
        if (id == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || content == null) throw invalid();
        this.id = id; this.tenantId = tenantId; this.applicationId = applicationId; this.employeeId = employeeId; this.content = content;
    }

    /** 草稿没有批准或付款事实。 */
    public static AdvanceRequest draft(UUID id, String tenantId, UUID applicationId, String employeeId, AdvanceRequestContent content) {
        return new AdvanceRequest(id, tenantId, applicationId, employeeId, content);
    }

    /** 可编辑审批状态由应用服务核对，本聚合保护版本及已批准约定。 */
    public void revise(long expectedVersion, AdvanceRequestContent changed) {
        requireMutable(expectedVersion); content = Objects.requireNonNull(changed); version++;
    }

    /** 预检与正式提交共用冻结校验；全部通过后才追加历史。 */
    public void freeze(long expectedVersion, int roundNo, FinanceCatalog catalog, EmployeeAccountPort.Account account,
                       InitiatorContext initiator, Instant now) {
        requireMutable(expectedVersion);
        if (roundNo != rounds.size() + 1 || now == null || !rounds.isEmpty() && now.isBefore(currentRound().submittedAt())) throw invalid();
        if (catalog == null || !employeeId.equals(catalog.employeeId()) || !catalog.validUntil().isAfter(now)) {
            throw new DomainException("ADVANCE_CATALOG_CHANGED", "A current finance catalog for the applicant is required");
        }
        if (account == null || !account.validUntil().isAfter(now)) {
            throw new DomainException("ADVANCE_ACCOUNT_EXPIRED", "A current employee account is required");
        }
        if (initiator == null || initiator.appointmentId() == null || !employeeId.equals(initiator.subject())
                || !content.legalEntityId().equals(initiator.legalEntityId())) {
            throw new DomainException("ADVANCE_INITIATOR_MISMATCH", "Selected appointment must match the advance applicant and legal entity");
        }
        var round = new AdvanceRequestRound(roundNo, version, employeeId, now, content, catalog.legalEntity(content.legalEntityId()),
                catalog.sourceVersion(), account.snapshot());
        var changed = new ArrayList<>(rounds); changed.add(round); rounds = List.copyOf(changed); version++;
    }

    /** 只在实际申请最终批准的事务内调用，不生成资金余额或支付成功记录。 */
    public void approve(long expectedVersion, int roundNo, long applicationVersion, String actor, Instant now) {
        requireMutable(expectedVersion); var round = currentRound();
        if (roundNo != round.roundNo() || version != round.submittedRequestVersion() + 1 || !content.equals(round.content())
                || now == null || now.isBefore(round.submittedAt())) throw invalid();
        approval = new Approval(roundNo, applicationVersion, actor, now); version++;
    }

    /** 历史读取必须使用原轮次，不能由当前草稿倒推。 */
    public AdvanceRequestRound currentRound() {
        if (rounds.isEmpty()) throw new DomainException("ADVANCE_NOT_SUBMITTED", "Advance request has not been submitted");
        return rounds.get(rounds.size() - 1);
    }

    /** 跨聚合服务核实账户授权证据后，实体按原批准和无矛盾回执生成固定借款身份。 */
    public EmployeeAdvance paidAdvance(PaymentOperation payment, EmployeeAccountSnapshot authorizedAccount) {
        var command = payment.input().command(); var binding = command.binding();
        if (!payment.settleable() || command.purpose() != PaymentCommand.Purpose.EMPLOYEE_ADVANCE || approval == null
                || !tenantId.equals(command.tenantId()) || !id.equals(binding.businessId()) || !applicationId.equals(binding.applicationId())
                || binding.roundNo() != approval.roundNo() || binding.applicationVersion() != approval.applicationVersion()
                || binding.businessVersion() != version || !employeeId.equals(command.payee().employeeId())
                || !command.payee().equals(authorizedAccount) || !currentRound().legalEntity().id().equals(command.payee().legalEntityId())
                || !currentRound().content().amount().equals(command.amount())
                || command.authorization().authorizedAt().isBefore(approval.approvedAt())) {
            throw new DomainException("ADVANCE_PAYMENT_MISMATCH", "Successful payment must match original approved advance terms");
        }
        var receipt = payment.observation();
        var paidOn = LocalDate.ofInstant(receipt.completedAt(), ZoneId.of(currentRound().legalEntity().timeZone()));
        return new EmployeeAdvance(id, tenantId, content.legalEntityId(), employeeId, receipt.paidAmount(), receipt.paymentReference(), paidOn, content.dueOn());
    }

    /** 完整恢复时核对连续轮次、身份、内容与批准版本，损坏的事实不能进入结算。 */
    public static AdvanceRequest restore(State state) {
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
        if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Advance request version changed");
        if (approval != null) throw new DomainException("ADVANCE_ALREADY_APPROVED", "Approved advance terms cannot be changed");
    }
    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REQUEST", "Advance request identity, version or approved round is inconsistent"); }

    /** 持久化申请依据，不保存派生余额。 */
    public State state() { return new State(id, tenantId, applicationId, employeeId, content, rounds, approval, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public String employeeId() { return employeeId; }
    public AdvanceRequestContent content() { return content; }
    public List<AdvanceRequestRound> rounds() { return rounds; }
    public Approval approval() { return approval; }
    public long version() { return version; }

    /**
     * 最终批准的应用版本和实际处理人，后续支付还需独立授权。
     * @author owlzhangfq@gmail.com
     */
    public record Approval(int roundNo, long applicationVersion, String approvedBy, Instant approvedAt) {
        /** 批准不能省略来源。 */
        public Approval {
            if (roundNo < 1 || applicationVersion < 1 || StringUtils.isBlank(approvedBy) || approvedBy.length() > 128 || approvedAt == null) throw invalid();
        }
    }

    /**
     * 保留全部提交历史，不共享可变集合。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, String employeeId, AdvanceRequestContent content,
                        List<AdvanceRequestRound> rounds, Approval approval, long version) {
        /** 轮次只能由领域行为追加。 */
        public State { rounds = List.copyOf(rounds); }
    }
}
