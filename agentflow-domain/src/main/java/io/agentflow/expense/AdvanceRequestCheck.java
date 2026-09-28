package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.EmployeeAccountPort;
import io.agentflow.organization.InitiatorContext;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 借款申请预检的持久状态；目录和本人账户是待提交事实，不是审批结论。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRequestCheck(Input input, long version, Status status, Instant createdAt, Instant startedAt,
                               Instant leaseUntil, Instant completedAt, Result result) {
    /** 排队、领取和完成各一个版本，不能将迟到结果恢复为成功。 */
    public AdvanceRequestCheck {
        Objects.requireNonNull(input); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (version < 1 || version > 3 || startedAt != null && startedAt.isBefore(createdAt)
                || status == Status.QUEUED && (version != 1 || startedAt != null || leaseUntil != null)
                || status != Status.QUEUED && (startedAt == null || leaseUntil == null || !leaseUntil.isAfter(startedAt))
                || status == Status.RUNNING && version != 2 || active && (completedAt != null || result != null)
                || !active && (version != 3 || completedAt == null || completedAt.isBefore(startedAt) || result == null || status != result.status())) throw invalid();
        if (status == Status.READY) {
            var evidence = result.evidence(); var preview = evidence.preview();
            if (!evidence.validUntil().isAfter(completedAt) || !input.employeeId().equals(evidence.catalog().employeeId())
                    || !input.employeeId().equals(preview.submittedBy()) || preview.roundNo() != input.roundNo()
                    || preview.submittedRequestVersion() != input.requestVersion() || !preview.legalEntity().id().equals(input.initiator().legalEntityId())) throw invalid();
        }
    }

    /** 入队只登记输入，不产生财务余额。 */
    public static AdvanceRequestCheck queue(Input input, Instant now) { return new AdvanceRequestCheck(input, 1, Status.QUEUED, now, null, null, null, null); }
    /** 固定单次租约，不自动重复外发已领取任务。 */
    public AdvanceRequestCheck start(Instant now, Instant until) {
        if (status != Status.QUEUED) throw conflict();
        return new AdvanceRequestCheck(input, 2, Status.RUNNING, createdAt, now, until, null, null);
    }
    /** 即使外部返回成功，过期租约也只能记录超时。 */
    public AdvanceRequestCheck finish(Result result, Instant now) {
        if (status != Status.RUNNING) throw conflict();
        var value = expired(now) ? Result.unavailable("TIMEOUT") : Objects.requireNonNull(result);
        return new AdvanceRequestCheck(input, 3, value.status(), createdAt, startedAt, leaseUntil, now, value);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }

    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REQUEST_CHECK", "Advance request check state and facts must match"); }
    private static DomainException conflict() { return new DomainException("ADVANCE_REQUEST_CHECK_STATE_CONFLICT", "Advance request check is no longer executable"); }

    /**
     * 固定双版本、审批轮次、任职和外发目标，不接收客户端财务结论。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID requestId, UUID applicationId, String employeeId,
                        long applicationVersion, long requestVersion, int roundNo, long attempt, InitiatorContext initiator, String targetDigest) {
        /** 预检身份必须与被冻结的任职相同。 */
        public Input {
            if (id == null || requestId == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || applicationVersion < 1 || requestVersion < 1
                    || roundNo < 1 || attempt < 1 || initiator == null || initiator.appointmentId() == null || initiator.legalEntityId() == null
                    || !employeeId.equals(initiator.subject()) || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
        }
    }

    /**
     * 成功候选、业务阻断与依赖不可用分别保留。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, UNAVAILABLE }

    /**
     * 查询结果与候选轮次一起保存，正式提交会重新校验目录时效和法人本地日期。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(FinanceCatalog catalog, EmployeeAccountPort.Account account, AdvanceRequestRound preview, Instant validUntil) {
        /** 来源和预览必须一致，防止恢复出与批准金额不同的事实。 */
        public Evidence {
            if (catalog == null || account == null || preview == null || validUntil == null || validUntil.isAfter(catalog.validUntil())
                    || validUntil.isAfter(account.validUntil()) || !account.snapshot().equals(preview.account())
                    || !validUntil.isAfter(preview.submittedAt()) || !catalog.employeeId().equals(preview.submittedBy())
                    || !catalog.sourceVersion().equals(preview.catalogVersion()) || !catalog.legalEntity(preview.legalEntity().id()).equals(preview.legalEntity())) throw invalid();
        }
    }

    /**
     * 只存受控错误码，不持久化远端响应正文或凭据。
     * @author owlzhangfq@gmail.com
     */
    public record Result(Status status, Evidence evidence, String code) {
        /** 成功事实和错误分类互斥。 */
        public Result {
            if (status == null || status == Status.QUEUED || status == Status.RUNNING
                    || status == Status.READY && (evidence == null || code != null)
                    || status != Status.READY && (evidence != null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))) throw invalid();
        }
        /** 可用事实仍需正式提交时重验。 */
        public static Result ready(Evidence evidence) { return new Result(Status.READY, evidence, null); }
        /** 外部依赖未知不代表业务拒绝。 */
        public static Result unavailable(String code) { return new Result(Status.UNAVAILABLE, null, code); }
        /** 已确认的业务不满足不能形成提交候选。 */
        public static Result blocked(String code) { return new Result(Status.BLOCKED, null, code); }
    }
}
