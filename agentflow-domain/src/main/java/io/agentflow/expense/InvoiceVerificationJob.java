package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次明确授权的验票任务；超时成为终态，重新查验必须建立新任务。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceVerificationJob(Input input, long version, Status status, Instant createdAt, Instant startedAt,
        Instant leaseUntil, Instant completedAt, Long resultingInvoiceVersion, Rejection rejection, Failure failure) {
    /** 恢复和新建使用相同状态约束，失败原因不能被空成功替代。 */
    public InvoiceVerificationJob {
        Objects.requireNonNull(input); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (version < 1 || version > 3 || startedAt != null && startedAt.isBefore(createdAt)
                || status == Status.QUEUED && (version != 1 || startedAt != null || leaseUntil != null)
                || status != Status.QUEUED && (startedAt == null || leaseUntil == null || !leaseUntil.isAfter(startedAt))
                || status == Status.RUNNING && version != 2
                || active && (completedAt != null || resultingInvoiceVersion != null || rejection != null || failure != null)
                || !active && (version != 3 || completedAt == null || completedAt.isBefore(startedAt))
                || status == Status.SUCCEEDED && (resultingInvoiceVersion == null || rejection != null || failure != null)
                || status == Status.REJECTED && (rejection == null || failure != null
                    || (rejection == Rejection.LEGAL_ENTITY_UNAVAILABLE) != (resultingInvoiceVersion == null))
                || status == Status.UNAVAILABLE && (failure == null || resultingInvoiceVersion != null || rejection != null)
                || resultingInvoiceVersion != null && resultingInvoiceVersion != input.invoiceVersion() + 1) throw invalid();
    }

    /** 初始输入永不更新，排队不改变发票原有结论。 */
    public static InvoiceVerificationJob queue(Input input, Instant now) {
        return new InvoiceVerificationJob(input, 1, Status.QUEUED, now, null, null, null, null, null, null);
    }

    /** 只有排队任务可领取；持久租约覆盖网络等待和本地结果提交。 */
    public InvoiceVerificationJob start(Instant now, Instant until) {
        if (status != Status.QUEUED) throw conflict();
        return new InvoiceVerificationJob(input, version + 1, Status.RUNNING, createdAt, now, until, null, null, null, null);
    }

    /** 已核对原件和票面的结果必须绑定同事务写入的发票新版本。 */
    public InvoiceVerificationJob succeed(long invoiceVersion, Instant now) {
        requireLive(now);
        return new InvoiceVerificationJob(input, version + 1, Status.SUCCEEDED, createdAt, startedAt, leaseUntil, now, invoiceVersion, null, null);
    }

    /** 法人不可用只拒绝本次请求，其余可信票面拒绝绑定失败发票版本。 */
    public InvoiceVerificationJob reject(Rejection reason, Long invoiceVersion, Instant now) {
        requireLive(now);
        return new InvoiceVerificationJob(input, version + 1, Status.REJECTED, createdAt, startedAt, leaseUntil, now, invoiceVersion, reason, null);
    }

    /** 失败终态保留原租约和输入，过期后的任何结果统一记为超时。 */
    public InvoiceVerificationJob unavailable(Failure reason, Instant now) {
        if (status != Status.RUNNING) throw conflict();
        return new InvoiceVerificationJob(input, version + 1, Status.UNAVAILABLE, createdAt, startedAt, leaseUntil, now,
                null, null, expired(now) ? Failure.TIMEOUT : Objects.requireNonNull(reason));
    }

    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }
    private void requireLive(Instant now) { if (status != Status.RUNNING || expired(now)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_INVOICE_VERIFICATION_JOB", "Invoice verification job state is invalid"); }
    private static DomainException conflict() { return new DomainException("INVOICE_VERIFICATION_STATE_CONFLICT", "Invoice verification job is no longer executable"); }

    /**
     * 服务端冻结实际员工、原件、法人、版本及目标指纹，不保存外部凭据或原件字节。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID invoiceId, String ownerId, long invoiceVersion,
                        UUID legalEntityId, UUID originalId, String originalDigest, String targetDigest) {
        /** 身份与摘要须完整，不能通过任务 JSON 替换原件。 */
        public Input {
            if (id == null || invoiceId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                    || StringUtils.isBlank(ownerId) || ownerId.length() > 128 || invoiceVersion < 1 || legalEntityId == null
                    || originalId == null || originalDigest == null || !originalDigest.matches("[a-f0-9]{64}")
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
        }
    }

    /**
     * 三种终态分别表示真实通过、真实业务拒绝、没有可信结论。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, SUCCEEDED, REJECTED, UNAVAILABLE }
    /**
     * 只允许验票端口的封闭拒绝分类。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { LEGAL_ENTITY_UNAVAILABLE, INVOICE_INVALID, INVOICE_CANCELLED, INVOICE_BUYER_MISMATCH }
    /**
     * 本地与外部不可用均不代表票面无效。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, TARGET_CHANGED, ORIGINAL_UNAVAILABLE, INVOICE_CHANGED, INTERNAL_ERROR }
}
