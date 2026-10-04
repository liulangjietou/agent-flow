package io.agentflow.signature;

import io.agentflow.common.DomainException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件签署的持久状态；只允许一次发送，远端完成和本地文件保存分别确认。
 * @author owlzhangfq@gmail.com
 */
public record SignatureOperation(Input input, long version, Status status, int attempts, Instant updatedAt,
                                 Instant nextAttemptAt, Instant leaseUntil, SignatureReceipt receipt,
                                 Failure failure, List<StoredArtifact> artifacts) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);

    /** 恢复时校验阶段和原授权，不能将已尝试的签署恢复为首次发送。 */
    public SignatureOperation {
        if (input == null || status == null || updatedAt == null || version < 1 || attempts < 0
                || version < (long) attempts + 1 || updatedAt.isBefore(input.request().authorization().authorizedAt())
                || artifacts == null || artifacts.size() > SignatureRequest.MAX_DOCUMENTS
                || artifacts.stream().anyMatch(Objects::isNull)) throw invalid();
        artifacts = artifacts.stream().sorted(Comparator.comparing(value -> value.documentId().toString())).toList();
        if (receipt != null && (receipt.status() == SignatureReceipt.Status.NOT_FOUND || !receipt.matches(input.request(), updatedAt))) throw invalid();
        boolean running = status == Status.SENDING || status == Status.QUERYING || status == Status.FETCHING_FILES;
        boolean terminal = status == Status.SIGNED || status == Status.DECLINED || status == Status.CANCELLED || status == Status.EXPIRED;
        if (running ? leaseUntil == null || !leaseUntil.isAfter(updatedAt)
                || Duration.between(updatedAt, leaseUntil).compareTo(MAX_LEASE) > 0 || nextAttemptAt != null || failure != null
                : leaseUntil != null || (terminal ? nextAttemptAt != null : nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt))) throw invalid();

        boolean pending = receipt != null && receipt.status() == SignatureReceipt.Status.PENDING;
        boolean signed = receipt != null && receipt.status() == SignatureReceipt.Status.SIGNED;
        boolean validStage = switch (status) {
            case QUEUED -> version == 1 && attempts == 0 && receipt == null && failure == null
                    && updatedAt.equals(nextAttemptAt) && updatedAt.isBefore(input.request().authorization().validUntil());
            case SENDING -> version == 2 && attempts == 1 && receipt == null
                    && updatedAt.isBefore(input.request().authorization().validUntil());
            case QUERYING -> attempts >= 2 && (receipt == null || pending);
            case UNKNOWN -> attempts >= 1 && failure != null && (receipt == null || pending);
            case PENDING -> attempts >= 1 && pending && failure == null;
            case COLLECTING -> attempts >= 1 && signed;
            case FETCHING_FILES -> attempts >= 2 && signed;
            case SIGNED -> attempts >= 2 && signed && failure == null;
            case DECLINED -> attempts >= 1 && receipt != null && receipt.status() == SignatureReceipt.Status.DECLINED && failure == null;
            case CANCELLED -> version >= 2 && failure == null && (attempts == 0 ? receipt == null
                    : receipt != null && receipt.status() == SignatureReceipt.Status.CANCELLED);
            case EXPIRED -> version >= 2 && attempts == 0 && receipt == null && failure == null
                    && !updatedAt.isBefore(input.request().authorization().validUntil());
        };
        if (!validStage || !validArtifacts(input.request(), receipt, artifacts)) throw invalid();
    }

    /** 授权事务创建待发操作；后台必须先持久领取，才能发送原件。 */
    public static SignatureOperation queue(Input input, Instant now) {
        return new SignatureOperation(input, 1, Status.QUEUED, 0, now, now, null, null, null, List.of());
    }

    /** 未知及处理中只查询原号；服务方已签署则只领取结果文件，授权过期不再首次发送。 */
    public SignatureOperation claim(Instant now, Duration lease) {
        if ((status != Status.QUEUED && status != Status.UNKNOWN && status != Status.PENDING && status != Status.COLLECTING)
                || now.isBefore(nextAttemptAt) || lease == null || lease.isNegative() || lease.isZero() || lease.compareTo(MAX_LEASE) > 0) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.request().authorization().validUntil())) {
            return changed(Status.EXPIRED, now, null, null, null, List.of());
        }
        Status next = switch (status) {
            case QUEUED -> Status.SENDING;
            case COLLECTING -> Status.FETCHING_FILES;
            default -> Status.QUERYING;
        };
        return new SignatureOperation(input, Math.incrementExact(version), next, Math.incrementExact(attempts), now,
                null, now.plus(lease), receipt, null, artifacts);
    }

    /** 租约丢失后保留原号及已知证据；文件下载崩溃只重新收集同一份结果。 */
    public SignatureOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(status == Status.FETCHING_FILES ? Status.COLLECTING : Status.UNKNOWN,
                now, now, receipt, Failure.LEASE_EXPIRED, artifacts);
    }

    /** 接收本次发送或查询结果；真实字节和回执真实性必须先由适配器核验。 */
    public SignatureOperation complete(SignatureReceipt value, Instant now) {
        if ((status != Status.SENDING && status != Status.QUERYING) || now.isBefore(updatedAt)) throw conflict();
        if (expired(now)) return expire(now);
        if (value == null || !value.matches(input.request(), now)) return unavailable(Failure.INVALID_RESPONSE, now);
        if (value.status() == SignatureReceipt.Status.NOT_FOUND) {
            return unavailable(status == Status.QUERYING ? Failure.NOT_FOUND : Failure.INVALID_RESPONSE, now);
        }
        if (receipt != null && value.revision() < receipt.revision()) return unavailable(Failure.STALE_RESPONSE, now);
        if (conflicting(value)) return unavailable(Failure.CONFLICTING_RECEIPT, now);
        return adopt(value, now);
    }

    /** 已验真的回调可以结束当前领取；旧版本和相同回执不改状态，冲突终态不覆盖。 */
    public SignatureOperation receiveCallback(SignatureReceipt value, Instant now) {
        if (attempts == 0 || now.isBefore(updatedAt) || value == null || !value.matches(input.request(), now)
                || value.status() == SignatureReceipt.Status.NOT_FOUND) throw receiptConflict();
        if (receipt != null && value.revision() < receipt.revision()) return this;
        if (conflicting(value)) throw receiptConflict();
        if (receipt != null && value.digest().equals(receipt.digest())) return this;
        return adopt(value, now);
    }

    /** 不保存远端异常正文；失败后仍保留已知回执和预分配文件标识，不能重新发起签署。 */
    public SignatureOperation unavailable(Failure value, Instant now) {
        if (!running() || now.isBefore(updatedAt) || value == null || value == Failure.LEASE_EXPIRED
                || value == Failure.NOT_FOUND && status != Status.QUERYING) throw conflict();
        if (expired(now)) return expire(now);
        return changed(status == Status.FETCHING_FILES ? Status.COLLECTING : Status.UNKNOWN,
                now, retryAt(now), receipt, value, artifacts);
    }

    /** 存储层校验全部文件字节后确认；缺一份、指纹不符或替换内容标识均不能本地完成。 */
    public SignatureOperation completeFiles(List<StoredArtifact> verified, Instant now) {
        if (status != Status.FETCHING_FILES || now.isBefore(updatedAt)) throw conflict();
        if (expired(now)) return expire(now);
        if (verified == null || verified.size() != artifacts.size() || verified.stream().anyMatch(Objects::isNull)
                || !new HashSet<>(verified).equals(new HashSet<>(artifacts))) return unavailable(Failure.ARTIFACT_MISMATCH, now);
        return changed(Status.SIGNED, now, null, receipt, null, artifacts);
    }

    /** 仅在发送前允许本地取消；查询未找到也不等于远端没有正在执行的签署。 */
    public SignatureOperation cancelUnsent(Instant now) {
        if (status != Status.QUEUED || now.isBefore(updatedAt)) throw conflict();
        return changed(Status.CANCELLED, now, null, null, null, List.of());
    }

    public boolean terminal() { return status == Status.SIGNED || status == Status.DECLINED || status == Status.CANCELLED || status == Status.EXPIRED; }
    public boolean running() { return status == Status.SENDING || status == Status.QUERYING || status == Status.FETCHING_FILES; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }

    private boolean conflicting(SignatureReceipt value) {
        if (receipt == null) return false;
        if (value.revision() == receipt.revision()) return !value.digest().equals(receipt.digest());
        return receipt.status() != SignatureReceipt.Status.PENDING || !value.providerReference().equals(receipt.providerReference())
                || value.recordedAt().isBefore(receipt.recordedAt());
    }

    private SignatureOperation adopt(SignatureReceipt value, Instant now) {
        return switch (value.status()) {
            case PENDING -> changed(Status.PENDING, now, retryAt(now), value, null, List.of());
            case SIGNED -> changed(Status.COLLECTING, now, now, value, null, reserveArtifacts(value));
            case DECLINED -> changed(Status.DECLINED, now, null, value, null, List.of());
            case CANCELLED -> changed(Status.CANCELLED, now, null, value, null, List.of());
            case NOT_FOUND -> throw receiptConflict();
        };
    }

    private List<StoredArtifact> reserveArtifacts(SignatureReceipt value) {
        var allocated = new HashSet<>(input.request().documents().stream().map(SignatureRequest.Document::contentId).toList());
        return value.artifacts().stream().map(artifact -> {
            UUID contentId;
            do { contentId = UUID.randomUUID(); } while (!allocated.add(contentId));
            return new StoredArtifact(artifact.documentId(), contentId, artifact.size(), artifact.sha256());
        }).toList();
    }

    private SignatureOperation changed(Status next, Instant now, Instant retryAt, SignatureReceipt value, Failure problem, List<StoredArtifact> files) {
        return new SignatureOperation(input, Math.incrementExact(version), next, attempts, now, retryAt, null, value, problem, files);
    }

    private Instant retryAt(Instant now) {
        return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6)));
    }

    private static boolean validArtifacts(SignatureRequest request, SignatureReceipt receipt, List<StoredArtifact> artifacts) {
        if (receipt == null || receipt.status() != SignatureReceipt.Status.SIGNED) return artifacts.isEmpty();
        if (artifacts.size() != receipt.artifacts().size()
                || artifacts.stream().map(StoredArtifact::documentId).distinct().count() != artifacts.size()
                || artifacts.stream().map(StoredArtifact::contentId).distinct().count() != artifacts.size()) return false;
        for (var file : artifacts) {
            if (request.documents().stream().anyMatch(original -> original.contentId().equals(file.contentId()))
                    || receipt.artifacts().stream().noneMatch(proof -> proof.documentId().equals(file.documentId())
                    && proof.size() == file.size() && proof.sha256().equals(file.sha256()))) return false;
        }
        return true;
    }

    /**
     * 固定可信目标摘要；后台恢复不能因配置改变而把原授权改发到另一个服务。
     * @author owlzhangfq@gmail.com
     */
    public record Input(SignatureRequest request, String targetDigest) {
        public Input { if (request == null || !SignatureRequest.digestValue(targetDigest)) throw invalid(); }
    }

    /**
     * 文件标识在领取下载前持久分配；反复恢复沿用同一标识，绝不覆盖原件。
     * @author owlzhangfq@gmail.com
     */
    public record StoredArtifact(UUID documentId, UUID contentId, long size, String sha256) {
        public StoredArtifact {
            if (documentId == null || contentId == null || size < 1 || size > SignatureReceipt.MAX_ARTIFACT_BYTES
                    || !SignatureRequest.digestValue(sha256)) throw invalid();
        }
    }

    /**
     * COLLECTING 表示远端已签署但本地文件尚未确认保存，不能显示为交付完成。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, SENDING, UNKNOWN, QUERYING, PENDING, COLLECTING, FETCHING_FILES, SIGNED, DECLINED, CANCELLED, EXPIRED }

    /**
     * 有界失败分类；NOT_FOUND 只描述本次查询，不允许重新发送或本地取消。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, OPERATION_DISABLED, TIMEOUT, CONNECTION, AUTHENTICATION,
        REMOTE_FAILURE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, STALE_RESPONSE, CONFLICTING_RECEIPT, NOT_FOUND,
        ARTIFACT_MISMATCH, LEASE_EXPIRED, AUTHORIZATION_EXPIRED, SOURCE_UNAVAILABLE, STORAGE_UNAVAILABLE, INTERNAL_ERROR }

    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_OPERATION", "Signature operation state, authorization and artifacts must be consistent"); }
    private static DomainException conflict() { return new DomainException("SIGNATURE_OPERATION_CONFLICT", "Signature operation is no longer executable with this claim"); }
    private static DomainException receiptConflict() { return new DomainException("SIGNATURE_RECEIPT_CONFLICT", "Signature receipt does not match the original operation or its accepted revision"); }
}
