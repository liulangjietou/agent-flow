package io.agentflow.signature;

import io.agentflow.common.DomainException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 经适配器验证的服务方版本事实；签署成功仍须逐份保存和校验结果文件后才能本地完成。
 * @author owlzhangfq@gmail.com
 */
public record SignatureReceipt(UUID operationId, String requestDigest, long revision, Status status, Instant recordedAt,
                               String providerReference, Instant completedAt, List<Artifact> artifacts) {
    public static final long MAX_ARTIFACT_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_ARTIFACT_BYTES = 64L * 1024 * 1024;

    /** 每个外部修订的正文必须稳定；轮询和回调不得为同一修订重新生成时间或文件指纹。 */
    public SignatureReceipt {
        if (operationId == null || !SignatureRequest.digestValue(requestDigest) || status == null || recordedAt == null
                || artifacts == null || artifacts.size() > SignatureRequest.MAX_DOCUMENTS || artifacts.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        artifacts = artifacts.stream().sorted(Comparator.comparing(value -> value.documentId().toString())).toList();
        boolean terminal = status == Status.SIGNED || status == Status.DECLINED || status == Status.CANCELLED;
        if (status == Status.NOT_FOUND ? revision != 0 || providerReference != null : revision < 1 || !SignatureRequest.literal(providerReference, 128)) throw invalid();
        if (terminal ? completedAt == null || completedAt.isAfter(recordedAt) : completedAt != null) throw invalid();
        if ((status == Status.SIGNED) != !artifacts.isEmpty()
                || artifacts.stream().map(Artifact::documentId).distinct().count() != artifacts.size()
                || artifacts.stream().mapToLong(Artifact::size).sum() > MAX_TOTAL_ARTIFACT_BYTES
                || artifacts.stream().flatMap(value -> value.signatures().stream()).anyMatch(value -> value.signedAt().isAfter(completedAt))) throw invalid();
    }

    /** 回执必须属于原授权及全部原文件和签署方；未找到仅是查询观察，不能证明从未产生副作用。 */
    public boolean matches(SignatureRequest request, Instant now) {
        if (!operationId.equals(request.id()) || !requestDigest.equals(request.digest()) || recordedAt.isAfter(now)
                || recordedAt.isBefore(request.authorization().authorizedAt()) || completedAt != null && completedAt.isBefore(request.authorization().authorizedAt())) return false;
        if (status != Status.SIGNED) return true;
        if (artifacts.size() != request.documents().size()) return false;
        for (var artifact : artifacts) {
            if (request.documents().stream().noneMatch(document -> document.attachmentId().equals(artifact.documentId()))
                    || !artifact.signatures().stream().map(Proof::signerKey).toList().equals(request.signers().stream().map(SignatureRequest.Signer::key).toList())
                    || artifact.signatures().stream().anyMatch(proof -> proof.signedAt().isBefore(request.authorization().authorizedAt()))) return false;
        }
        return true;
    }

    /** 摘要覆盖可核验的完整回执事实，便于识别同一外部修订的冲突与重放。 */
    public String digest() {
        var digest = SignatureRequest.sha256();
        SignatureRequest.add(digest, "agentflow-signature-receipt-1", operationId.toString(), requestDigest, Long.toString(revision), status.name(),
                recordedAt.toString(), providerReference == null ? "" : providerReference, completedAt == null ? "" : completedAt.toString(), Integer.toString(artifacts.size()));
        for (var artifact : artifacts) {
            SignatureRequest.add(digest, artifact.documentId().toString(), Long.toString(artifact.size()), artifact.sha256(), artifact.mediaType(), Integer.toString(artifact.signatures().size()));
            for (var proof : artifact.signatures()) SignatureRequest.add(digest, proof.signerKey(), proof.certificateSha256(), proof.signedAt().toString(), proof.timestampReference() == null ? "" : proof.timestampReference());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override public String toString() { return "SignatureReceipt[operationId=" + operationId + ", revision=" + revision + ", status=" + status + ", artifacts=" + artifacts.size() + "]"; }

    /**
     * NOT_FOUND 不授权再次发送，也不能把可能仍在远端执行的签署标记为取消。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_FOUND, PENDING, SIGNED, DECLINED, CANCELLED }

    /**
     * 结果只给出原文件标识和内容指纹，不接收可供服务器任意抓取的下载 URL。
     * @author owlzhangfq@gmail.com
     */
    public record Artifact(UUID documentId, long size, String sha256, String mediaType, List<Proof> signatures) {
        public Artifact {
            if (documentId == null || size < 1 || size > MAX_ARTIFACT_BYTES || !SignatureRequest.digestValue(sha256)
                    || mediaType == null || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}")
                    || signatures == null || signatures.isEmpty() || signatures.size() > SignatureRequest.MAX_SIGNERS || signatures.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            signatures = signatures.stream().sorted(Comparator.comparing(Proof::signerKey)).toList();
            if (signatures.stream().map(Proof::signerKey).distinct().count() != signatures.size()) throw invalid();
        }
    }

    /**
     * 保留服务方核验的签署证据；没有时间戳凭据时不能声称已完成可信时间戳验收。
     * @author owlzhangfq@gmail.com
     */
    public record Proof(String signerKey, String certificateSha256, Instant signedAt, String timestampReference) {
        public Proof {
            if (!SignatureRequest.key(signerKey) || !SignatureRequest.digestValue(certificateSha256) || signedAt == null
                    || timestampReference != null && !SignatureRequest.literal(timestampReference, 128)) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_SIGNATURE_RECEIPT", "Signature receipt status, evidence and document fingerprints must be complete and consistent"); }
}
