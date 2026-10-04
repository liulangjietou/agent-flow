package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.agentflow.signature.SignatureTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 回执必须指向全部原文件及签署方，完整性指纹不代替适配器的证书和回执验真。
 * @author owlzhangfq@gmail.com
 */
class SignatureReceiptTest {
    @Test
    void canonicalReceiptFreezesFilesAndProofsAcrossQueryAndCallbackOrder() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 2, NOW.plusSeconds(1));
        var proofs = new ArrayList<>(value.artifacts().get(0).signatures()); Collections.reverse(proofs);
        var first = value.artifacts().get(0);
        var file = new SignatureReceipt.Artifact(first.documentId(), first.size(), first.sha256(), first.mediaType(), proofs);
        var files = new ArrayList<>(List.of(value.artifacts().get(1), file));
        var reordered = copy(value, value.operationId(), value.requestDigest(), files);
        proofs.clear(); files.clear();
        assertThat(reordered).isEqualTo(value);
        assertThat(reordered.digest()).isEqualTo(value.digest());
        assertThat(reordered.matches(request(), NOW.plusSeconds(1))).isTrue();
        assertThatThrownBy(() -> reordered.artifacts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> file.signatures().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void receiptCannotBeReusedForAnotherOperationOrAuthorization() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 2, NOW);
        assertThat(copy(value, UUID.randomUUID(), value.requestDigest(), value.artifacts()).matches(request(), NOW)).isFalse();
        assertThat(copy(value, value.operationId(), "b".repeat(64), value.artifacts()).matches(request(), NOW)).isFalse();
    }

    @Test
    void rejectsFutureOrPreAuthorizationFactsButAllowsCompletionAfterSendAuthorizationExpires() {
        assertThat(receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)).matches(request(), NOW)).isFalse();
        assertThat(receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW.minusSeconds(1)).matches(request(), NOW)).isFalse();
        var later = NOW.plusSeconds(7200);
        assertThat(receipt(request(), SignatureReceipt.Status.SIGNED, 2, later).matches(request(), later)).isTrue();
    }

    @Test
    void signedReceiptRequiresExactlyTheRequestedDocuments() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW);
        assertThat(copy(value, value.operationId(), value.requestDigest(), List.of(value.artifacts().get(0))).matches(request(), NOW)).isFalse();
        var file = value.artifacts().get(1);
        var other = new SignatureReceipt.Artifact(UUID.randomUUID(), file.size(), file.sha256(), file.mediaType(), file.signatures());
        assertThat(copy(value, value.operationId(), value.requestDigest(), List.of(value.artifacts().get(0), other)).matches(request(), NOW)).isFalse();
    }

    @Test
    void everyDocumentRequiresAllConfiguredSignersAndNoSubstitutedSigner() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW); var file = value.artifacts().get(0);
        for (var proofs : List.of(List.of(file.signatures().get(0)), List.of(file.signatures().get(0), new SignatureReceipt.Proof("other", "f".repeat(64), NOW, null)))) {
            var changed = new SignatureReceipt.Artifact(file.documentId(), file.size(), file.sha256(), file.mediaType(), proofs);
            assertThat(copy(value, value.operationId(), value.requestDigest(), List.of(changed, value.artifacts().get(1))).matches(request(), NOW)).isFalse();
        }
    }

    @Test
    void proofTimesMustFollowAuthorizationAndNotFollowReceiptCompletion() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)); var first = value.artifacts().get(0);
        var before = first.signatures().stream().map(proof -> new SignatureReceipt.Proof(proof.signerKey(), proof.certificateSha256(), NOW.minusSeconds(1), null)).toList();
        var file = new SignatureReceipt.Artifact(first.documentId(), first.size(), first.sha256(), first.mediaType(), before);
        assertThat(copy(value, value.operationId(), value.requestDigest(), List.of(file, value.artifacts().get(1))).matches(request(), NOW.plusSeconds(1))).isFalse();
        var after = first.signatures().stream().map(proof -> new SignatureReceipt.Proof(proof.signerKey(), proof.certificateSha256(), NOW.plusSeconds(2), null)).toList();
        var futureFile = new SignatureReceipt.Artifact(first.documentId(), first.size(), first.sha256(), first.mediaType(), after);
        assertThatThrownBy(() -> copy(value, value.operationId(), value.requestDigest(), List.of(futureFile, value.artifacts().get(1)))).isInstanceOf(DomainException.class);
    }

    @Test
    void shapeRejectsFalseTerminalStatusAndAmbiguousDuplicateEvidence() {
        var request = request(); var signed = receipt(request, SignatureReceipt.Status.SIGNED, 1, NOW);
        assertThatThrownBy(() -> new SignatureReceipt(request.id(), request.digest(), 1, SignatureReceipt.Status.SIGNED, NOW, "ref", NOW, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureReceipt(request.id(), request.digest(), 1, SignatureReceipt.Status.PENDING, NOW, "ref", NOW, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureReceipt(request.id(), request.digest(), 0, SignatureReceipt.Status.PENDING, NOW, "ref", null, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureReceipt(request.id(), request.digest(), 0, SignatureReceipt.Status.NOT_FOUND, NOW, "ref", null, List.of())).isInstanceOf(DomainException.class);
        var file = signed.artifacts().get(0); var proof = file.signatures().get(0);
        assertThatThrownBy(() -> copy(signed, signed.operationId(), signed.requestDigest(), List.of(file, file))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureReceipt.Artifact(file.documentId(), file.size(), file.sha256(), file.mediaType(), List.of(proof, proof))).isInstanceOf(DomainException.class);
    }

    @Test
    void digestBindsCertificateTimestampAndSignedContent() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW); var first = value.artifacts().get(0);
        var proofs = new ArrayList<>(first.signatures()); var proof = proofs.get(0);
        proofs.set(0, new SignatureReceipt.Proof(proof.signerKey(), "a".repeat(64), NOW, "timestamp-1"));
        var changedProof = new SignatureReceipt.Artifact(first.documentId(), first.size(), first.sha256(), first.mediaType(), proofs);
        var changedBytes = new SignatureReceipt.Artifact(first.documentId(), first.size(), "a".repeat(64), first.mediaType(), first.signatures());
        assertThat(List.of(changedProof, changedBytes).stream().map(file -> copy(value, value.operationId(), value.requestDigest(), List.of(file, value.artifacts().get(1))).digest()).toList())
                .doesNotContain(value.digest()).doesNotHaveDuplicates();
    }

    @Test
    void enforcesTotalDownloadBudgetBeforeAnyFilesAreRequested() {
        var value = receipt(request(), SignatureReceipt.Status.SIGNED, 1, NOW); var file = value.artifacts().get(0);
        var files = new ArrayList<SignatureReceipt.Artifact>();
        for (int index = 0; index < 3; index++) files.add(new SignatureReceipt.Artifact(UUID.randomUUID(), SignatureReceipt.MAX_ARTIFACT_BYTES, file.sha256(), file.mediaType(), file.signatures()));
        assertThatThrownBy(() -> copy(value, value.operationId(), value.requestDigest(), files)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureReceipt.Artifact(file.documentId(), Long.MAX_VALUE, file.sha256(), file.mediaType(), file.signatures())).isInstanceOf(DomainException.class);
    }

    private SignatureReceipt copy(SignatureReceipt value, UUID operationId, String digest, List<SignatureReceipt.Artifact> artifacts) {
        return new SignatureReceipt(operationId, digest, value.revision(), value.status(), value.recordedAt(), value.providerReference(), value.completedAt(), artifacts);
    }
}
