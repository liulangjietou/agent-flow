package io.agentflow.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * V109 已有组织事实和未处理来源批次升级后不变，计划和应用记录只新增空表。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncPlanMigrationTest {
    @Test void preservesNonemptyOrganizationSourceAndReceivedBatchAcrossV110() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:sync-plan-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(data).target("109").load().migrate(); var jdbc = new JdbcTemplate(data);
        var json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        var organization = new OrganizationService(new JdbcOrganizationRepository(jdbc, json)); var sync = new JdbcOrganizationSyncRepository(jdbc, json);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(data)); var actor = new Actor("tenant", "admin", Set.of("ADMIN"));
        Instant at = Instant.parse("2026-10-04T00:00:00Z");
        var batch = new OrganizationSyncBatch(new OrganizationSyncBatch.Context(UUID.randomUUID(), "tenant", "hr", 0, "1".repeat(64), "admin", at, null));
        try {
            tx.executeWithoutResult(ignored -> {
                organization.initialize(actor); organization.createPerson(actor, "subject", "旧人员", true, true);
                sync.register(new OrganizationSyncSource("tenant", "hr", 0, 1, null, "admin", at)); createV109(jdbc, json, batch);
                batch.start(1, at.plusSeconds(1), at.plusSeconds(30)); sync.update(batch, 1);
                batch.receive(2, new OrganizationSyncDelta("hr", 0, 1, List.of(), List.of(new OrganizationSyncDelta.Person(
                        new OrganizationSyncKey(OrganizationSyncKey.Kind.PERSON, "source-person"), "subject", "来源人员", true, true)), List.of()), at.plusSeconds(2)); sync.update(batch, 2);
            });
            var tables = List.of("organization_directory", "organization_person", "organization_change", "organization_sync_source", "organization_sync_batch", "organization_sync_transition");
            var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
            assertThat(before).allSatisfy(rows -> assertThat(rows).isNotEmpty());
            var history = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
            assertThat(Flyway.configure().dataSource(data).target("110").load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).usingRecursiveComparison().isEqualTo(before);
            var after = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
            assertThat(after).hasSize(history.size() + 1); assertThat(after.subList(0, history.size())).isEqualTo(history);
            assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state()).isEqualTo(batch.state());
            for (String table : List.of("organization_sync_plan", "organization_sync_application")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
            assertThat(Flyway.configure().dataSource(data).target("110").load().migrate().migrationsExecuted).isZero();
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    private void createV109(JdbcTemplate jdbc, JsonUtil json, OrganizationSyncBatch batch) {
        var context = batch.context();
        jdbc.update("""
                INSERT INTO organization_sync_batch(tenant_id,id,source_key,after_revision,requested_by,status,version,
                    pending_tenant_id,context_json,state_json,created_at)
                VALUES(?,?,?,?,?,'QUEUED',1,?,?,?,?)
                """, context.tenantId(), context.id().toString(), context.sourceKey(), context.afterRevision(), context.requestedBy(),
                context.tenantId(), json.write(context), json.write(batch.state()), java.sql.Timestamp.from(context.createdAt()));
        jdbc.update("INSERT INTO organization_sync_transition(tenant_id,batch_id,batch_version,status,state_json) VALUES(?,?,1,'QUEUED',?)",
                context.tenantId(), context.id().toString(), json.write(batch.state()));
    }
}
