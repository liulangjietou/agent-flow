package io.agentflow.mybatis;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

import javax.sql.DataSource;

/**
 * 持久化测试使用真实 XML Mapper，共用测试原有的数据源和 Spring 事务。
 *
 * @author owlzhangfq@gmail.com
 */
public final class MyBatisTestSupport {
    private static final Map<DataSource, WeakReference<SqlSessionTemplate>> SESSIONS =
            new WeakHashMap<>();

    private MyBatisTestSupport() {}

    /** 为同一测试数据源复用映射配置，事务仍由测试的事务管理器控制。 */
    public static synchronized <T> T mapper(DataSource source, Class<T> mapperType) {
        return mapper(source, mapperType, UnaryOperator.identity());
    }

    /** 旧库夹具在解析 XML 前适配历史列，运行时业务 Mapper 始终使用原始 XML。 */
    public static synchronized <T> T mapper(
            DataSource source, Class<T> mapperType, UnaryOperator<String> fixtureSql) {
        var reference = SESSIONS.get(source);
        var template = reference == null ? null : reference.get();
        if (template == null) {
            template = session(source);
            SESSIONS.put(source, new WeakReference<>(template));
        }
        var configuration = template.getConfiguration();
        if (!configuration.hasMapper(mapperType)) {
            String resource =
                    "mapper/"
                            + mapperType
                                    .getPackageName()
                                    .substring("io.agentflow.".length())
                                    .replace(".mapper", "")
                                    .replace('.', '/')
                            + "/"
                            + mapperType.getSimpleName()
                            + ".xml";
            try (var input = mapperType.getClassLoader().getResourceAsStream(resource)) {
                if (input == null)
                    throw new IllegalStateException("Missing test mapper: " + resource);
                String xml =
                        fixtureSql.apply(
                                new String(
                                        input.readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8));
                new XMLMapperBuilder(
                                new java.io.ByteArrayInputStream(
                                        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                configuration,
                                resource,
                                configuration.getSqlFragments())
                        .parse();
            } catch (java.io.IOException error) {
                throw new IllegalStateException(
                        "Test mapper could not be read: " + resource, error);
            }
        }
        return template.getMapper(mapperType);
    }

    private static SqlSessionTemplate session(DataSource source) {
        try {
            var config = new org.apache.ibatis.session.Configuration();
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setCacheEnabled(false);
            config.setCallSettersOnNulls(true);
            config.setReturnInstanceForEmptyRow(true);
            MyBatisConfiguration.configureRowTypes(config);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            factory.setDatabaseIdProvider(new MyBatisConfiguration().databaseIdProvider());
            return new SqlSessionTemplate(factory.getObject());
        } catch (Exception error) {
            throw new IllegalStateException("MyBatis test mappings could not be loaded", error);
        }
    }
}
