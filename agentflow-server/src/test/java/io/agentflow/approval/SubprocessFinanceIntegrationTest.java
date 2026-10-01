package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.budget.BudgetAdjustmentCheckWorker;
import io.agentflow.budget.BudgetAdjustmentContent;
import io.agentflow.budget.BudgetAdjustmentFormContract;
import io.agentflow.budget.BudgetAdjustmentRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionDeploymentPort;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.expense.AdvanceRequestCheckWorker;
import io.agentflow.expense.AdvanceRequestContent;
import io.agentflow.expense.AdvanceRequestFormContract;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpensePlanCheckWorker;
import io.agentflow.expense.ExpensePlanContent;
import io.agentflow.expense.ExpensePlanFormContract;
import io.agentflow.expense.ExpensePlanRepository;
import io.agentflow.expense.ExpenseRequestRepository;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import io.agentflow.procurement.JdbcProcurementPayableReservationRepository;
import io.agentflow.procurement.ProcurementPaymentCheckWorker;
import io.agentflow.procurement.ProcurementPaymentContent;
import io.agentflow.procurement.ProcurementPaymentFormContract;
import io.agentflow.procurement.ProcurementPaymentRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 四类结构化财务父申请通过专用预检和提交启动固定版本子审批，验证真实引擎与财务事务边界。
 * 父定义仅由内部夹具经正式部署适配器生成；本测试不代表公开子流程发布已开放或企业联调已完成。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.finance-gateway.enabled=true",
        "agentflow.timers.enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.vouchers.preparation-worker-enabled=false", "agentflow.vouchers.worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false", "agentflow.expenses.precheck-worker-enabled=false",
        "agentflow.advance-requests.precheck-worker-enabled=false", "agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.budget-adjustments.precheck-worker-enabled=false", "agentflow.procurement-payments.precheck-worker-enabled=false",
        "agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubprocessFinanceIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN"));
    private static final SubprocessFinanceGatewayFixture GATEWAY = new SubprocessFinanceGatewayFixture();
    private static final Path FILES = Path.of("/fyoung/tmp/agentflow-subprocess-finance-" + UUID.randomUUID());
    private static final String SYSTEM_ACTOR = "system:subprocess";
    private UUID entity;
    private UUID appointment;
    private UUID manager;
    private UUID finance;

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationService organization;
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired DefinitionDeploymentPort deployment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired SubprocessCallRepository calls;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired ExpensePlanRepository plans;
    @Autowired ExpenseRequestRepository credits;
    @Autowired AdvanceRequestRepository advances;
    @Autowired ProcurementPaymentRepository procurements;
    @Autowired BudgetAdjustmentRepository budgets;
    @Autowired JdbcProcurementPayableReservationRepository reservations;
    @Autowired ExpensePlanCheckWorker planChecks;
    @Autowired AdvanceRequestCheckWorker advanceChecks;
    @Autowired ProcurementPaymentCheckWorker procurementChecks;
    @Autowired BudgetAdjustmentCheckWorker budgetChecks;
    @Autowired FinanceGatewayConfiguration configuration;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", FILES::toString);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint", GATEWAY::endpoint);
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback", () -> true);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_FINANCE_URL", "jdbc:h2:mem:subprocess-finance;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_FINANCE_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_FINANCE_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_FINANCE_PASSWORD", ""));
    }

    @BeforeEach void setup() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization_directory WHERE tenant_id='demo'", Integer.class) == 0) organization.initialize(ADMIN);
        entity = organization.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "合成父子财务法人", null, null, true).id();
        var department = organization.createUnit(ADMIN, OrganizationUnit.Kind.DEPARTMENT, "合成部门", entity, null, true);
        var position = organization.createUnit(ADMIN, OrganizationUnit.Kind.POSITION, "合成岗位", entity, null, true);
        appointment = organization.createAppointment(ADMIN, person("alice", false), department.id(), position.id(), true).id();
        manager = person("manager", true);
        finance = person("finance", true);
        GATEWAY.use(json, entity);
    }

    @AfterEach void clearActor() {
        actors.clear();
        assertThat(GATEWAY.failure()).as("合成端口不能隐藏意外外发或无效事实").isNull();
    }
    @AfterAll static void closeGateway() { GATEWAY.close(); }

    @ParameterizedTest @EnumSource(Kind.class)
    void lastChildApprovalCreatesOnlyOriginalFinancialBasisAndReplaysOnce(Kind kind) throws Exception {
        var fixture = create(kind, Layout.REVIEW_THEN_CHILD);
        submit(fixture);
        String originalRounds = financialRounds(fixture);
        var originalPayload = app(fixture).payload();
        var gatewayCalls = GATEWAY.calls();
        assertPendingBasis(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var child = child(fixture);
        assertChildBinding(fixture, child);
        assertPendingBasis(fixture);

        String path = actionPath(child.id());
        String key = UUID.randomUUID().toString();
        var decision = decision(child.id(), "APPROVE");
        var completed = send(path, "finance", key, decision);
        ok(completed, 200);
        assertThat(send(path, "finance", key, decision).getContentAsString()).isEqualTo(completed.getContentAsString());
        assertApprovedBasis(fixture, SYSTEM_ACTOR);
        assertThat(application(child.id()).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app(fixture).payload()).isEqualTo(originalPayload);
        assertThat(financialRounds(fixture)).isEqualTo(originalRounds);
        assertThat(GATEWAY.calls()).isEqualTo(gatewayCalls);
        assertRoundStatus(fixture.applicationId(), 1, ApplicationStatus.APPROVED);
        assertRoundStatus(child.id(), 1, ApplicationStatus.APPROVED);
        assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(instance(child.id())).finished().count()).isEqualTo(1);
        assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(instance(fixture.applicationId())).finished().count()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void childApprovalStillRequiresTheActualRootFinancialReview(Kind kind) throws Exception {
        var fixture = create(kind, Layout.CHILD_THEN_REVIEW);
        submit(fixture);
        var child = child(fixture);
        assertChildBinding(fixture, child);
        ok(act(child.id(), "finance", "APPROVE"), 200);
        assertPendingBasis(fixture);
        assertThat(tasks.createTaskQuery().processInstanceId(instance(fixture.applicationId())).singleResult().getTaskDefinitionKey()).isEqualTo("review");
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        assertApprovedBasis(fixture, "manager");
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void returnedChildKeepsOriginalFinancialRoundAndResubmissionCreatesANewChild(Kind kind) throws Exception {
        var fixture = create(kind, Layout.REVIEW_THEN_CHILD);
        submit(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var oldChild = child(fixture);
        String originalRounds = financialRounds(fixture);
        var oldCall = call(fixture);
        var oldReservation = kind == Kind.PROCUREMENT ? reservations.active("demo", fixture.id()).orElseThrow() : null;
        ok(act(oldChild.id(), "finance", "RETURN"), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.RETURNED);
        assertThat(financialRounds(fixture)).isEqualTo(originalRounds);
        assertNoBasis(fixture);
        if (oldReservation != null) assertThat(reservations.active("demo", fixture.id())).contains(oldReservation);

        submit(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var next = child(fixture);
        assertThat(next.id()).isNotEqualTo(oldChild.id());
        assertThat(call(fixture).parentRoundNo()).isEqualTo(2);
        assertThat(calls.findByParentRound("demo", fixture.applicationId(), 1)).containsExactly(oldCall);
        assertThat(application(oldChild.id()).status()).isEqualTo(ApplicationStatus.RETURNED);
        assertRoundStatus(fixture.applicationId(), 1, ApplicationStatus.RETURNED);
        assertRoundStatus(oldChild.id(), 1, ApplicationStatus.RETURNED);
        assertThat(financialRoundList(fixture).get(0)).isEqualTo(json.read(originalRounds, JsonNode.class).get(0));
        ok(act(next.id(), "finance", "APPROVE"), 200);
        assertApprovedBasis(fixture, SYSTEM_ACTOR);
        assertRoundStatus(fixture.applicationId(), 2, ApplicationStatus.APPROVED);
        if (oldReservation != null) {
            assertThat(reservations.active("demo", fixture.id()).orElseThrow().source().round().roundNo()).isEqualTo(2);
            assertThat(reservations.find("demo", oldReservation.id()).orElseThrow().release().reason().name()).isEqualTo("RESUBMITTED");
        }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void rejectedChildRejectsTheFinancialParentWithoutCreatingCreditOrPayment(Kind kind) throws Exception {
        var fixture = create(kind, Layout.REVIEW_THEN_CHILD);
        submit(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var child = child(fixture);
        String originalRounds = financialRounds(fixture);
        var gatewayCalls = GATEWAY.calls();
        ok(act(child.id(), "finance", "REJECT"), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application(child.id()).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertNoBasis(fixture);
        assertThat(financialRounds(fixture)).isEqualTo(originalRounds);
        assertThat(tasks.createTaskQuery().processInstanceId(instance(fixture.applicationId())).count()).isZero();
        assertThat(tasks.createTaskQuery().processInstanceId(instance(child.id())).count()).isZero();
        assertThat(GATEWAY.calls()).isEqualTo(gatewayCalls);
        if (kind == Kind.PROCUREMENT) assertThat(reservations.active("demo", fixture.id())).isEmpty();
    }

    @Test void failedCreditCreationRollsBackChildApprovalAndBothRoundsThenOriginalRequestCanRetry() throws Exception {
        var fixture = create(Kind.PLAN, Layout.REVIEW_THEN_CHILD);
        submit(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var parent = app(fixture);
        var child = child(fixture);
        String taskPath = actionPath(child.id());
        String key = UUID.randomUUID().toString();
        var decision = decision(child.id(), "APPROVE");
        jdbc.execute("ALTER TABLE finance_resource ADD CONSTRAINT ck_subprocess_credit_fixture CHECK (id <> '" + fixture.id() + "')");
        try {
            assertThatThrownBy(() -> send(taskPath, "finance", key, decision)).hasRootCauseInstanceOf(SQLException.class);
            assertThat(app(fixture).version()).isEqualTo(parent.version());
            assertThat(application(child.id()).version()).isEqualTo(child.version());
            assertThat(actionPath(child.id())).isEqualTo(taskPath);
            assertPendingBasis(fixture);
            assertRoundStatus(parent.id(), 1, ApplicationStatus.IN_APPROVAL);
            assertRoundStatus(child.id(), 1, ApplicationStatus.IN_APPROVAL);
            assertThat(history.createHistoricTaskInstanceQuery().processInstanceId(instance(child.id())).finished().count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE finance_resource DROP CONSTRAINT ck_subprocess_credit_fixture");
        }
        ok(send(taskPath, "finance", key, decision), 200);
        assertApprovedBasis(fixture, SYSTEM_ACTOR);
    }

    @Test void failedPayableReleaseRollsBackChildRejectionAndKeepsOriginalReservation() throws Exception {
        var fixture = create(Kind.PROCUREMENT, Layout.REVIEW_THEN_CHILD);
        submit(fixture);
        ok(act(fixture.applicationId(), "manager", "APPROVE"), 200);
        var parent = app(fixture);
        var child = child(fixture);
        var reserved = reservations.active("demo", fixture.id()).orElseThrow();
        String taskPath = actionPath(child.id());
        String key = UUID.randomUUID().toString();
        var decision = decision(child.id(), "REJECT");
        jdbc.execute("ALTER TABLE procurement_payable_reservation ADD CONSTRAINT ck_subprocess_release_fixture CHECK (id <> '" + reserved.id() + "' OR released_at IS NULL)");
        try {
            assertThatThrownBy(() -> send(taskPath, "finance", key, decision)).hasRootCauseInstanceOf(SQLException.class);
            assertThat(app(fixture).version()).isEqualTo(parent.version());
            assertThat(application(child.id()).version()).isEqualTo(child.version());
            assertThat(actionPath(child.id())).isEqualTo(taskPath);
            assertThat(reservations.active("demo", fixture.id())).contains(reserved);
            assertRoundStatus(parent.id(), 1, ApplicationStatus.IN_APPROVAL);
            assertRoundStatus(child.id(), 1, ApplicationStatus.IN_APPROVAL);
        } finally {
            jdbc.execute("ALTER TABLE procurement_payable_reservation DROP CONSTRAINT ck_subprocess_release_fixture");
        }
        ok(send(taskPath, "finance", key, decision), 200);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(reservations.active("demo", fixture.id())).isEmpty();
        assertNoBasis(fixture);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void ordinaryChildCannotSubstituteTheRootFinancialReview(Kind kind) throws Exception {
        var fixture = create(kind, Layout.CHILD_ONLY);
        var original = app(fixture);
        var submission = versions(fixture);
        submission.put("precheckId", ready(fixture));
        var response = send(fixture.path() + "/submit", "alice", UUID.randomUUID().toString(), submission);
        assertThat(response.getStatus()).isBetween(400, 499);
        assertThat(json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class).path("code").asText()).isEqualTo(kind.reviewCode);
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app(fixture).roundNo()).isEqualTo(original.roundNo());
        assertThat(app(fixture).version()).isEqualTo(original.version());
        assertThat(financialRoundList(fixture)).isEmpty();
        assertThat(rounds.findAll("demo", fixture.applicationId())).isEmpty();
        assertThat(calls.findByParentRound("demo", fixture.applicationId(), 1)).isEmpty();
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", fixture.applicationId().toString()).count()).isZero();
        assertNoBasis(fixture);
        if (kind == Kind.PROCUREMENT) assertThat(reservations.active("demo", fixture.id())).isEmpty();
    }

    private Fixture create(Kind kind, Layout layout) throws Exception {
        var childDraft = definitions.create("demo", "finance-child-" + UUID.randomUUID(), "独立资料审批",
                linear(List.of(review("review", "role:ORG_PERSON_" + finance))), ordinarySchema());
        var child = definitions.publish(ADMIN, childDraft.id(), childDraft.revision(), "合成子审批固定版本");
        var policy = new SubprocessPolicy(child.key(), child.version(), Map.of("total", "amount", "currency", "currency"));
        var call = new Node("call", "独立子审批", NodeType.SUB_PROCESS, policy.properties());
        var review = review("review", "role:ORG_PERSON_" + manager);
        var graph = linear(switch (layout) {
            case REVIEW_THEN_CHILD -> List.of(review, call);
            case CHILD_THEN_REVIEW -> List.of(call, review);
            case CHILD_ONLY -> List.of(call);
        });
        // 公开门禁仍关闭；内部夹具只绕过开放策略，不绕过正式部署依赖解析或专用财务提交。
        var definition = new TransactionTemplate(transactions).execute(status -> {
            var value = DefinitionDraft.create(UUID.randomUUID(), "demo", "finance-parent-" + UUID.randomUUID(), "合成财务父审批", graph, financialSchema(kind, layout));
            drafts.save(value);
            value.publish(0, drafts.nextVersion("demo", value.key()));
            drafts.save(value);
            deployment.deploy(value);
            return value;
        });
        var response = ok(send(kind.path, "alice", UUID.randomUUID().toString(), Map.of("businessNo", "SUBFIN-" + UUID.randomUUID(),
                "processKey", definition.key(), "definitionVersion", definition.version(), "content", content(kind))), 201);
        UUID id = UUID.fromString(response.path("id").asText());
        UUID applicationId = switch (kind) {
            case PLAN -> plans.find("demo", id).orElseThrow().applicationId();
            case ADVANCE -> advances.find("demo", id).orElseThrow().applicationId();
            case PROCUREMENT -> procurements.find("demo", id).orElseThrow().applicationId();
            case BUDGET -> budgets.find("demo", id).orElseThrow().applicationId();
        };
        return new Fixture(kind, id, applicationId, child.id());
    }

    private Object content(Kind kind) {
        return switch (kind) {
            case PLAN -> new ExpensePlanContent(entity, ExpenseContent.Type.TRAVEL, "合成差旅计划", List.of(new ExpensePlanContent.Line(7, "TRAVEL", LocalDate.now(),
                    null, "SH", new Money(new BigDecimal("100"), "USD"), List.of(new CostAllocation("IT", null, new Money(new BigDecimal("100"), "USD"))), "客户现场交流")));
            case ADVANCE -> new AdvanceRequestContent(entity, "合成出差借款", "客户现场交流", money("100"), LocalDate.now().plusDays(10));
            case PROCUREMENT -> new ProcurementPaymentContent(entity, "合成设备采购", "验收后付款", "supplier-1", "AP-" + UUID.randomUUID(), money("70"));
            case BUDGET -> new BudgetAdjustmentContent(entity, "合成预算调拨", "业务额度调整", BudgetAdjustmentContent.Type.TRANSFER,
                    LocalDate.now(), "budget-source", "budget-target", money("70"));
        };
    }

    private void submit(Fixture fixture) throws Exception {
        String checked = ready(fixture);
        var submission = versions(fixture);
        submission.put("precheckId", checked);
        ok(send(fixture.path() + "/submit", "alice", UUID.randomUUID().toString(), submission), 200);
    }

    private String ready(Fixture fixture) throws Exception {
        var input = versions(fixture);
        input.put("initiatorAppointmentId", appointment);
        input.put("targetDigest", configuration.destination("demo").orElseThrow().digest("demo"));
        var checked = ok(send(fixture.path() + "/prechecks", "alice", UUID.randomUUID().toString(), input), 202);
        switch (fixture.kind()) {
            case PLAN -> planChecks.poll();
            case ADVANCE -> advanceChecks.poll();
            case PROCUREMENT -> procurementChecks.poll();
            case BUDGET -> budgetChecks.poll();
        }
        var status = ok(mvc.perform(get(fixture.path() + "/prechecks/" + checked.path("id").asText()).header("Authorization", token("alice"))).andReturn().getResponse(), 200);
        assertThat(status.at("/job/status").asText()).as(status.toString()).isEqualTo("READY");
        assertThat(status.path("usable").asBoolean()).isTrue();
        return checked.path("id").asText();
    }

    private Map<String, Object> versions(Fixture fixture) {
        long financialVersion = switch (fixture.kind()) {
            case PLAN -> plans.find("demo", fixture.id()).orElseThrow().version();
            case ADVANCE -> advances.find("demo", fixture.id()).orElseThrow().version();
            case PROCUREMENT -> procurements.find("demo", fixture.id()).orElseThrow().version();
            case BUDGET -> budgets.find("demo", fixture.id()).orElseThrow().version();
        };
        return new HashMap<>(Map.of("applicationVersion", app(fixture).version(), fixture.kind() == Kind.PLAN ? "planVersion" : "requestVersion", financialVersion));
    }

    private String financialRounds(Fixture fixture) {
        return json.write(switch (fixture.kind()) {
            case PLAN -> plans.find("demo", fixture.id()).orElseThrow().rounds();
            case ADVANCE -> advances.find("demo", fixture.id()).orElseThrow().rounds();
            case PROCUREMENT -> procurements.find("demo", fixture.id()).orElseThrow().rounds();
            case BUDGET -> budgets.find("demo", fixture.id()).orElseThrow().rounds();
        });
    }
    private JsonNode financialRoundList(Fixture fixture) { return json.read(financialRounds(fixture), JsonNode.class); }

    private void assertChildBinding(Fixture fixture, Application child) {
        var call = call(fixture);
        assertThat(call.childDefinitionId()).isEqualTo(fixture.childDefinitionId());
        assertThat(child.businessReference()).isNull();
        assertThat(child.createdBy()).isEqualTo("alice");
        assertThat(child.payload()).containsOnlyKeys("total", "currency");
        assertThat(child.payload().get("total")).isEqualTo(app(fixture).payload().get("amount"));
        assertThat(child.payload().get("currency")).isEqualTo("CNY");
        assertThat(rounds.findByRound("demo", child.id(), 1).orElseThrow().initiatorContext())
                .isEqualTo(rounds.findByRound("demo", fixture.applicationId(), app(fixture).roundNo()).orElseThrow().initiatorContext());
    }

    private void assertPendingBasis(Fixture fixture) {
        assertThat(app(fixture).status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertNoBasis(fixture);
    }

    private void assertNoBasis(Fixture fixture) {
        switch (fixture.kind()) {
            case PLAN -> assertThat(credits.find("demo", fixture.id())).isEmpty();
            case ADVANCE -> assertThat(advances.find("demo", fixture.id()).orElseThrow().approval()).isNull();
            case PROCUREMENT -> assertThat(procurements.find("demo", fixture.id()).orElseThrow().approval()).isNull();
            case BUDGET -> assertThat(budgets.find("demo", fixture.id()).orElseThrow().approval()).isNull();
        }
        assertThat(preparations(fixture)).isZero();
    }

    private void assertApprovedBasis(Fixture fixture, String actor) {
        var application = app(fixture);
        assertThat(application.status()).isEqualTo(ApplicationStatus.APPROVED);
        switch (fixture.kind()) {
            case PLAN -> {
                var credit = credits.find("demo", fixture.id()).orElseThrow();
                assertThat(credit.balance(7).available()).isEqualTo(money("710"));
                assertThat(credit.approvedLines().get(0).policyReference()).isEqualTo("APPROVAL:" + application.id() + ":" + application.roundNo());
            }
            case ADVANCE -> {
                var approval = advances.find("demo", fixture.id()).orElseThrow().approval();
                assertThat(approval.applicationVersion()).isEqualTo(application.version());
                assertThat(approval.roundNo()).isEqualTo(application.roundNo());
                assertThat(approval.approvedBy()).isEqualTo(actor);
            }
            case PROCUREMENT -> {
                var approval = procurements.find("demo", fixture.id()).orElseThrow().approval();
                assertThat(approval.applicationVersion()).isEqualTo(application.version());
                assertThat(approval.roundNo()).isEqualTo(application.roundNo());
                assertThat(approval.approvedBy()).isEqualTo(actor);
                assertThat(reservations.active("demo", fixture.id())).isPresent();
            }
            case BUDGET -> {
                var approval = budgets.find("demo", fixture.id()).orElseThrow().approval();
                assertThat(approval.applicationVersion()).isEqualTo(application.version());
                assertThat(approval.roundNo()).isEqualTo(application.roundNo());
                assertThat(approval.approvedBy()).isEqualTo(actor);
            }
        }
        assertThat(preparations(fixture)).isEqualTo(fixture.kind() == Kind.ADVANCE ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_operation WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString())).isZero();
    }

    private int preparations(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM voucher_preparation WHERE tenant_id='demo' AND application_id=?", Integer.class, fixture.applicationId().toString());
    }
    private void assertRoundStatus(UUID id, int round, ApplicationStatus status) { assertThat(rounds.findByRound("demo", id, round).orElseThrow().status().name()).isEqualTo(status.name()); }
    private String instance(UUID id) { return rounds.findByRound("demo", id, application(id).roundNo()).orElseThrow().processInstanceId(); }
    private Application app(Fixture fixture) { return application(fixture.applicationId()); }
    private Application application(UUID id) { return applications.findById("demo", id).orElseThrow(); }
    private Application child(Fixture fixture) { return application(call(fixture).childApplicationId()); }
    private SubprocessCall call(Fixture fixture) {
        var found = calls.findByParentRound("demo", fixture.applicationId(), app(fixture).roundNo());
        assertThat(found).hasSize(1);
        return found.get(0);
    }
    private String actionPath(UUID id) { return "/api/v1/tasks/" + tasks.createTaskQuery().processVariableValueEquals("applicationId", id.toString()).singleResult().getId() + "/actions"; }
    private Map<String, Object> decision(UUID id, String action) { return Map.of("action", action, "expectedVersion", application(id).version(), "comment", "已核对本轮资料"); }
    private MockHttpServletResponse act(UUID id, String user, String action) throws Exception { return send(actionPath(id), user, UUID.randomUUID().toString(), decision(id, action)); }
    private MockHttpServletResponse send(String path, String user, String key, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(json.write(body))).andReturn().getResponse();
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws java.io.UnsupportedEncodingException {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class);
    }
    private UUID person(String subject, boolean approver) {
        var found = jdbc.queryForList("SELECT id FROM organization_person WHERE tenant_id='demo' AND subject=?", String.class, subject);
        return found.isEmpty() ? organization.createPerson(ADMIN, subject, subject, true, approver).id() : UUID.fromString(found.get(0));
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static Node review(String id, String rule) { return new Node(id, "人工复核", NodeType.USER_TASK, Map.of("assigneeRule", rule)); }
    private static Graph linear(List<Node> steps) {
        var nodes = new ArrayList<Node>();
        nodes.add(new Node("start", "开始", NodeType.START, Map.of()));
        nodes.addAll(steps);
        nodes.add(new Node("end", "结束", NodeType.END, Map.of()));
        var edges = new ArrayList<Edge>();
        for (int index = 1; index < nodes.size(); index++) edges.add(new Edge("edge" + index, nodes.get(index - 1).id(), nodes.get(index).id(), ""));
        return new Graph(nodes, edges);
    }
    private static FormSchema ordinarySchema() { return new FormSchema(2, List.of(field("total", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT))); }
    private static FormSchema financialSchema(Kind kind, Layout layout) {
        var access = layout == Layout.CHILD_ONLY ? Map.<String, FieldVisibility>of() : Map.of("review", FieldVisibility.READ_ONLY);
        return new FormSchema(2, List.of(new FormSchema.Field(kind.details, "财务明细", FormSchema.FieldType.TEXT, true, null, null, null, null, null,
                null, null, true, access), field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT)));
    }
    private static FormSchema.Field field(String key, FormSchema.FieldType type) { return new FormSchema.Field(key, key, type, true, null, null, null, null, null); }

    private enum Kind {
        PLAN("/api/v1/expense-plans", ExpensePlanFormContract.DETAILS, "EXPENSE_PLAN_REVIEW_REQUIRED"),
        ADVANCE("/api/v1/advance-requests", AdvanceRequestFormContract.DETAILS, "ADVANCE_REQUEST_REVIEW_REQUIRED"),
        PROCUREMENT("/api/v1/procurement-payments", ProcurementPaymentFormContract.DETAILS, "PROCUREMENT_REVIEW_REQUIRED"),
        BUDGET("/api/v1/budget-adjustments", BudgetAdjustmentFormContract.DETAILS, "BUDGET_ADJUSTMENT_REVIEW_REQUIRED");
        private final String path;
        private final String details;
        private final String reviewCode;
        Kind(String path, String details, String reviewCode) { this.path = path; this.details = details; this.reviewCode = reviewCode; }
    }
    private enum Layout { REVIEW_THEN_CHILD, CHILD_THEN_REVIEW, CHILD_ONLY }
    private record Fixture(Kind kind, UUID id, UUID applicationId, UUID childDefinitionId) {
        private String path() { return kind.path + "/" + id; }
    }
}
