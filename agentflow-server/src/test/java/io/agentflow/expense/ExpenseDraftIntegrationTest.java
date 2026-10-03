package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.Money;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static io.agentflow.support.MutationRequests.put;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真实数据库与引擎验证双版本、不可变绑定、通用入口隔离及逐轮敏感明细权限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo",
        "spring.datasource.url=${AGENTFLOW_EXPENSE_TEST_URL:jdbc:h2:mem:expense-draft;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_EXPENSE_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_EXPENSE_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_EXPENSE_TEST_DRIVER:org.h2.Driver}"})
@AutoConfigureMockMvc
class ExpenseDraftIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired CurrentActor actors;
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ExpenseReportRepository reports;
    @Autowired ExpenseDraftService drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService tasks;

    @Test
    void draftKeepsExactDecimalStringsAndReplayDoesNotDuplicateTheApplication() throws Exception {
        var definition = published(FieldVisibility.READ_ONLY);
        var content = content("精确金额", "99999999999999.99");
        var body = createBody(definition, content);
        String key = UUID.randomUUID().toString();
        var first = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/expense-reports")
                .header("Idempotency-Key", key), "alice", body);
        var result = ok(first, 201);
        var repeated = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/expense-reports")
                .header("Idempotency-Key", key), "alice", body);
        assertThat(repeated.getStatus()).isEqualTo(201);
        assertThat(repeated.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(result.at("/content/lines/0/claimedGross/value").isTextual()).isTrue();
        assertThat(result.at("/content/lines/0/claimedGross/value").asText()).isEqualTo("99999999999999.99");
        var app = applicationRepository.findById("demo", UUID.fromString(result.path("applicationId").asText())).orElseThrow();
        assertThat(app.payload()).isEqualTo(ExpenseFormContract.draftPayload());
        assertThat(app.businessReference()).isEqualTo(new BusinessReference(BusinessReference.Type.EXPENSE, UUID.fromString(result.path("id").asText())));
        assertThat(reports.findByApplication("demo", app.id()).orElseThrow().content()).isEqualTo(content);
        assertThat(revisions(result)).isEqualTo(1);
        assertThat(ok(send(get("/api/v1/applications/" + app.id()), "alice", null), 200).at("/businessReference/type").asText()).isEqualTo("EXPENSE");
    }

    @Test
    void moneyNumbersExponentsAndUnknownPropertiesFailWithoutCreatingFinancialRecords() throws Exception {
        var definition = published(FieldVisibility.READ_ONLY);
        for (String invalid : List.of("{\"value\":0.1,\"currency\":\"CNY\"}", "{\"value\":\"1e2\",\"currency\":\"CNY\"}",
                "{\"value\":\"1.001\",\"currency\":\"CNY\"}", "{\"value\":\"10.00\",\"currency\":\"CNY\",\"approved\":true}")) {
            String body = json.write(createBody(definition, content("非法金额", "10"))).replace("{\"value\":\"10.00\",\"currency\":\"CNY\"}", invalid);
            int before = jdbc.queryForObject("SELECT COUNT(*) FROM expense_report", Integer.class);
            var response = mvc.perform(post("/api/v1/expense-reports").header("Authorization", token("alice"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_MONEY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_report", Integer.class)).isEqualTo(before);
        }
    }

    @Test
    void unrelatedUsersAndAdministratorsCannotReadOrEditAnApplicantsDraft() throws Exception {
        var draft = create(); String path = path(draft);
        for (String user : List.of("bob", "admin", "manager")) {
            assertThat(send(get(path), user, null).getStatus()).isEqualTo(404);
            var response = send(post(path + "/revise"), user, revision(1, 1, content("恶意改写", "2")));
            assertThat(response.getStatus()).isEqualTo(403);
        }
        assertThat(reports.find("foreign", id(draft))).isEmpty();
        actors.set(new Actor("foreign", "alice", Set.of("ADMIN")));
        try { fails("NOT_FOUND", () -> drafts.read(id(draft), null)); } finally { actors.clear(); }
        assertThat(ok(send(get(path), "alice", null), 200).at("/content/title").asText()).isEqualTo("费用草稿");
    }

    @Test
    void eachVersionMustMatchAndNoFailedRevisionLeavesAPartialApplicationChange() throws Exception {
        var draft = create(); String path = path(draft);
        var changed = ok(send(post(path + "/revise"), "alice", revision(1, 1, content("第一版补正", "20"))), 200);
        assertThat(changed.path("applicationVersion").asLong()).isEqualTo(2);
        assertThat(changed.path("financialVersion").asLong()).isEqualTo(2);
        for (var versions : List.of(new long[]{2, 1}, new long[]{1, 2})) {
            var conflict = send(post(path + "/revise"), "alice", revision(versions[0], versions[1], content("过期补正", "99")));
            assertThat(conflict.getStatus()).isEqualTo(409);
        }
        assertThat(ok(send(get(path), "alice", null), 200)).isEqualTo(changed);
        assertThat(revisions(draft)).isEqualTo(2);
    }

    @Test
    void genericCreateSubmitReviseWithdrawAndCancelCannotBypassTheBusinessBoundary() throws Exception {
        var definition = published(FieldVisibility.READ_ONLY);
        var forged = send(post("/api/v1/applications"), "alice", Map.of("businessNo", "forged-" + UUID.randomUUID(),
                "processKey", definition.key(), "definitionVersion", definition.version(), "title", "绕过财务", "payload", ExpenseFormContract.draftPayload()));
        assertThat(forged.getStatus()).isEqualTo(422);
        assertThat(tree(forged).path("code").asText()).isEqualTo("USE_BUSINESS_ENDPOINT");
        var draft = ok(send(post("/api/v1/expense-reports"), "alice", createBody(definition, content("受保护草稿", "10"))), 201);
        String application = "/api/v1/applications/" + draft.path("applicationId").asText();
        for (String operation : List.of("submit", "withdraw", "cancel")) {
            var response = send(post(application + "/" + operation), "alice", Map.of("expectedVersion", 1));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("USE_BUSINESS_ENDPOINT");
        }
        var revision = send(put(application), "alice", Map.of("expectedVersion", 1, "title", "篡改总额", "payload", Map.of("amount", "99999")));
        assertThat(tree(revision).path("code").asText()).isEqualTo("USE_BUSINESS_ENDPOINT");
        assertThat(ok(send(get(path(draft)), "alice", null), 200).path("financialVersion").asLong()).isEqualTo(1);
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", draft.path("applicationId").asText()).count()).isZero();
    }

    @Test
    void missingSensitiveDetailsContractCannotCreateAnExpenseApplication() {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            fails("EXPENSE_FORM_CONTRACT_REQUIRED", () -> drafts.create("invalid-" + UUID.randomUUID(), "expense-reimbursement", 1, content("旧表单", "10")));
            var fields = schema(FieldVisibility.READ_ONLY).fields().stream().map(field -> ExpenseFormContract.DETAILS.equals(field.key())
                    ? new FormSchema.Field(field.key(), field.label(), field.type(), true, null, null, null, null, null) : field).toList();
            fails("EXPENSE_FORM_CONTRACT_REQUIRED", () -> ExpenseFormContract.requireSchema(new FormSchema(2, fields)));
        } finally { actors.clear(); }
    }

    @Test
    void financialSnapshotReadsUseActualRoundPermissionsAndNeverExposeAccountReferences() throws Exception {
        var draft = create(); submitFixture(draft);
        var response = ok(send(get(path(draft)), "manager", null), 200);
        assertThat(response.at("/financialRound/approvedGross/value").asText()).isEqualTo("10.00");
        assertThat(response.at("/financialRound/maskedAccount").asText()).isEqualTo("***1234");
        assertThat(response.toString()).doesNotContain("private-account-ref", "a".repeat(64));
        assertThat(send(get(path(draft)), "admin", null).getStatus()).isEqualTo(403);
        assertThat(send(get(path(draft)), "bob", null).getStatus()).isEqualTo(404);
        var maskedDefinition = published(FieldVisibility.MASKED);
        var masked = ok(send(post("/api/v1/expense-reports"), "alice", createBody(maskedDefinition, content("脱敏费用", "10"))), 201);
        submitFixture(masked);
        assertThat(send(get(path(masked)), "manager", null).getStatus()).isEqualTo(403);
    }

    @Test
    void returningDoesNotGrantOldReviewersTheCorrectedDraftButRetainsTheirOriginalRound() throws Exception {
        var draft = create(); submitFixture(draft);
        var appId = draft.path("applicationId").asText();
        var task = tasks.createTaskQuery().processVariableValueEquals("applicationId", appId).singleResult();
        var returned = ok(send(post("/api/v1/tasks/" + task.getId() + "/actions"), "manager",
                Map.of("action", "RETURN", "comment", "补齐凭据", "expectedVersion", 3)), 200);
        long applicationVersion = returned.path("version").asLong();
        var changed = send(post(path(draft) + "/revise"), "alice", revision(applicationVersion, 2, content("本轮补正的私密内容", "20")));
        ok(changed, 200);
        assertThat(send(get(path(draft)), "manager", null).getStatus()).isEqualTo(404);
        var old = ok(send(get(path(draft) + "?roundNo=1"), "manager", null), 200);
        assertThat(old.at("/content/title").asText()).isEqualTo("费用草稿");
        assertThat(old.toString()).doesNotContain("本轮补正的私密内容");
        assertThat(old.path("editable").asBoolean()).isFalse();
    }

    @Test
    void failedFinancialJournalInsertRollsBackBothAggregateUpdates() throws Exception {
        var draft = create();
        var appId = draft.path("applicationId").asText();
        jdbc.update("INSERT INTO expense_report_revision(tenant_id,report_id,financial_version,actor_id,operation,state_json) SELECT tenant_id,report_id,2,actor_id,operation,state_json FROM expense_report_revision WHERE tenant_id='demo' AND report_id=?", id(draft).toString());
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            assertThatThrownBy(() -> drafts.revise(id(draft), 1, 1, content("不应保存", "20")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { actors.clear(); }
        assertThat(reports.find("demo", id(draft)).orElseThrow().version()).isEqualTo(1);
        assertThat(applicationRepository.findById("demo", UUID.fromString(appId)).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void repositoryProtectsTenantBindingApplicantAndOptimisticFinancialVersion() throws Exception {
        var draft = create(); var report = reports.find("demo", id(draft)).orElseThrow();
        var first = ExpenseReport.restore(report.state()); var second = ExpenseReport.restore(report.state());
        first.revise(1, content("先写入", "10")); second.revise(1, content("后写入", "20"));
        reports.update(first, 1, "alice", "REVISE");
        fails("CONCURRENCY_CONFLICT", () -> reports.update(second, 1, "alice", "REVISE"));
        var state = first.state();
        var changedOwner = ExpenseReport.restore(new ExpenseReport.State(state.id(), state.tenantId(), state.applicationId(), "bob", state.content(), state.version(), state.rounds()));
        changedOwner.revise(2, content("修改所有人", "20"));
        fails("CONCURRENCY_CONFLICT", () -> reports.update(changedOwner, 2, "alice", "REVISE"));
        assertThat(reports.find("demo", id(draft)).orElseThrow().employeeId()).isEqualTo("alice");
        assertThat(reports.findByApplication("foreign", report.applicationId())).isEmpty();
    }

    private JsonNode create() throws Exception {
        return ok(send(post("/api/v1/expense-reports"), "alice", createBody(published(FieldVisibility.READ_ONLY), content("费用草稿", "10"))), 201);
    }

    private DefinitionModels.DefinitionDraft published(FieldVisibility access) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", "", false), new Edge("b", "review", "finance", "", false), new Edge("c", "finance", "end", "", false)));
        var draft = definitions.create("demo", "expense-test-" + UUID.randomUUID(), "结构化费用测试", graph, schema(access), null);
        return definitions.publish(new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN")), draft.id(), draft.revision(), "财务验收");
    }

    private static FormSchema schema(FieldVisibility access) {
        return new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true, null,
                        null, null, null, null, null, null, true, Map.of("review", access, "finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "本币核定额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超出制度", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
    }

    // 仅为读取权限测试构造已冻结事实，不作为正式提交服务或真实费用查验的验收。
    private void submitFixture(JsonNode draft) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try {
            var report = reports.find("demo", id(draft)).orElseThrow();
            var line = report.content().lines().get(0);
            var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "fixture", LocalDate.now()),
                    new ExpensePolicySnapshot(UUID.randomUUID(), 1, line.claimedGross(), line.claimedGross(), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "fixture-tax", "fixture-evidence"), line.claimedTax());
            report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(report.content().legalEntityId(), "alice", "private-account-ref", "***1234", "a".repeat(64), "fixture-v1"),
                    Map.of(1, assessment), "alice", Instant.now());
            reports.update(report, 1, "alice", "SUBMIT");
            var app = applicationRepository.findById("demo", report.applicationId()).orElseThrow();
            applications.reviseBusiness(app.id(), 1, app.title(), ExpenseFormContract.submittedPayload(report.currentRound()), app.businessReference());
            applications.submitBusiness(app.id(), 2, null, app.businessReference());
        } finally { actors.clear(); }
    }

    private static ExpenseContent content(String title, String amount) {
        var gross = new Money(new BigDecimal(amount), "CNY");
        return new ExpenseContent(UUID.fromString("11111111-1111-1111-1111-111111111111"), ExpenseContent.Type.DAILY, title,
                List.of(new ExpenseLine(1, "OFFICE", LocalDate.parse("2026-09-28"), null, "BEIJING", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                        gross, Money.zero("CNY"), List.of(), null, List.of(new CostAllocation("engineering", null, gross)), "办公费用", null)), List.of());
    }
    private static Map<String, Object> createBody(DefinitionModels.DefinitionDraft definition, ExpenseContent content) {
        return Map.of("businessNo", "EXP-" + UUID.randomUUID(), "processKey", definition.key(), "definitionVersion", definition.version(), "content", content);
    }
    private static Map<String, Object> revision(long app, long report, ExpenseContent content) { return Map.of("applicationVersion", app, "financialVersion", report, "content", content); }
    private static UUID id(JsonNode draft) { return UUID.fromString(draft.path("id").asText()); }
    private static String path(JsonNode draft) { return "/api/v1/expense-reports/" + id(draft); }
    private int revisions(JsonNode draft) { return jdbc.queryForObject("SELECT COUNT(*) FROM expense_report_revision WHERE tenant_id='demo' AND report_id=?", Integer.class, id(draft).toString()); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String user, Object body) throws Exception {
        request.header("Authorization", token(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        return mvc.perform(request).andReturn().getResponse();
    }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), JsonNode.class); }
    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status); return tree(response);
    }
    private static void fails(String code, Runnable action) {
        assertThatExceptionOfType(DomainException.class).isThrownBy(action::run).satisfies(error -> assertThat(error.code()).isEqualTo(code));
    }
}
