package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置失效时返回稳定业务错误，不暴露凭据，也不降级到未授权目的地。
 * @author owlzhangfq@gmail.com
 */
class AssistConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"http:/broken", "http://remote.example/chat", "https://user:secret@example.com/chat",
            "https://example.com/chat?api_key=secret", "https://example.com/chat#secret", "file:///etc/passwd", ""})
    void invalidEndpointUsesDomainError(String endpoint) {
        var configuration = enabled(endpoint);
        assertThatThrownBy(configuration::requireAvailable).isInstanceOf(DomainException.class)
                .hasMessage("Assist model configuration is invalid");
    }

    @Test
    void destinationFingerprintBindsEndpointProviderModelAndNotSecretRotation() {
        var configuration = enabled("https://model.example/v1/chat/completions");
        configuration.requireAvailable(); String original = configuration.targetDigest();
        configuration.setApiKey("rotated-key");
        assertThat(configuration.targetDigest()).isEqualTo(original);
        configuration.setModel("different-model");
        assertThat(configuration.targetDigest()).isNotEqualTo(original);
        configuration.setEnabled(false);
        assertThatThrownBy(configuration::requireAvailable).isInstanceOf(DomainException.class)
                .hasMessage("Assist model is disabled");
    }

    private AssistConfiguration enabled(String endpoint) {
        var configuration = new AssistConfiguration();
        configuration.setEnabled(true); configuration.setModel("model"); configuration.setEndpoint(endpoint);
        return configuration;
    }
}
