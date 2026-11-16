package io.agentflow.config;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产连接池参数必须写明并可由部署环境调整：默认值取容量基线实测的 10 个连接，
 * 副本数 × 连接数需要运维按数据库 max_connections 规划，不能依赖驱动隐式默认。
 * @author owlzhangfq@gmail.com
 */
class ProductionDataSourcePoolTest {
    private static final String PREFIX = "spring.datasource.hikari.";

    @Test void productionPoolUsesTheMeasuredBaselineUnlessDeploymentOverridesIt() throws IOException {
        var environment = production();
        assertThat(environment.getProperty(PREFIX + "maximum-pool-size", Integer.class)).isEqualTo(10);
        assertThat(environment.getProperty(PREFIX + "minimum-idle", Integer.class)).isEqualTo(10);
        assertThat(environment.getProperty(PREFIX + "connection-timeout", Long.class)).isEqualTo(30_000L);
        assertThat(environment.getProperty(PREFIX + "max-lifetime", Long.class)).isEqualTo(1_800_000L);
        assertThat(environment.getProperty(PREFIX + "pool-name")).isEqualTo("agentflow");

        environment.setProperty("AGENTFLOW_DATASOURCE_POOL_SIZE", "24");
        environment.setProperty("AGENTFLOW_DATASOURCE_MAX_LIFETIME_MS", "600000");
        assertThat(environment.getProperty(PREFIX + "maximum-pool-size", Integer.class)).isEqualTo(24);
        assertThat(environment.getProperty(PREFIX + "minimum-idle", Integer.class)).isEqualTo(24);
        assertThat(environment.getProperty(PREFIX + "max-lifetime", Long.class)).isEqualTo(600_000L);
    }

    private static MockEnvironment production() throws IOException {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }
}
