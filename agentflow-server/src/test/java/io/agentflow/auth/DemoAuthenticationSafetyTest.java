package io.agentflow.auth;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 演示账号使用公开固定口令：未显式开启时必须关闭，生产配置下即使被覆盖开启也拒绝启动。
 * @author owlzhangfq@gmail.com
 */
class DemoAuthenticationSafetyTest {
    private static final Duration TTL = Duration.ofHours(12);

    @Test void demoLoginIsOffUnlessTheEnvironmentAsksForIt() throws IOException {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        assertThat(environment.getProperty("agentflow.auth.demo-enabled", Boolean.class)).isFalse();
        environment.setProperty("AGENTFLOW_DEMO_AUTH", "true");
        assertThat(environment.getProperty("agentflow.auth.demo-enabled", Boolean.class)).isTrue();
    }

    @Test void productionProfileRefusesToStartWithDemoLoginOverriddenOn() {
        for (var profile : new String[] {"prod", "production"}) {
            var environment = new MockEnvironment(); environment.setActiveProfiles(profile);
            assertThatThrownBy(() -> new AuthService(true, "demo", TTL, environment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod profile");
            assertThat(new AuthService(false, "demo", TTL, environment).options("demo")).isEmpty();
        }
    }

    @Test void nonProductionProfilesKeepDemoLoginAvailable() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("demo");
        assertThat(new AuthService(true, "demo", TTL, environment).options("demo")).isNotEmpty();
    }
}
