package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Document;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际非空旧库验证当前财务轮次投影和不可变路由依据；SQL 夹具不代表完整审批运行。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSplitRoutingPersistenceTest {
    private static final String TENANT = "split-storage";
    private static final UUID LEGAL = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final Instant AT = Instant.parse("2026-10-04T10:00:00.123456Z");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private ExpenseReport draft, approving, reduced, returned;
    private final UUID definitionId = UUID.randomUUID();
    private static final ExpenseSplitRiskPolicy.Configuration ENABLED = new ExpenseSplitRiskPolicy.Configuration(
            ExpenseSplitRiskPolicy.Mode.ENABLED, new ExpenseSplitRiskPolicy.Rule(7, money("5000")), Set.of("gate"));
    private JdbcExpenseReportRepository reports;
    private JdbcExpenseSplitRoutingRepository routing;
    private TransactionTemplate tx;

    @BeforeEach void nonemptyV115Database() {
        source = new DriverManagerDataSource("jdbc:h2:mem:expense-split-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("115").load().migrate(); jdbc = new JdbcTemplate(source);
        draft = seed("DRAFT", "1000", null); approving = seed("IN_APPROVAL", "4000", null);
        reduced = seed("APPROVED", "6000", "3000"); returned = seed("RETURNED", "2000", null);
    }
    @AfterEach void closeOwnedDatabase() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test void migrationAddsCurrentRoundProjectionAndEvidenceWithoutRewritingAnyLegacyColumn() {
        var before = legacyRows(); upgrade();
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='EXPENSE_REPORT'", String.class))
                .contains("CURRENT_ROUND_NO", "CURRENT_SUBMITTED_AT", "CURRENT_LEGAL_ENTITY_ID", "CURRENT_BASE_CURRENCY");
        assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES", String.class))
                .contains("EXPENSE_SPLIT_ROUTING", "EXPENSE_SPLIT_ROUTING_SOURCE");
        assertThat(legacyRows()).isEqualTo(before);
        assertThat(projection(draft)).containsEntry("CURRENT_ROUND_NO", null).containsEntry("CURRENT_SUBMITTED_AT", null)
                .containsEntry("CURRENT_LEGAL_ENTITY_ID", null).containsEntry("CURRENT_BASE_CURRENCY", null);
        for (var report : List.of(approving, reduced, returned)) {
            assertThat(projection(report)).containsEntry("CURRENT_ROUND_NO", 1).containsEntry("CURRENT_LEGAL_ENTITY_ID", LEGAL.toString())
                    .containsEntry("CURRENT_BASE_CURRENCY", "CNY");
            assertThat(jdbc.queryForObject("SELECT current_submitted_at FROM expense_report WHERE id=?", Timestamp.class, report.id().toString()).toInstant()).isEqualTo(AT);
        }
        assertThat(returned.content().legalEntityId()).isNotEqualTo(LEGAL);
        assertThat(Flyway.configure().dataSource(source).target("116").load().migrate().migrationsExecuted).isZero();
    }

    @Test void reportUpdatesKeepFrozenProjectionWhenReturnedDraftChangesAndAdvanceItOnlyOnResubmission() {
        upgrade();
        assertThat(reports.find(TENANT, returned.id()).orElseThrow().state()).isEqualTo(returned.state());
        long before = returned.version(); freeze(returned, AT.plusSeconds(10));
        tx.executeWithoutResult(ignored -> reports.update(returned, before, "alice", "SUBMIT"));
        assertThat(projection(returned)).containsEntry("CURRENT_ROUND_NO", 2)
                .containsEntry("CURRENT_LEGAL_ENTITY_ID", returned.content().legalEntityId().toString());
        assertThat(reports.find(TENANT, returned.id()).orElseThrow().currentRound().submittedAt()).isEqualTo(AT.plusSeconds(10));
        jdbc.update("UPDATE expense_report SET current_round_no=1 WHERE id=?", returned.id().toString());
        assertThatThrownBy(() -> reports.find(TENANT, returned.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void candidateQueryUsesCurrentApprovedFinancialVersionAndExcludesInactiveOwnAndForeignScopes() {
        upgrade(); var found = candidates(draft, Set.of("TAXI"));
        assertThat(found).extracting(Document::reportId).containsExactlyInAnyOrder(approving.id(), reduced.id());
        var adjusted = found.stream().filter(value -> value.reportId().equals(reduced.id())).findFirst().orElseThrow();
        assertThat(adjusted.financialVersion()).isEqualTo(3); assertThat(adjusted.lines().get(0).approvedGross()).isEqualTo(money("3000"));
        assertThat(candidates(approving, Set.of("TAXI"))).extracting(Document::reportId).containsExactly(reduced.id());
        assertThat(candidates(draft, Set.of("HOTEL"))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates("foreign", "alice", LEGAL, "CNY", draft.id(), AT.minusSeconds(1), AT, Set.of("TAXI")))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates(TENANT, "bob", LEGAL, "CNY", draft.id(), AT.minusSeconds(1), AT, Set.of("TAXI")))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates(TENANT, "alice", UUID.randomUUID(), "CNY", draft.id(), AT.minusSeconds(1), AT, Set.of("TAXI")))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates(TENANT, "alice", LEGAL, "USD", draft.id(), AT.minusSeconds(1), AT, Set.of("TAXI")))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates(TENANT, "alice", LEGAL, "CNY", draft.id(), AT.plusNanos(1000), AT.plusSeconds(1), Set.of("TAXI")))).isEmpty();
        assertThat(tx.<List<Document>>execute(ignored -> routing.candidates(TENANT, "alice", LEGAL, "CNY", draft.id(), AT.minusSeconds(1), AT.minusNanos(1000), Set.of("TAXI")))).isEmpty();
    }

    @Test void activeSourceWithMissingProjectionCannotDisappearFromRiskCheck() {
        upgrade(); jdbc.update("UPDATE expense_report SET current_round_no=NULL,current_submitted_at=NULL,current_legal_entity_id=NULL,current_base_currency=NULL WHERE id=?", approving.id().toString());
        assertThatThrownBy(() -> candidates(draft, Set.of("TAXI"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void participantLimitFailsWithoutTruncatingAndUnrelatedCategoriesDoNotConsumeIt() {
        for (int i = 0; i < 998; i++) seed("IN_APPROVAL", "1", null);
        upgrade();
        assertThat(candidates(draft, Set.of("HOTEL"))).isEmpty();
        assertThatThrownBy(() -> candidates(draft, Set.of("TAXI"))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("EXPENSE_SPLIT_SOURCE_LIMIT");
        jdbc.update("UPDATE approval_application SET status='WITHDRAWN' WHERE id=?", approving.applicationId().toString());
        assertThat(candidates(draft, Set.of("TAXI"))).hasSize(999);
    }

    @Test void frozenEvidenceSurvivesReductionWithdrawalAndMissingPrimaryRoundUntilEngineAppendsIt() {
        upgrade(); var snapshot = prepared(); tx.executeWithoutResult(ignored -> routing.save(snapshot));
        assertThat(routing.find(TENANT, draft.id(), 1)).contains(snapshot);
        assertThat(routing.findByApplication(TENANT, draft.applicationId(), 1)).contains(snapshot);
        assertThat(routing.find("foreign", draft.id(), 1)).isEmpty(); assertThat(routing.find(TENANT, draft.id(), 2)).isEmpty();
        assertThat(snapshot.assessment().routingAmount()).isEqualTo(money("8000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, draft.applicationId().toString())).isZero();
        long version = reduced.version(); reduced.reduce(version, List.of(new ExpenseReport.Reduction(1, money("1000"), money("0"))), "finance", "CORRECTION", "再次核減", AT.plusSeconds(2));
        tx.executeWithoutResult(ignored -> reports.update(reduced, version, "finance", "REDUCE"));
        jdbc.update("UPDATE approval_application SET status='WITHDRAWN',version=version+1 WHERE id=?", approving.applicationId().toString());
        assertThat(candidates(draft, Set.of("TAXI"))).singleElement().satisfies(value -> assertThat(value.lines().get(0).approvedGross()).isEqualTo(money("1000")));
        assertThat(routing.find(TENANT, draft.id(), 1)).contains(snapshot);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> routing.save(snapshot))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"application-version", "financial-version", "application-status", "definition-status"})
    void preparationRejectsChangedPrimaryOrUnpublishedDefinition(String changed) {
        upgrade(); var snapshot = prepared();
        switch (changed) {
            case "application-version" -> jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", draft.applicationId().toString());
            case "financial-version" -> jdbc.update("UPDATE expense_report SET version=version+1 WHERE id=?", draft.id().toString());
            case "application-status" -> jdbc.update("UPDATE approval_application SET status='APPROVED' WHERE id=?", draft.applicationId().toString());
            case "definition-status" -> jdbc.update("UPDATE approval_definition SET status='DRAFT' WHERE id=?", definitionId.toString());
            default -> throw new AssertionError(changed);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> routing.save(snapshot))).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_split_routing", Integer.class)).isZero();
    }

    @Test void preparationRequiresTransactionAndLaterFailureRollsBackParentAndSources() {
        upgrade(); var snapshot = prepared();
        assertThatThrownBy(() -> routing.save(snapshot)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> { routing.save(snapshot); throw new IllegalStateException("downstream-failure"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("downstream-failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_split_routing", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_split_routing_source", Integer.class)).isZero();
    }

    @Test void missingSourceRoundAndForeignReportCannotCreateEvidenceAndReadRejectsAlteredIndexes() {
        upgrade(); var snapshot = prepared();
        jdbc.update("DELETE FROM approval_submission_round WHERE application_id=?", approving.applicationId().toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> routing.save(snapshot))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_split_routing", Integer.class)).isZero();
        insertRound(approving);
        tx.executeWithoutResult(ignored -> routing.save(snapshot));
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_split_routing SET financial_version=999 WHERE report_id=?", draft.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_split_routing SET tenant_id='foreign' WHERE report_id=?", draft.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE expense_split_routing_source SET source_application_version=99 WHERE report_id=? AND ordinal=1", draft.id().toString());
        assertThatThrownBy(() -> routing.find(TENANT, draft.id(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unknownDisabledAndEnabledModesAreDistinctAndTamperedAggregateCannotBeRestored() {
        upgrade(); var snapshot = prepared();
        var bad = new ExpenseSplitRiskEvidence.Assessment(snapshot.assessment().windowFrom(), AT, money("1000"), money("9000"),
                snapshot.assessment().categories(), snapshot.assessment().sources());
        assertThatThrownBy(() -> new ExpenseSplitRoutingSnapshot(1, definitionId, "split-fixture", 1, ENABLED, snapshot.primary(), bad))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("EXPENSE_SPLIT_SNAPSHOT_INVALID");
        for (var mode : List.of(ExpenseSplitRiskPolicy.Mode.UNCONFIGURED, ExpenseSplitRiskPolicy.Mode.DISABLED)) {
            var value = new ExpenseSplitRoutingSnapshot(1, definitionId, "split-fixture", 1,
                    new ExpenseSplitRiskPolicy.Configuration(mode, null, Set.of()), snapshot.primary(), null);
            assertThat(json.read(json.write(value), ExpenseSplitRoutingSnapshot.class)).isEqualTo(value);
            assertThat(value.sources()).containsExactly(snapshot.primary());
            tx.executeWithoutResult(transaction -> {
                var graph = new Graph(List.of(new Node("start", "开始", NodeType.START,
                        mode == ExpenseSplitRiskPolicy.Mode.DISABLED ? Map.of("expenseSplitRisk", "DISABLED") : Map.of())), List.of());
                jdbc.update("UPDATE approval_definition SET graph_json=? WHERE id=?", json.write(graph), definitionId.toString());
                routing.save(value);
                assertThat(routing.find(TENANT, draft.id(), 1)).contains(value);
                assertThat(jdbc.queryForObject("SELECT mode FROM expense_split_routing WHERE report_id=?", String.class, draft.id().toString())).isEqualTo(mode.name());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_split_routing_source", Integer.class)).isZero();
                transaction.setRollbackOnly();
            });
        }
    }

    private void upgrade() {
        Flyway.configure().dataSource(source).target("116").load().migrate();
        var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        reports = new JdbcExpenseReportRepository(jdbc, json);
        var proxy = new ProxyFactory(new JdbcExpenseSplitRoutingRepository(jdbc, json));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        routing = (JdbcExpenseSplitRoutingRepository) proxy.getProxy();
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of("expenseSplitRisk", "ENABLED", "expenseSplitWindowDays", "7", "expenseSplitThreshold", "5000", "expenseSplitCurrency", "CNY")),
                new Node("gate", "业务金额", NodeType.EXCLUSIVE_GATEWAY, Map.of("expenseSplitRouting", "AGGREGATE_AMOUNT"))), List.of());
        jdbc.update("INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json) VALUES(?,?,'split-fixture','合成存储定义',1,1,'PUBLISHED',?)", definitionId.toString(), TENANT, json.write(graph));
    }
    private List<Document> candidates(ExpenseReport primary, Set<String> categories) {
        return tx.execute(ignored -> routing.candidates(TENANT, "alice", LEGAL, "CNY", primary.id(), AT.minusSeconds(7 * 24 * 3600), AT, categories));
    }
    private ExpenseSplitRoutingSnapshot prepared() {
        freeze(draft, AT);
        tx.executeWithoutResult(ignored -> reports.update(draft, 1, "alice", "SUBMIT"));
        var primary = document(draft, ApplicationStatus.DRAFT);
        var candidates = List.of(document(approving, ApplicationStatus.IN_APPROVAL), document(reduced, ApplicationStatus.APPROVED));
        return new ExpenseSplitRoutingSnapshot(1, definitionId, "split-fixture", 1, ENABLED, primary,
                ExpenseSplitRiskEvidence.assess(ENABLED.rule(), primary, candidates, AT));
    }
    private static Document document(ExpenseReport report, ApplicationStatus status) {
        var round = report.currentRound();
        return new Document(report.id(), report.applicationId(), 5, report.version(), round.roundNo(),
                new ExpenseSplitRiskEvidence.Scope(TENANT, "alice", round.content().legalEntityId(), round.baseCurrency()), round.submittedAt(), status,
                round.approvedLines().stream().map(line -> new ExpenseSplitRiskEvidence.Line(line.lineNo(), "TAXI", line.gross())).toList());
    }
    private Map<String, Object> projection(ExpenseReport report) {
        return jdbc.queryForMap("SELECT current_round_no,current_submitted_at,current_legal_entity_id,current_base_currency FROM expense_report WHERE id=?", report.id().toString());
    }
    private List<List<Map<String, Object>>> legacyRows() {
        return List.of(jdbc.queryForList("SELECT id,tenant_id,application_id,business_type,employee_id,version,state_json,created_at,updated_at FROM expense_report ORDER BY id"),
                jdbc.queryForList("SELECT * FROM expense_report_revision ORDER BY report_id,financial_version"),
                jdbc.queryForList("SELECT * FROM approval_application ORDER BY id"),
                jdbc.queryForList("SELECT * FROM approval_submission_round ORDER BY application_id,round_no"));
    }
    private ExpenseReport seed(String status, String amount, String reduction) {
        var report = ExpenseReport.draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), "alice", content(LEGAL, amount));
        var revisions = new ArrayList<ExpenseReport.State>(); revisions.add(report.state());
        if (!status.equals("DRAFT")) {
            freeze(report, AT); revisions.add(report.state());
            if (reduction != null) {
                report.reduce(report.version(), List.of(new ExpenseReport.Reduction(1, money(reduction), money("0"))),
                        "finance", "CORRECTION", "合成核減夹具", AT.plusSeconds(1)); revisions.add(report.state());
            }
            if (status.equals("RETURNED")) {
                report.revise(report.version(), content(UUID.randomUUID(), amount)); revisions.add(report.state());
            }
        }
        jdbc.update("""
                INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id)
                VALUES(?,? ,?,'split-fixture',1,'alice','跨单存储夹具','{}',?,1,5,'EXPENSE',?)
                """, report.applicationId().toString(), TENANT, "SPLIT-" + report.id(), status, report.id().toString());
        jdbc.update("INSERT INTO expense_report(id,tenant_id,application_id,employee_id,version,state_json) VALUES(?,?,?,'alice',?,?)",
                report.id().toString(), TENANT, report.applicationId().toString(), report.version(), json.write(report.state()));
        for (var state : revisions) jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) VALUES(?,?,?,'fixture','SEED',?)",
                TENANT, report.id().toString(), state.version(), json.write(state));
        if (!status.equals("DRAFT")) insertRound(report);
        return report;
    }
    private void insertRound(ExpenseReport report) {
        String status = jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, report.applicationId().toString());
        boolean concluded = !status.equals("IN_APPROVAL");
        jdbc.update("""
                INSERT INTO approval_submission_round(tenant_id,application_id,round_no,process_instance_id,definition_version,title,payload_json,submitted_by,submitted_at,status,completed_by,completed_at)
                VALUES(?,?,1,?,1,'存储原轮次','{}','alice',?,?,?,?)
                """, TENANT, report.applicationId().toString(), "fixture-" + report.id(), Timestamp.from(AT), status,
                concluded ? "approver" : null, concluded ? Timestamp.from(AT.plusSeconds(1)) : null);
    }
    private static ExpenseContent content(UUID legal, String amount) {
        var line = new ExpenseLine(1, "TAXI", LocalDate.parse("2026-10-04"), null, "CITY", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                money(amount), money("0"), List.of(), null, List.of(new CostAllocation("COST", null, money(amount))), "合成费用", null);
        return new ExpenseContent(legal, ExpenseContent.Type.DAILY, "存储验证", List.of(line), List.of());
    }
    private static void freeze(ExpenseReport report, Instant at) {
        var gross = report.content().lines().get(0).claimedGross();
        var policy = new ExpensePolicySnapshot(UUID.randomUUID(), 1, gross, gross, ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "policy-fixture", "stored-fact");
        report.freeze(report.version(), report.rounds().size() + 1, "CNY", new EmployeeAccountSnapshot(report.content().legalEntityId(), "alice", "account", "***1234", "a".repeat(64), "v1"),
                Map.of(1, new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "rate-fixture", LocalDate.parse("2026-10-04")), policy, money("0"))), "alice", at);
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
