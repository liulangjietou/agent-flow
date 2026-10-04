package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * 真实 JDBC 原修订校验、竞争写入与回滚；合成来源不代表组织资格或流程引擎验收。
 * @author owlzhangfq@gmail.com
 */
class ExpenseProjectApprovalPersistenceTest {
    private static final String TENANT = "project-storage";
    private static final UUID LEGAL = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);
    private static final Instant AT = Instant.parse("2026-10-04T10:00:00.123456Z");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private final UUID definitionId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcExpenseReportRepository reports;
    private JdbcExpensePrecheckRepository prechecks;
    private JdbcExpenseProjectApprovalRepository projects;
    private ExpenseReport report;
    private ExpensePrecheckJob checked;
    private ExpenseProjectApprovalSnapshot snapshot;
    private String url;

    @BeforeEach void prepareOriginalReadyPrecheckAndFrozenReport() {
        url = "jdbc:h2:mem:project-storage-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var source = new DriverManagerDataSource(url, "sa", ""); Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source); var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        reports = new JdbcExpenseReportRepository(jdbc, json); prechecks = new JdbcExpensePrecheckRepository(jdbc, json);
        var proxy = new ProxyFactory(new JdbcExpenseProjectApprovalRepository(jdbc, json));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        projects = (JdbcExpenseProjectApprovalRepository) proxy.getProxy();
        var line = new ExpenseLine(1, "OFFICE", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("0"), List.of(), null,
                List.of(new CostAllocation("IT", "A", money("40")), new CostAllocation("IT", "B", money("60"))), "合成项目费用", null);
        report = ExpenseReport.draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), "alice",
                new ExpenseContent(LEGAL, ExpenseContent.Type.DAILY, "项目存储验证", List.of(line), List.of()));
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'project-fixture',1,'alice','合成申请','{}','DRAFT',1,1,'EXPENSE',?)",
                report.applicationId().toString(), TENANT, "PROJECT-" + report.id(), report.id().toString());
        jdbc.update("INSERT INTO approval_definition(id,tenant_id,process_key,name,version,revision,status,graph_json) VALUES(?,?,'project-fixture','合成存储定义',1,1,'PUBLISHED','{}')", definitionId.toString(), TENANT);
        tx.executeWithoutResult(ignored -> reports.create(report, "alice"));
        var context = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, LEGAL, "合成法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
        var input = new ExpensePrecheckJob.Input(UUID.randomUUID(), TENANT, report.id(), report.applicationId(), "alice", 1, 1, 1, 1, context, DATE, "a".repeat(64));
        var account = new EmployeeAccountSnapshot(LEGAL, "alice", "private-ref", "****1234", "a".repeat(64), "v1");
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "rate-v1", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic", "synthetic"), money("0"));
        var preview = ExpenseReport.restore(report.state()); preview.freeze(1, 1, "CNY", account, Map.of(1, assessment), "alice", AT.minusSeconds(2));
        var owners = new ExpenseProjectOwners("catalog-v1", LEGAL, List.of(new FinanceCatalog.Project(LEGAL, "A", "项目A", "manager"),
                new FinanceCatalog.Project(LEGAL, "B", "项目B", "manager")));
        var budget = new BudgetPrecheckPort.Assessment(BudgetPrecheckPort.Request.from(preview, DATE), "budget-v1", AT.minusSeconds(2), AT.plusSeconds(60));
        var evidence = new ExpensePrecheckEvidence("catalog-v1", new FinanceCatalog.LegalEntity(LEGAL, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai"), DATE,
                budget, preview.currentRound(), List.of(), List.of(), AT.plusSeconds(60), null, null, owners);
        var queued = ExpensePrecheckJob.queue(input, AT.minusSeconds(10)); var running = queued.start(AT.minusSeconds(3), AT.plusSeconds(15));
        checked = running.finish(new ExpensePrecheckJob.Result(evidence, List.of()), AT.minusSeconds(1));
        tx.executeWithoutResult(ignored -> { prechecks.create(queued); prechecks.update(running); prechecks.update(checked); });
        report.freeze(1, 1, "CNY", account, Map.of(1, assessment), "alice", AT);
        tx.executeWithoutResult(ignored -> {
            reports.update(report, 1, "alice", "SUBMIT");
            jdbc.update("UPDATE approval_application SET version=2 WHERE id=?", report.applicationId().toString());
        });
        snapshot = ExpenseProjectApprovalSnapshot.capture(report, 2, definitionId, "project-fixture", 1, "projects", checked);
    }

    @AfterEach void closeOwnedDatabase() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test void originalEvidenceSurvivesFreshConnectionsReductionAndLaterPrechecks() {
        save();
        var reopened = new JdbcExpenseProjectApprovalRepository(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), json);
        assertThat(reopened.find(TENANT, report.id(), 1)).contains(snapshot);
        assertThat(reopened.findByApplication(TENANT, report.applicationId(), 1)).contains(snapshot);
        assertThat(reopened.find("another-tenant", report.id(), 1)).isEmpty();
        assertThat(reopened.find(TENANT, report.id(), 2)).isEmpty();
        assertThat(reopened.find(TENANT, UUID.randomUUID(), 1)).isEmpty();
        assertThat(snapshot.owners().projects()).extracting(FinanceCatalog.Project::code).containsExactly("A", "B");
        assertThat(snapshot.owners().subjects()).containsExactly("manager");
        report.reduce(2, List.of(new ExpenseReport.Reduction(1, money("50"), money("0"))), "finance", "CORRECTION", "合成核减", AT.plusSeconds(1));
        tx.executeWithoutResult(ignored -> reports.update(report, 2, "finance", "REDUCE"));
        var old = checked.input();
        var next = new ExpensePrecheckJob.Input(UUID.randomUUID(), TENANT, report.id(), report.applicationId(), "alice", 4, 3, 2, 2, old.initiator(), DATE, old.targetDigest());
        tx.executeWithoutResult(ignored -> prechecks.create(ExpensePrecheckJob.queue(next, AT.plusSeconds(2))));
        assertThat(prechecks.latestId(TENANT, report.id())).contains(next.id());
        assertThat(reopened.find(TENANT, report.id(), 1)).contains(snapshot);
        assertThat(snapshot.financialVersion()).isEqualTo(2);
    }

    @Test void missingRowsStayUnrecordedAndWriteRequiresTheEnclosingSubmissionTransaction() {
        assertThat(projects.find(TENANT, report.id(), 1)).isEmpty();
        assertThatThrownBy(() -> projects.save(snapshot)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> { projects.save(snapshot); throw new IllegalStateException("downstream-failure"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("downstream-failure");
        assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"application-version", "financial-version", "application-status", "definition-status", "round", "precheck-financial-version", "precheck-application-version"})
    void preparationRejectsChangedBindingsWithoutKeepingPartialRows(String changed) {
        switch (changed) {
            case "application-version" -> jdbc.update("UPDATE approval_application SET version=3 WHERE id=?", report.applicationId().toString());
            case "financial-version" -> jdbc.update("UPDATE expense_report SET version=3 WHERE id=?", report.id().toString());
            case "application-status" -> jdbc.update("UPDATE approval_application SET status='APPROVED' WHERE id=?", report.applicationId().toString());
            case "definition-status" -> jdbc.update("UPDATE approval_definition SET status='DRAFT' WHERE id=?", definitionId.toString());
            case "round" -> jdbc.update("UPDATE expense_report SET current_round_no=2 WHERE id=?", report.id().toString());
            case "precheck-financial-version" -> jdbc.update("UPDATE expense_precheck_job SET financial_version=2 WHERE id=?", checked.input().id().toString());
            case "precheck-application-version" -> jdbc.update("UPDATE expense_precheck_job SET application_version=2 WHERE id=?", checked.input().id().toString());
            default -> throw new AssertionError(changed);
        }
        assertThatThrownBy(this::save).isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).code()).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "precheck-input", "financial-content", "submitted-time", "definition-version", "has-projects", "catalog-version"})
    void restoreRejectsForeignOrTamperedOriginalEvidence(String changed) {
        save();
        switch (changed) {
            case "owner" -> alter("expense_project_approval", "snapshot_json", "report_id", report.id(), root ->
                    ((ObjectNode) root.at("/owners/projects/0")).put("ownerSubject", "replacement"));
            case "precheck-input" -> alter("expense_precheck_revision", "state_json", "job_id", checked.input().id(), root ->
                    ((ObjectNode) root.path("input")).put("applicationId", UUID.randomUUID().toString()));
            case "financial-content" -> alter("expense_report_revision", "state_json", "report_id", report.id(), root -> root.put("applicationId", UUID.randomUUID().toString()));
            case "submitted-time" -> jdbc.update("UPDATE expense_project_approval SET submitted_at=DATEADD('SECOND',1,submitted_at)");
            case "definition-version" -> jdbc.update("UPDATE expense_project_approval SET definition_version=2");
            case "has-projects" -> jdbc.update("UPDATE expense_project_approval SET has_projects=FALSE");
            case "catalog-version" -> jdbc.update("UPDATE expense_project_approval SET catalog_version='other-version'");
            default -> throw new AssertionError(changed);
        }
        assertThatThrownBy(() -> projects.find(TENANT, report.id(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test void originallyReadyEvidenceIsCheckedBeforeItsReferenceCanBeSaved() {
        alter("expense_precheck_revision", "state_json", "job_id", checked.input().id(), root -> {
            if (root.path("status").asText().equals("READY")) ((ObjectNode) root.at("/result/evidence/projectOwners/projects/0")).put("ownerSubject", "replacement");
        });
        assertThatThrownBy(this::save).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test void conflictingConcurrentPreparationsCreateOneImmutableRow() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<String> attempt = () -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start-timeout");
            try { save(); return "CREATED"; } catch (DataIntegrityViolationException duplicate) { return "DUPLICATE"; } };
        try {
            var first = executor.submit(attempt); var second = executor.submit(attempt); assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("CREATED", "DUPLICATE");
            assertThat(count()).isEqualTo(1); assertThat(projects.find(TENANT, report.id(), 1)).contains(snapshot);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void identityConstraintsAndConstructorPreventRebindingToAnUnknownRevisionOrRound() {
        save();
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_project_approval SET financial_version=999")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_project_approval SET precheck_version=1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE expense_project_approval SET tenant_id='another-tenant'")).isInstanceOf(DataIntegrityViolationException.class);
        for (var field : List.of("ruleVersion", "precheckVersion", "applicationVersion", "financialVersion", "definitionVersion")) {
            var raw = json.read(json.write(snapshot), ObjectNode.class); raw.put(field, 0);
            assertThatThrownBy(() -> json.read(json.write(raw), ExpenseProjectApprovalSnapshot.class)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> ExpenseProjectApprovalSnapshot.capture(report, 3, definitionId, "project-fixture", 1, "projects", checked))
                .isInstanceOf(DomainException.class);
        var raw = json.read(json.write(snapshot), ObjectNode.class); ((ObjectNode) raw.path("precheck")).put("roundNo", 2);
        var wrongRound = json.read(json.write(raw), ExpenseProjectApprovalSnapshot.class);
        assertThat(wrongRound.matches(report, checked)).isFalse();
    }

    private void save() { tx.executeWithoutResult(ignored -> projects.save(snapshot)); }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM expense_project_approval", Integer.class); }
    private void alter(String table, String column, String idColumn, UUID id, Consumer<ObjectNode> change) {
        String versionColumn = table.equals("expense_report_revision") ? "financial_version" : table.equals("expense_precheck_revision") ? "version" : "round_no";
        var rows = jdbc.queryForList("SELECT " + versionColumn + "," + column + " FROM " + table + " WHERE " + idColumn + "=?", id.toString());
        for (var row : rows) {
            var value = json.read(row.get(column.toUpperCase()).toString(), ObjectNode.class); change.accept(value);
            jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE " + idColumn + "=? AND " + versionColumn + "=?", json.write(value), id.toString(), row.get(versionColumn.toUpperCase()));
        }
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
