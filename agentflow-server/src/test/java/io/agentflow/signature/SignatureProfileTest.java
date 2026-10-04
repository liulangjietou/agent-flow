package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 授权资料绑定真实主体、公钥和全部签署方，管理员身份不产生额外签署权。
 * @author owlzhangfq@gmail.com
 */
class SignatureProfileTest {
    @Test void immutableCanonicalProfileBindsEveryAuthorizationDimension() {
        var original = profile(keyPair()); var actors = new ArrayList<>(List.of("bob", "alice"));
        var value = new SignatureProfile(original.tenantId(), original.key(), original.version(), original.name(), actors, original.signers(), original.receiptPublicKey());
        actors.clear();
        assertThat(value.actors()).containsExactly("alice", "bob");
        var reordered = new SignatureProfile(value.tenantId(), value.key(), value.version(), value.name(), List.of("alice", "bob"), value.signers(), value.receiptPublicKey());
        assertThat(value.digest()).isEqualTo(reordered.digest()).isNotEqualTo(original.digest());
        assertThat(value.matches(input(value).request())).isTrue();
        assertThat(value.matches(input(original).request())).isFalse();
        assertThat(value.digest()).isNotEqualTo(new SignatureProfile(value.tenantId(), value.key(), value.version() + 1, value.name(), value.actors(), value.signers(), value.receiptPublicKey()).digest());
        assertThat(value.toString()).doesNotContain("alice", "provider-company-1", value.receiptPublicKey());
    }

    @ParameterizedTest @ValueSource(strings = {"empty-actors", "duplicate-actors", "control-actor", "empty-signers", "duplicate-subject", "bad-key", "noncanonical-key", "blank-name", "zero-version"})
    void invalidTrustDeclarationsFailClosed(String variant) {
        var p = profile(keyPair());
        var actors = switch (variant) { case "empty-actors" -> List.<String>of(); case "duplicate-actors" -> List.of("alice", "alice"); case "control-actor" -> List.of("alice\n"); default -> p.actors(); };
        var signers = switch (variant) { case "empty-signers" -> List.<SignatureRequest.Signer>of(); case "duplicate-subject" -> List.of(p.signers().get(0), new SignatureRequest.Signer("other", "provider-company-1")); default -> p.signers(); };
        String key = switch (variant) { case "bad-key" -> "A".repeat(60); case "noncanonical-key" -> p.receiptPublicKey().replace("=", ""); default -> p.receiptPublicKey(); };
        assertThatThrownBy(() -> new SignatureProfile(p.tenantId(), p.key(), variant.equals("zero-version") ? 0 : p.version(), variant.equals("blank-name") ? " " : p.name(), actors, signers, key))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).code()).isEqualTo("INVALID_SIGNATURE_PROFILE");
    }

    @Test void exactActorAndSignerMappingCannotBeSubstitutedEvenWithAnOtherwiseMatchingDigest() {
        var p = profile(keyPair()); var request = input(p).request(); var auth = request.authorization();
        var admin = new SignatureRequest(request.id(), request.tenantId(), request.source(), new SignatureRequest.Authorization("admin", auth.profileKey(), auth.profileVersion(), auth.profileDigest(), auth.purpose(), auth.authorizedAt(), auth.validUntil()), request.documents(), request.signers());
        assertThat(p.matches(admin)).isFalse();
        var changed = new SignatureRequest(request.id(), request.tenantId(), request.source(), auth, request.documents(), List.of(new SignatureRequest.Signer("company", "another-provider-subject")));
        assertThat(p.matches(changed)).isFalse();
    }
}
