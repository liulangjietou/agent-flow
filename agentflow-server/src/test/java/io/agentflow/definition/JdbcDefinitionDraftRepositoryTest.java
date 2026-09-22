package io.agentflow.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用真实数据库校验版本分配、独立快照写入与旧库迁移。
 * @author owlzhangfq@gmail.com
 */
class JdbcDefinitionDraftRepositoryTest {
    private JdbcTemplate jdbc;
    private JdbcDefinitionDraftRepository repository;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcDefinitionDraftRepository(jdbc, new JsonUtil(new ObjectMapper()));
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='APPROVAL_APPLICATION' AND COLUMN_NAME='VERSION'", String.class))
                .isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='APPROVAL_DEFINITION' AND COLUMN_NAME='VERSION'", String.class))
                .isEqualTo("YES");
    }

    @Test
    void keepsMultipleDraftsWithoutConsumingPublishedVersions() {
        DefinitionDraft first = newDraft("travel");
        DefinitionDraft second = newDraft("travel");

        repository.save(first);
        repository.save(second);

        assertThat(repository.findAll("tenant-a", "DRAFT")).hasSize(2);
        assertThat(repository.nextVersion("tenant-a", "travel")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition WHERE version IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void upgradesAnAlreadyMigratedV1SchemaWithoutChangingApplicationVersion() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        JdbcTemplate schema = new JdbcTemplate(dataSource);

        assertThat(schema.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='APPROVAL_DEFINITION' AND COLUMN_NAME='VERSION'", String.class))
                .isEqualTo("NO");
        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(schema.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='APPROVAL_DEFINITION' AND COLUMN_NAME='VERSION'", String.class))
                .isEqualTo("YES");
        assertThat(schema.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='APPROVAL_APPLICATION' AND COLUMN_NAME='VERSION'", String.class))
                .isEqualTo("NO");
    }

    @Test
    void rejectsTwoPublishedSnapshotsCompetingForTheSameVersion() {
        DefinitionDraft first = newDraft("travel");
        DefinitionDraft second = newDraft("travel");
        repository.save(first);
        repository.save(second);
        long firstCandidate = repository.nextVersion("tenant-a", "travel");
        long secondCandidate = repository.nextVersion("tenant-a", "travel");
        first.publish(0, firstCandidate);
        second.publish(0, secondCandidate);
        repository.save(first);

        assertThatThrownBy(() -> repository.save(second))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(repository.findById("tenant-a", second.id()).orElseThrow().status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(repository.nextVersion("tenant-a", "travel")).isEqualTo(2);
    }

    @Test
    void rejectsStaleSnapshotWithoutOverwritingTheWinner() {
        DefinitionDraft initial = newDraft("travel");
        repository.save(initial);
        DefinitionDraft winner = repository.findById("tenant-a", initial.id()).orElseThrow();
        DefinitionDraft stale = repository.findById("tenant-a", initial.id()).orElseThrow();
        winner.update("已保存修改", graph(), 0);
        stale.update("过期修改", graph(), 0);
        repository.save(winner);

        assertThatThrownBy(() -> repository.save(stale))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(repository.findById("tenant-a", initial.id()).orElseThrow().name()).isEqualTo("已保存修改");
    }

    @Test
    void cannotReinsertDeletedDraftThroughAnUpdate() {
        DefinitionDraft draft = newDraft("travel");
        repository.save(draft);
        jdbc.update("DELETE FROM approval_definition WHERE id=?", draft.id().toString());
        draft.update("过期修改", graph(), 0);

        assertThatThrownBy(() -> repository.save(draft))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"));
        assertThat(repository.findById("tenant-a", draft.id())).isEmpty();
    }

    private DefinitionDraft newDraft(String key) {
        return DefinitionDraft.create(UUID.randomUUID(), "tenant-a", key, "出差审批", graph());
    }

    private Graph graph() {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "approve", ""), new Edge("b", "approve", "end", "")));
    }
}
