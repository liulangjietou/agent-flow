package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.organization.InitiatorContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 预算调整预检固定原草稿、轮次、任职和目的地；读取完成仍须正式提交，不直接修改额度。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentCheck(Input input, long version, Status status, Instant createdAt, Instant startedAt,
                                      Instant leaseUntil, Instant completedAt, Result result) {
    /** 排队、领取与完成连续三版，迟到的事实不能重开已完成检查。 */
    public BudgetAdjustmentCheck {
        Objects.requireNonNull(input); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (version < 1 || version > 3 || startedAt != null && startedAt.isBefore(createdAt)
                || status == Status.QUEUED && (version != 1 || startedAt != null || leaseUntil != null)
                || status != Status.QUEUED && (startedAt == null || leaseUntil == null || !leaseUntil.isAfter(startedAt))
                || status == Status.RUNNING && version != 2 || active && (completedAt != null || result != null)
                || !active && (version != 3 || completedAt == null || completedAt.isBefore(startedAt) || result == null || status != result.status())) throw invalid();
        if (status == Status.READY) {
            var evidence = result.evidence(); var preview = evidence.preview();
            if (!evidence.validUntil().isAfter(completedAt) || !input.employeeId().equals(preview.submittedBy())
                    || preview.roundNo() != input.roundNo() || preview.submittedRequestVersion() != input.requestVersion()
                    || !preview.content().equals(input.content()) || !preview.targetDigest().equals(input.targetDigest())
                    || !preview.legalEntity().id().equals(input.initiator().legalEntityId())
                    || preview.submittedAt().isBefore(startedAt) || preview.submittedAt().isAfter(completedAt)) throw invalid();
        }
    }

    /** 登记只读检查，不产生预算额度变更。 */
    public static BudgetAdjustmentCheck queue(Input input, Instant now) { return new BudgetAdjustmentCheck(input, 1, Status.QUEUED, now, null, null, null, null); }
    /** 固定领取租约；恢复时不能自动延长或重复执行旧领取。 */
    public BudgetAdjustmentCheck start(Instant now, Instant until) {
        if (status != Status.QUEUED) throw conflict();
        return new BudgetAdjustmentCheck(input, 2, Status.RUNNING, createdAt, now, until, null, null);
    }
    /** 到期的成功也只保存超时，旧请求结果不能恢复成新的可提交依据。 */
    public BudgetAdjustmentCheck finish(Result result, Instant now) {
        if (status != Status.RUNNING) throw conflict();
        var value = expired(now) ? Result.unavailable("TIMEOUT") : Objects.requireNonNull(result);
        return new BudgetAdjustmentCheck(input, 3, value.status(), createdAt, startedAt, leaseUntil, now, value);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }

    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_CHECK", "Budget adjustment check must preserve the exact request, source and evidence lifetime"); }
    private static DomainException conflict() { return new DomainException("BUDGET_ADJUSTMENT_CHECK_STATE_CONFLICT", "Budget adjustment check is no longer executable"); }

    /**
     * 原始内容随双版本保存，不能将同一员工另一项预算的检查结果嫁接进来。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID requestId, UUID applicationId, String employeeId, long applicationVersion,
                        long requestVersion, int roundNo, long attempt, InitiatorContext initiator, String targetDigest, BudgetAdjustmentContent content) {
        /** 固定的法人和员工必须与本次任职相同。 */
        public Input {
            if (id == null || requestId == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || applicationVersion < 1 || requestVersion < 1 || roundNo < 1 || attempt < 1
                    || initiator == null || initiator.appointmentId() == null || !employeeId.equals(initiator.subject()) || content == null
                    || !content.legalEntityId().equals(initiator.legalEntityId()) || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
        }
    }

    /**
     * 外部不可用与已确认业务阻断分别保留。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, UNAVAILABLE }

    /**
     * 只保留一份原预算快照，目录和台账时效共同限制候选提交期限。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(FinanceCatalog catalog, BudgetAdjustmentRound preview, Instant validUntil) {
        /** 有效期不能超过任一原事实，预览与目录必须属于同一人和法人版本。 */
        public Evidence {
            if (catalog == null || preview == null || validUntil == null || !validUntil.isAfter(preview.submittedAt())
                    || validUntil.isAfter(catalog.validUntil()) || validUntil.isAfter(preview.ledger().validUntil())
                    || validUntil.isAfter(preview.ledger().observedAt().plus(BudgetLedgerPort.MAX_EVIDENCE_AGE))
                    || !catalog.employeeId().equals(preview.submittedBy()) || !catalog.sourceVersion().equals(preview.catalogVersion())
                    || !catalog.legalEntity(preview.legalEntity().id()).equals(preview.legalEntity())) throw invalid();
        }
    }

    /**
     * 持久结果只保存受控分类，不复制远端错误文本。
     * @author owlzhangfq@gmail.com
     */
    public record Result(Status status, Evidence evidence, String code) {
        /** 成功依据和失败分类互斥。 */
        public Result {
            if (status == null || status == Status.QUEUED || status == Status.RUNNING
                    || status == Status.READY && (evidence == null || code != null)
                    || status != Status.READY && (evidence != null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))) throw invalid();
        }
        /** 候选通过仍需正式提交复核。 */
        public static Result ready(Evidence evidence) { return new Result(Status.READY, evidence, null); }
        /** 依赖不可用不能当作企业业务拒绝。 */
        public static Result unavailable(String code) { return new Result(Status.UNAVAILABLE, null, code); }
        /** 未满足原预算条件不形成可提交候选。 */
        public static Result blocked(String code) { return new Result(Status.BLOCKED, null, code); }
    }
}
