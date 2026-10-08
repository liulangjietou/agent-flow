package io.agentflow.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * 验证完整 XML 合约、两个驱动的列投影，以及与原业务事务共用连接的行为。
 *
 * @author owlzhangfq@gmail.com
 */
class MyBatisMappingContractTest {
    @Test
    void bothAdjustmentRecoveryQueriesUseCompleteColumnIdentifiers() throws Exception {
        DataSource source = source();
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var mapper =
                new SqlSessionTemplate(factory(source))
                        .getMapper(
                                io.agentflow.expense.mapper.ExpensePartialAdjustmentRepositoryMapper
                                        .class);
        Object[] times = {
            java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now())
        };
        assertThatCode(
                        () -> {
                            mapper.dueQuery("budget", times);
                            mapper.dueQuery("accrual", times);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    void everyMapperMethodHasAnXmlStatement() throws Exception {
        var configuration = factory(source()).getConfiguration();
        assertThat(configuration.getMapperRegistry().getMappers()).isNotEmpty();
        for (var mapper : configuration.getMapperRegistry().getMappers()) {
            for (var method : mapper.getDeclaredMethods()) {
                if (method.isDefault()
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                String statement = mapper.getName() + "." + method.getName();
                assertThat(configuration.hasStatement(statement)).as(statement).isTrue();
            }
        }
    }

    @Test
    void projectionsPreserveNullsCaseAndTemporalPrecision() throws Exception {
        var mapper = new SqlSessionTemplate(factory(source())).getMapper(ProjectionMapper.class);
        Instant at = Instant.parse("2026-10-08T08:09:10.123456Z");
        LocalDate date = LocalDate.parse("2026-10-08");
        var row = SqlRows.single(mapper.projection(at.atOffset(ZoneOffset.ofHours(8)), date));
        assertThat(row.containsKey("NULLABLE_NUMBER")).isTrue();
        assertThat(row.getObject("nullable_number", Integer.class)).isNull();
        assertThat(row.getInt("nullable_number")).isZero();
        assertThat(row.getString("nullable_text")).isNull();
        assertThat(row.getTimestamp("recorded_at").toInstant()).isEqualTo(at);
        assertThat(row.getObject("recorded_at", OffsetDateTime.class).toInstant()).isEqualTo(at);
        assertThat(row.getDate("due_on").toLocalDate()).isEqualTo(date);
        assertThat(row.getObject("due_on", LocalDate.class)).isEqualTo(date);
    }

    @Test
    void mapperSharesTheTransactionAndReadsWritesMadeOutsideMyBatis() throws Exception {
        DataSource source = source();
        var jdbc = new JdbcTemplate(source);
        var mapper = new SqlSessionTemplate(factory(source)).getMapper(ProjectionMapper.class);
        jdbc.execute(
                "CREATE TABLE mybatis_mapping_contract(id VARCHAR(32) PRIMARY KEY, value_text"
                        + " VARCHAR(32))");
        try {
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThatThrownBy(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                mapper.insert("one", "first");
                                                assertThat(SqlRows.single(mapper.values("one")))
                                                        .isEqualTo("first");
                                                jdbc.update(
                                                        "UPDATE mybatis_mapping_contract SET"
                                                                + " value_text=? WHERE id=?",
                                                        "second",
                                                        "one");
                                                assertThat(SqlRows.single(mapper.values("one")))
                                                        .isEqualTo("second");
                                                throw new IllegalStateException(
                                                        "Expected rollback");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Expected rollback");
            assertThat(mapper.values("one")).isEmpty();
            assertThatThrownBy(() -> SqlRows.single(mapper.values("one")))
                    .isInstanceOf(EmptyResultDataAccessException.class);
        } finally {
            jdbc.execute("DROP TABLE mybatis_mapping_contract");
        }
    }

    private static DataSource source() {
        String url = System.getenv("AGENTFLOW_MYBATIS_TEST_URL");
        if (url == null || url.isBlank()) {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:mybatis-contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    "");
        }
        return new DriverManagerDataSource(
                url,
                System.getenv("AGENTFLOW_MYBATIS_TEST_USERNAME"),
                System.getenv("AGENTFLOW_MYBATIS_TEST_PASSWORD"));
    }

    private static SqlSessionFactory factory(DataSource source) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);
        MyBatisConfiguration.configureRowTypes(configuration);
        var resolver = new PathMatchingResourcePatternResolver();
        var resources =
                new ArrayList<>(Arrays.asList(resolver.getResources("classpath*:mapper/**/*.xml")));
        resources.add(resolver.getResource("classpath:mybatis-contract/ProjectionMapper.xml"));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setDatabaseIdProvider(new MyBatisConfiguration().databaseIdProvider());
        factory.setMapperLocations(resources.toArray(org.springframework.core.io.Resource[]::new));
        return factory.getObject();
    }

    /**
     * 独立合约夹具，不进入应用的自动 Mapper 扫描。
     *
     * @author owlzhangfq@gmail.com
     */
    interface ProjectionMapper {
        /** 读取可空列与带时区、日期投影。 */
        List<SqlRow> projection(@Param("at") OffsetDateTime at, @Param("date") LocalDate date);

        /** 验证映射写入参与已有事务。 */
        int insert(@Param("id") String id, @Param("value") String value);

        /** 验证重复读取不会返回已过时的事务内缓存。 */
        List<String> values(@Param("id") String id);
    }
}
