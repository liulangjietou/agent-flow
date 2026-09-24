package io.agentflow.database;

import javax.sql.DataSource;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * 生产启动仅校验结构；初始化和升级由同一发布包的离线命令负责。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSchemaConfiguration {
    /** 引擎元数据定位与业务迁移使用相同 schema；冲突配置应在启动前修正。 */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> productionEngineSchema() {
        return configuration -> {
            String schema = DatabaseSchemaLifecycle.currentSchema(configuration.getDataSource());
            if (configuration.getDatabaseSchema() != null && !configuration.getDatabaseSchema().equals(schema)) {
                throw new IllegalStateException("Flowable and business migrations must use the same database schema");
            }
            configuration.setDatabaseSchema(schema);
        };
    }

    /** 在 Flyway 初始化及依赖它的引擎启动前拒绝隐式迁移配置。 */
    @Bean
    public FlywayMigrationStrategy productionSchemaValidation(Environment environment, DataSource dataSource) {
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, true)
                || !"false".equals(environment.getProperty("flowable.database-schema-update"))
                || environment.getProperty("flowable.check-process-definitions", Boolean.class, true)) {
            throw new IllegalStateException("Production requires Flyway validation and disabled automatic engine schema updates and process deployment");
        }
        // 即使配置了独立 Flyway 连接，也只能校验应用实际使用的库。
        return ignored -> DatabaseSchemaLifecycle.validateBusiness(DatabaseSchemaLifecycle.flyway(dataSource));
    }
}
