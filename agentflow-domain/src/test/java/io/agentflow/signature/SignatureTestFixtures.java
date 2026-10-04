package io.agentflow.signature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 受控签署事实，只验证本地协议和状态约束，不代表真实证书或电子签服务验收。
 * @author owlzhangfq@gmail.com
 */
final class SignatureTestFixtures {
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private SignatureTestFixtures() { }

    static SignatureRequest request() {
        return new SignatureRequest(UUID.fromString("10000000-0000-0000-0000-000000000001"), "tenant-a",
                new SignatureRequest.Source(UUID.fromString("10000000-0000-0000-0000-000000000002"), 2, 7, "contract", 3),
                new SignatureRequest.Authorization("alice", "company-seal", 2, "c".repeat(64), "合同签署授权", NOW, NOW.plusSeconds(3600)),
                List.of(new SignatureRequest.Document(UUID.fromString("20000000-0000-0000-0000-000000000001"),
                                UUID.fromString("30000000-0000-0000-0000-000000000001"), "contract", "合同.pdf", 1001, "a".repeat(64)),
                        new SignatureRequest.Document(UUID.fromString("20000000-0000-0000-0000-000000000002"),
                                UUID.fromString("30000000-0000-0000-0000-000000000002"), "items.attachment", "补充协议.pdf", 2002, "b".repeat(64))),
                List.of(new SignatureRequest.Signer("company", "provider-company-1"), new SignatureRequest.Signer("counterparty", "provider-user-7")));
    }

    static SignatureReceipt receipt(SignatureRequest request, SignatureReceipt.Status status, long revision, Instant time) {
        boolean terminal = status == SignatureReceipt.Status.SIGNED || status == SignatureReceipt.Status.DECLINED || status == SignatureReceipt.Status.CANCELLED;
        var artifacts = status == SignatureReceipt.Status.SIGNED ? request.documents().stream().map(document -> new SignatureReceipt.Artifact(
                document.attachmentId(), document.size() + 400, "e".repeat(64), "application/pdf", request.signers().stream().map(signer ->
                new SignatureReceipt.Proof(signer.key(), "f".repeat(64), time, null)).toList())).toList() : List.<SignatureReceipt.Artifact>of();
        return new SignatureReceipt(request.id(), request.digest(), status == SignatureReceipt.Status.NOT_FOUND ? 0 : revision, status,
                time, status == SignatureReceipt.Status.NOT_FOUND ? null : "provider-signature-1", terminal ? time : null, artifacts);
    }

    static SignatureOperation queued() {
        return SignatureOperation.queue(new SignatureOperation.Input(request(), "d".repeat(64)), NOW);
    }
}
