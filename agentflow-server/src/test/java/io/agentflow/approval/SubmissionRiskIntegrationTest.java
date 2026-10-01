package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.approval.service.ProcessRuntimePort;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.ApprovalRiskPolicy;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 实际发布版本、父子提交与轮次事务共同决定风险来源，读端不能重算或回填。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_RISK_TEST_URL:jdbc:h2:mem:submission-risk;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_RISK_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_RISK_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_RISK_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.sla.reminders-enabled=false"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class SubmissionRiskIntegrationTest {
    private static final Actor ADMIN = new Actor("demo", "admin", Set.of("ADMIN"));
    @Autowired DefinitionApplicationService definitions;
    @Autowired ApprovalApplicationFacade applications;
    @Autowired ApplicationRepository repository;
    @MockitoSpyBean SubmissionRoundRepository rounds;
    @Autowired SubprocessCallRepository calls;
    @Autowired CurrentActor actors;
    @Autowired ProcessRuntimePort process;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;

    @Test
    void oldRootUsesItsBoundVersionAndLaterRuntimePayloadCannotRewriteAssessment() {
        var original = publish(key(), graph("amount", SubmissionRisk.Level.HIGH), schema("amount"));
        var application = create(original);
        var latest = publish(original.key(), graph("amount", SubmissionRisk.Level.LOW), schema("amount"));
        assertThat(latest.version()).isEqualTo(2);
        asApplicant(() -> applications.submit(application.id(), 1));
        var round = round(application.id(), 1);
        assertThat(round.risk().definitionId()).isEqualTo(original.id());
        assertThat(round.risk().definitionVersion()).isEqualTo(1);
        assertThat(round.risk().level()).isEqualTo(SubmissionRisk.Level.HIGH);
        process.updateBusinessPayload(new ProcessRuntimePort.UpdateBusinessPayload("demo", application.id(), 1,
                round.processInstanceId(), Map.of("amount", "0")));
        assertThat(round(application.id(), 1).risk()).isEqualTo(round.risk());
        var withdrawn = asApplicant(() -> applications.withdraw(application.id(), 2, "修改本轮输入"));
        var revised = asApplicant(() -> applications.revise(application.id(), withdrawn.version(), "新的提交", Map.of("amount", "0")));
        asApplicant(() -> applications.submit(application.id(), revised.version()));
        assertThat(round(application.id(), 1).risk()).isEqualTo(round.risk());
        assertThat(round(application.id(), 2).risk().definitionId()).isEqualTo(original.id());
        assertThat(round(application.id(), 2).risk().level()).isEqualTo(SubmissionRisk.Level.UNMATCHED);
    }

    @Test
    void childUsesMappedInputAndFixedChildPolicyInsteadOfParentsOrLatestPolicy() {
        var child = publish(key(), graph("total", SubmissionRisk.Level.MEDIUM), schema("total"));
        var parent = publish(key(), parentGraph(child), schema("amount"));
        publish(child.key(), graph("total", SubmissionRisk.Level.LOW), schema("total"));
        var application = create(parent);
        asApplicant(() -> applications.submit(application.id(), 1));
        var call = calls.findByParentRound("demo", application.id(), 1).get(0);
        var parentRisk = round(application.id(), 1).risk();
        var childRound = round(call.childApplicationId(), 1);
        assertThat(parentRisk.level()).isEqualTo(SubmissionRisk.Level.HIGH);
        assertThat(childRound.risk().level()).isEqualTo(SubmissionRisk.Level.MEDIUM);
        assertThat(childRound.risk().definitionId()).isEqualTo(child.id());
        assertThat(childRound.risk().definitionVersion()).isEqualTo(1);
        assertThat(childRound.payload()).containsExactlyEntriesOf(Map.of("total", "20"));
        asApplicant(() -> applications.withdraw(application.id(), 2, "父子共同结束"));
        assertThat(round(call.childApplicationId(), 1).risk()).isEqualTo(childRound.risk());
        assertThat(round(application.id(), 1).risk()).isEqualTo(parentRisk);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureAfterRootOrChildSnapshotInsertionRollsBackTheEntireSubmission(boolean failChild) {
        var child = publish(key(), graph("total", SubmissionRisk.Level.MEDIUM), schema("total"));
        var parent = publish(key(), parentGraph(child), schema("amount"));
        var application = create(parent);
        var before = counts();
        var target = org.springframework.test.util.AopTestUtils.<SubmissionRoundRepository>getUltimateTargetObject(rounds);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            SubmissionRound saved = invocation.getArgument(0);
            if (failChild != saved.applicationId().equals(application.id())) throw new IllegalStateException("Injected risk snapshot failure");
            return null;
        }).when(target).append(any());
        try {
            assertThatThrownBy(() -> asApplicant(() -> applications.submit(application.id(), 1)))
                    .hasStackTraceContaining("Injected risk snapshot failure");
            assertThat(counts()).isEqualTo(before);
            assertThat(repository.findById("demo", application.id()).orElseThrow().status()).isEqualTo(ApplicationStatus.DRAFT);
            assertThat(calls.findByParentRound("demo", application.id(), 1)).isEmpty();
            assertThat(runtime.createProcessInstanceQuery().variableValueEquals("applicationId", application.id().toString()).count()).isZero();
        } finally { doCallRealMethod().when(target).append(any()); }
        asApplicant(() -> applications.submit(application.id(), 1));
        assertThat(round(application.id(), 1).risk().level()).isEqualTo(SubmissionRisk.Level.HIGH);
        assertThat(calls.findByParentRound("demo", application.id(), 1)).hasSize(1);
    }

    @Test
    void publicDefinitionApiRejectsCoercionUnknownPropertiesAndPrivateFieldRulesWithoutWriting() throws Exception {
        var good = json.map(json.write(graph("amount", SubmissionRisk.Level.HIGH)));
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition", Long.class);
        for (Object rule : List.of(
                Map.of("id", "r", "label", "公开说明", "level", 3, "condition", "amount > 1"),
                Map.of("id", "r", "label", true, "level", "HIGH", "condition", "amount > 1"),
                Map.of("id", "r", "label", "公开说明", "level", "HIGH", "condition", "amount > 1", "extra", "ignored"),
                Map.of("id", "r", "label", "公开说明", "level", "UNASSESSED", "condition", "amount > 1"))) {
            var invalid = new java.util.HashMap<>(good);
            invalid.put("riskPolicy", Map.of("rules", List.of(rule)));
            mvc.perform(post("/api/v1/process-definitions").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.write(Map.of("key", key(), "name", "拒绝隐式规则", "graph", invalid, "formSchema", schema("amount")))))
                    .andExpect(status().is4xxClientError());
        }
        var privateSchema = new FormSchema(1, List.of(new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER,
                true, null, null, null, null, null, null, null, false, Map.of("review", FieldVisibility.MASKED))));
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("graph", good, "formSchema", privateSchema))))
                .andExpect(status().isOk()).andExpect(jsonPath("errors[0]").value("RISK_FIELD_RESTRICTED:risk:amountRule"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_definition", Long.class)).isEqualTo(before);
    }

    private Map<String, Long> counts() {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (String table : List.of("approval_application", "approval_submission_round", "approval_subprocess_call", "audit_event", "notification_inbox", "ACT_RU_TASK")) {
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return result;
    }

    private Graph graph(String field, SubmissionRisk.Level level) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")), 2, policy(field, level));
    }

    private Graph parentGraph(DefinitionDraft child) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "固定子审批", NodeType.SUB_PROCESS, new SubprocessPolicy(child.key(), child.version(), Map.of("total", "amount")).properties()),
                new Node("review", "父审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "review", ""), new Edge("c", "review", "end", "")), 2,
                policy("amount", SubmissionRisk.Level.HIGH));
    }

    private ApprovalRiskPolicy policy(String field, SubmissionRisk.Level level) {
        return new ApprovalRiskPolicy(List.of(new ApprovalRiskPolicy.Rule(field + "Rule", "公开复核说明", level, field + " > 10")));
    }

    private DefinitionDraft publish(String key, Graph graph, FormSchema schema) {
        var draft = definitions.create("demo", key, "风险版本验收", graph, schema);
        return definitions.publish(ADMIN, draft.id(), draft.revision(), "核对提交时风险来源");
    }

    private Application create(DefinitionDraft definition) {
        return asApplicant(() -> applications.create("RISK-" + UUID.randomUUID(), definition.key(), definition.version(), "风险冻结", Map.of("amount", "20")));
    }
    private SubmissionRound round(UUID id, int number) { return rounds.findByRound("demo", id, number).orElseThrow(); }
    private FormSchema schema(String field) { return new FormSchema(1, List.of(new FormSchema.Field(field, field, FormSchema.FieldType.NUMBER, true, null, null, null, null, null))); }
    private String key() { return "risk-" + UUID.randomUUID(); }
    private String token() { return "Bearer " + auth.login("demo", "admin", "demo").token(); }
    private <T> T asApplicant(Supplier<T> action) {
        actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
        try { return action.get(); } finally { actors.clear(); }
    }
}
