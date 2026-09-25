package io.agentflow.system;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 期望值由部署显式给定；关闭实例不能让期望值随实际数量下降。
 * @author owlzhangfq@gmail.com
 */
class RuntimeMetricsConfigurationTest {
    @Test
    void exposesConfiguredCountAndRejectsInvalidDeploymentSize() {
        var configuration = new RuntimeMetricsConfiguration();
        var registry = new SimpleMeterRegistry();
        try {
            configuration.expectedInstances(2).bindTo(registry);
            assertThat(registry.get("agentflow.runtime.expected.instances").gauge().value()).isEqualTo(2);
        } finally { registry.close(); }
        assertThatThrownBy(() -> configuration.expectedInstances(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.expectedInstances(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
