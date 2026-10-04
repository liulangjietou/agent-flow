package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.agentflow.signature.SignatureTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 授权、来源、签署人及文件一起冻结，不能通过列表变更或摘要拼接替换待签内容。
 * @author owlzhangfq@gmail.com
 */
class SignatureRequestTest {
    @Test
    void freezesCallerCollectionsAndUsesCanonicalOrderForTheSameFilesAndSigners() {
        var original = request();
        var documents = new ArrayList<>(original.documents());
        var signers = new ArrayList<>(original.signers());
        Collections.reverse(documents); Collections.reverse(signers);
        var frozen = new SignatureRequest(original.id(), original.tenantId(), original.source(), original.authorization(), documents, signers);
        documents.clear(); signers.clear();
        assertThat(frozen).isEqualTo(original);
        assertThat(frozen.digest()).isEqualTo(original.digest()).matches("[a-f0-9]{64}");
        assertThatThrownBy(() -> frozen.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozen.signers().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void digestBindsIdentityTenantRoundAndProcessVersion() {
        var value = request(); var source = value.source();
        var changed = List.of(
                new SignatureRequest(UUID.randomUUID(), value.tenantId(), source, value.authorization(), value.documents(), value.signers()),
                new SignatureRequest(value.id(), "tenant-b", source, value.authorization(), value.documents(), value.signers()),
                source(value, new SignatureRequest.Source(UUID.randomUUID(), source.roundNo(), source.applicationVersion(), source.processKey(), source.definitionVersion())),
                source(value, new SignatureRequest.Source(source.applicationId(), 3, source.applicationVersion(), source.processKey(), source.definitionVersion())),
                source(value, new SignatureRequest.Source(source.applicationId(), 2, 8, source.processKey(), source.definitionVersion())),
                source(value, new SignatureRequest.Source(source.applicationId(), 2, 7, "other-contract", 3)),
                source(value, new SignatureRequest.Source(source.applicationId(), 2, 7, "contract", 4)));
        assertThat(changed).extracting(SignatureRequest::digest).doesNotContain(value.digest()).doesNotHaveDuplicates();
    }

    @Test
    void digestBindsAuthorizationScopeAndConfiguredSignerSubject() {
        var value = request(); var auth = value.authorization();
        var authorizations = List.of(
                new SignatureRequest.Authorization("bob", auth.profileKey(), 2, auth.profileDigest(), auth.purpose(), NOW, auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), "personal-signature", 2, auth.profileDigest(), auth.purpose(), NOW, auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), 3, auth.profileDigest(), auth.purpose(), NOW, auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), 2, "b".repeat(64), auth.purpose(), NOW, auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), 2, auth.profileDigest(), "补充协议授权", NOW, auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), 2, auth.profileDigest(), auth.purpose(), NOW.plusSeconds(1), auth.validUntil()),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), 2, auth.profileDigest(), auth.purpose(), NOW, auth.validUntil().plusSeconds(1)));
        assertThat(authorizations.stream().map(changed -> new SignatureRequest(value.id(), value.tenantId(), value.source(), changed, value.documents(), value.signers()).digest()).toList())
                .doesNotContain(value.digest()).doesNotHaveDuplicates();
        var newSigners = List.of(value.signers().get(0), new SignatureRequest.Signer("counterparty", "provider-user-8"));
        assertThat(new SignatureRequest(value.id(), value.tenantId(), value.source(), auth, value.documents(), newSigners).digest()).isNotEqualTo(value.digest());
    }

    @Test
    void digestBindsAttachmentContentFieldNameSizeAndFingerprint() {
        var value = request(); var file = value.documents().get(0);
        var changed = List.of(
                new SignatureRequest.Document(UUID.randomUUID(), file.contentId(), file.fieldPath(), file.filename(), file.size(), file.sha256()),
                new SignatureRequest.Document(file.attachmentId(), UUID.randomUUID(), file.fieldPath(), file.filename(), file.size(), file.sha256()),
                new SignatureRequest.Document(file.attachmentId(), file.contentId(), "other", file.filename(), file.size(), file.sha256()),
                new SignatureRequest.Document(file.attachmentId(), file.contentId(), file.fieldPath(), "其他.pdf", file.size(), file.sha256()),
                new SignatureRequest.Document(file.attachmentId(), file.contentId(), file.fieldPath(), file.filename(), file.size() + 1, file.sha256()),
                new SignatureRequest.Document(file.attachmentId(), file.contentId(), file.fieldPath(), file.filename(), file.size(), "f".repeat(64)));
        assertThat(changed.stream().map(document -> new SignatureRequest(value.id(), value.tenantId(), value.source(), value.authorization(),
                List.of(document, value.documents().get(1)), value.signers()).digest()).toList()).doesNotContain(value.digest()).doesNotHaveDuplicates();
    }

    @Test
    void rejectsRepeatedAttachmentIdentityAndAmbiguousSignerBindings() {
        var value = request(); var file = value.documents().get(0); var signer = value.signers().get(0);
        assertThatThrownBy(() -> new SignatureRequest(value.id(), value.tenantId(), value.source(), value.authorization(), List.of(file, file), value.signers()))
                .isInstanceOf(DomainException.class);
        for (var signers : List.of(List.of(signer, new SignatureRequest.Signer(signer.key(), "another-subject")),
                List.of(signer, new SignatureRequest.Signer("another-key", signer.providerSubject())))) {
            assertThatThrownBy(() -> new SignatureRequest(value.id(), value.tenantId(), value.source(), value.authorization(), value.documents(), signers))
                    .isInstanceOf(DomainException.class);
        }
    }

    @Test
    void rejectsOversizedFileSetsAndEmptySignersWithoutOverflow() {
        var value = request();
        var large = new ArrayList<SignatureRequest.Document>();
        for (int index = 0; index < 3; index++) large.add(new SignatureRequest.Document(UUID.randomUUID(), UUID.randomUUID(), "files", "file.pdf", SignatureRequest.MAX_DOCUMENT_BYTES, "a".repeat(64)));
        assertThatThrownBy(() -> new SignatureRequest(value.id(), value.tenantId(), value.source(), value.authorization(), large, value.signers())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureRequest(value.id(), value.tenantId(), value.source(), value.authorization(), value.documents(), List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SignatureRequest.Document(UUID.randomUUID(), UUID.randomUUID(), "files", "file.pdf", Long.MAX_VALUE, "a".repeat(64))).isInstanceOf(DomainException.class);
    }

    @Test
    void authorizationWindowMustBePositiveAndBounded() {
        var value = request().authorization();
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), SignatureRequest.MAX_AUTHORIZATION_LIFETIME.plusSeconds(1))) {
            assertThatThrownBy(() -> new SignatureRequest.Authorization(value.actor(), value.profileKey(), value.profileVersion(), value.profileDigest(), value.purpose(), NOW, NOW.plus(duration)))
                    .isInstanceOf(DomainException.class);
        }
        assertThat(new SignatureRequest.Authorization(value.actor(), value.profileKey(), value.profileVersion(), value.profileDigest(), value.purpose(), NOW,
                NOW.plus(SignatureRequest.MAX_AUTHORIZATION_LIFETIME)).validUntil()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void identifiersAndFileNamesCannotCarryControlCharactersOrInvalidUnicode() {
        var value = request();
        for (String actor : List.of(" ", "alice\nadmin", " alice", "alice ", "alice\uD800")) {
            assertThatThrownBy(() -> new SignatureRequest.Authorization(actor, "profile", 1, "a".repeat(64), "授权", NOW, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        }
        for (String filename : List.of("../合同.pdf", "folder\\file.pdf", "合同\n.pdf")) {
            assertThatThrownBy(() -> new SignatureRequest.Document(UUID.randomUUID(), UUID.randomUUID(), "files", filename, 1, "a".repeat(64))).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> source(value, new SignatureRequest.Source(value.source().applicationId(), 0, 1, "contract", 1))).isInstanceOf(DomainException.class);
    }

    @Test
    void diagnosticStringsDoNotRevealDocumentNamesOrSigningSubjects() {
        var value = request();
        assertThat(value.toString() + value.authorization() + value.documents() + value.signers())
                .doesNotContain("alice", "合同", "补充协议", "provider-company-1", "provider-user-7", value.authorization().profileDigest());
    }

    private SignatureRequest source(SignatureRequest value, SignatureRequest.Source source) {
        return new SignatureRequest(value.id(), value.tenantId(), source, value.authorization(), value.documents(), value.signers());
    }
}
