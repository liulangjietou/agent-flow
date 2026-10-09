package io.agentflow.mybatis;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 仅为真实存在的数据库方言差异配置标识，业务 Mapper 共用平台数据源和事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
public class MyBatisConfiguration {
    /** 内部列投影与直接 LocalDate 查询使用相同的日期语义。 */
    @Bean
    public org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer rowProjectionTypes() {
        return MyBatisConfiguration::configureRowTypes;
    }

    /** 测试工厂和应用工厂共用真实日期映射，避免旧历法日期在恢复时改变。 */
    public static void configureRowTypes(org.apache.ibatis.session.Configuration configuration) {
        configuration
                .getTypeHandlerRegistry()
                .register(
                        java.sql.Date.class,
                        org.apache.ibatis.type.JdbcType.DATE,
                        CalendarDateTypeHandler.class);
    }

    /** PostgreSQL 的代理行使用 NO KEY UPDATE，H2 使用 FOR UPDATE。 */
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        var provider = new VendorDatabaseIdProvider();
        var vendors = new Properties();
        vendors.setProperty("PostgreSQL", "postgresql");
        vendors.setProperty("H2", "h2");
        provider.setProperties(vendors);
        return provider;
    }
}
