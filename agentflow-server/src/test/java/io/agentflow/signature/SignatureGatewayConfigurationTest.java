package io.agentflow.signature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 配置只信任明确的租户版本和固定地址，保留旧版本而不静默切换外发目标。
 * @author owlzhangfq@gmail.com
 */
class SignatureGatewayConfigurationTest {
    @Test void featureDefaultsOffAndDisabledProfileStillResolvesItsExactVersionForQueries() {
        var configuration = new SignatureGatewayConfiguration(); var bean = bean();
        configuration.setTenants(Map.of("tenant-a", List.of(bean)));
        assertThat(configuration.declarations()).isEmpty();
        configuration.setEnabled(true); bean.setEnabled(false); configuration.validate();
        assertThat(configuration.find("tenant-a", "company-seal", 2)).isPresent().get().extracting(SignatureGatewayConfiguration.Declaration::enabled).isEqualTo(false);
        assertThat(configuration.find("tenant-b", "company-seal", 2)).isEmpty();
        assertThat(configuration.find("tenant-a", "company-seal", 3)).isEmpty();
    }

    @Test void targetIsStableAcrossCredentialAndTimeoutRotationButNotAddressOrKeyChanges() {
        var b = bean(); var configuration = configuration(b); var old = configuration.declarations().get(0);
        b.setToken("rotated-secret"); b.setTimeoutSeconds(30); b.setEndpoint("https://signature.example.test/api/");
        assertThat(configuration.declarations().get(0).targetDigest()).isEqualTo(old.targetDigest());
        b.setEndpoint("https://signature.example.test/other");
        assertThat(configuration.declarations().get(0).targetDigest()).isNotEqualTo(old.targetDigest());
        b.setEndpoint("https://signature.example.test/api"); b.setReceiptPublicKey(profile(keyPair()).receiptPublicKey());
        assertThat(configuration.declarations().get(0).targetDigest()).isNotEqualTo(old.targetDigest());
        assertThat(old.toString()).doesNotContain("test-secret", "https://", "provider-company-1");
    }

    @ParameterizedTest @ValueSource(strings = {"http://signature.example.test/api", "http://localhost/api", "https://user@signature.example.test/api", "https://signature.example.test/api?x=1", "https://signature.example.test/api#x", "https://signature.example.test/a/../b", "https://signature.example.test:0/api", "https://signature.example.test:99999/api", "file:///tmp/x", "https://signature.example.test/a%2fb"})
    void unsafeOrAmbiguousDestinationsAreRejected(String endpoint) {
        var b = bean(); b.setEndpoint(endpoint);
        assertThatThrownBy(() -> configuration(b).validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unauthenticatedLoopbackRequiresExplicitOptInAndCannotEnableHttpsWithoutCredentials() {
        var b = bean(); b.setEndpoint("http://127.0.0.1:18080/signature"); b.setToken("");
        assertThatThrownBy(() -> configuration(b).validate()).isInstanceOf(IllegalArgumentException.class);
        b.setAllowUnauthenticatedLoopback(true); assertThatCode(() -> configuration(b).validate()).doesNotThrowAnyException();
        b.setEndpoint("https://signature.example.test/api");
        assertThatThrownBy(() -> configuration(b).validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateVersionsAndHeaderInjectionFailBeforeAnyNetworkCall() {
        var b = bean(); var c = configuration(b); c.setTenants(Map.of("tenant-a", List.of(b, b)));
        assertThatThrownBy(c::validate).isInstanceOf(IllegalArgumentException.class);
        b.setToken("secret\r\nX-Other: value");
        assertThatThrownBy(() -> configuration(b).validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void springBindsExplicitActorAndSignerRecordsFromDeploymentProperties() {
        String prefix = "agentflow.signatures.gateway."; String entry = prefix + "tenants[tenant-a][0].";
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put(prefix + "enabled", true); values.put(entry + "key", "company-seal"); values.put(entry + "version", 2); values.put(entry + "name", "企业合同签署");
        values.put(entry + "actors[0]", "alice"); values.put(entry + "signers[0].key", "company"); values.put(entry + "signers[0].provider-subject", "provider-company-1");
        values.put(entry + "receipt-public-key", profile(keyPair()).receiptPublicKey()); values.put(entry + "endpoint", "https://signature.example.test/api"); values.put(entry + "token", "deployment-secret");
        var bound = new Binder(new MapConfigurationPropertySource(values)).bind(prefix.substring(0, prefix.length() - 1), Bindable.of(SignatureGatewayConfiguration.class)).get();
        bound.validate(); assertThat(bound.declarations()).hasSize(1); assertThat(bound.declarations().get(0).profile().actors()).containsExactly("alice");
        assertThat(bound.declarations().get(0).profile().signers()).containsExactly(new SignatureRequest.Signer("company", "provider-company-1"));
    }

    private SignatureGatewayConfiguration configuration(SignatureGatewayConfiguration.Profile bean) {
        var result = new SignatureGatewayConfiguration(); result.setEnabled(true); result.setTenants(Map.of("tenant-a", List.of(bean))); return result;
    }
    private SignatureGatewayConfiguration.Profile bean() {
        var p = profile(keyPair()); var result = new SignatureGatewayConfiguration.Profile();
        result.setKey(p.key()); result.setVersion(p.version()); result.setName(p.name()); result.setActors(p.actors()); result.setSigners(p.signers()); result.setReceiptPublicKey(p.receiptPublicKey());
        result.setEndpoint("https://signature.example.test/api"); result.setToken("test-secret"); return result;
    }
}
