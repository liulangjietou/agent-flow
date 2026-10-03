package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实认证和双库可复用的运行查询验收；摘要均为合成夹具，不调用模型。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_AGENT_TEST_URL:jdbc:h2:mem:agent-query;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_AGENT_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_AGENT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_AGENT_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class AssistRunQueryIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-09-23T12:00:00.123456789Z");
    private static final AssistInput.Reference SOURCE = new AssistInput.Reference("form:amount", "a".repeat(64));
    private static final String MODEL_TEXT = "合成夹具：<script>alert('untrusted')</script> 金额待核对";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationRepository applications;
    @Autowired AssistRunRepository runs;

    @Test
    void listExcludesBodiesAndDetailKeepsModelAndHumanTextSeparateWithoutWrites() throws Exception {
        var app = application("demo"); var run = run(app, AssistRun.Status.ADOPTED);
        var before = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", app.id().toString());
        var transitions = jdbc.queryForList("SELECT * FROM agent_assist_transition WHERE run_id=? ORDER BY run_version", run.id().toString());
        var audit = jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=?", app.id().toString());
        var tasks = jdbc.queryForList("SELECT * FROM ACT_RU_TASK ORDER BY ID_");
        var page = page(app, "alice", Map.of());
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.toString()).doesNotContain("suggestion", "inputReferences", "requestedBy", "tenantId", MODEL_TEXT);
        var detail = detail(app, run, "alice", 200);
        assertThat(detail.path("suggestion").path("claims").get(0).path("text").asText()).isEqualTo(MODEL_TEXT);
        assertThat(detail.path("review").path("acceptedText").asText()).isEqualTo("人工核对后的摘要");
        assertThat(detail.path("inputReferences").get(0).path("contentDigest").asText()).isEqualTo(SOURCE.contentDigest());
        assertThat(detail.path("inputCurrent").asBoolean()).isTrue();
        assertThat(detail.path("createdAt").asText()).isEqualTo(CREATED.toString());
        assertThat(detail.has("tenantId")).isFalse();
        assertThat(detail(app, run, "admin", 200)).isEqualTo(detail);
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", app.id().toString())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM agent_assist_transition WHERE run_id=? ORDER BY run_version", run.id().toString())).isEqualTo(transitions);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=?", app.id().toString())).isEqualTo(audit);
        assertThat(jdbc.queryForList("SELECT * FROM ACT_RU_TASK ORDER BY ID_")).isEqualTo(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "COMPLETED", "FAILED", "ADOPTED", "DISMISSED"})
    void everyStateHasExplicitNullableFieldsAndStableFailureCodes(String state) throws Exception {
        var app = application("demo"); var run = run(app, AssistRun.Status.valueOf(state));
        var value = detail(app, run, "alice", 200);
        assertThat(value.path("status").asText()).isEqualTo(state);
        for (String field : List.of("startedAt", "completedAt", "suggestion", "failure", "review")) assertThat(value.has(field)).isTrue();
        if (state.equals("FAILED")) {
            assertThat(value.path("failure").asText()).isEqualTo("MODEL_TIMEOUT");
            assertThat(value.path("suggestion").isNull()).isTrue();
        }
        if (state.equals("DISMISSED")) assertThat(value.path("review").path("acceptedText").isMissingNode()
                || value.path("review").path("acceptedText").isNull()).isTrue();
    }

    @Test
    void changedApplicationMarksHistoricalInputWithoutRewritingOriginalRecord() throws Exception {
        var app = application("demo"); var run = run(app, AssistRun.Status.COMPLETED);
        app.revise(1, "已补正", Map.of("amount", 7000)); applications.update(app, 1);
        var value = detail(app, run, "alice", 200);
        assertThat(value.path("inputCurrent").asBoolean()).isFalse();
        assertThat(value.path("applicationVersion").asLong()).isEqualTo(1);
        assertThat(value.path("currentApplicationVersion").asLong()).isEqualTo(2);
        assertThat(value.path("suggestion").path("claims").get(0).path("text").asText()).isEqualTo(MODEL_TEXT);
    }

    @Test
    void unauthorizedTenantAndMismatchedApplicationCannotReadOrUseRequesterIdentity() throws Exception {
        var app = application("demo"); var run = run(app, AssistRun.Status.COMPLETED);
        detail(app, run, "bob", 404); detail(app, run, "finance", 404);
        mvc.perform(get(path(app)).header("Authorization", token("finance"))).andExpect(status().isNotFound());
        mvc.perform(get(path(app))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(app) + "/" + run.id())).andExpect(status().isUnauthorized());
        detail(application("demo"), run, "alice", 404);
        var foreign = application("foreign"); var foreignRun = run(foreign, AssistRun.Status.COMPLETED);
        detail(foreign, foreignRun, "admin", 404); detail(app, foreignRun, "admin", 404);
        mvc.perform(get(path(foreign)).header("Authorization", token("admin"))).andExpect(status().isNotFound());
        mvc.perform(get(path(app) + "/" + UUID.randomUUID()).header("Authorization", token("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void currentTaskParticipationGrantsReadsAndLosingItBlocksOldCursorAndDetail() throws Exception {
        var app = submitted(); var first = run(app, AssistRun.Status.COMPLETED); run(app, AssistRun.Status.QUEUED);
        var page = page(app, "finance", Map.of("limit", "1"));
        detail(app, first, "finance", 200);
        String task = jdbc.queryForObject("SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_=(SELECT process_instance_id FROM approval_submission_round WHERE application_id=?)", String.class, app.id().toString());
        jdbc.update("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_=?", task);
        jdbc.update("UPDATE ACT_RU_TASK SET ASSIGNEE_='manager' WHERE ID_=?", task);
        mvc.perform(get(path(app)).param("limit", "1").param("cursor", page.path("nextCursor").asText())
                .header("Authorization", token("finance"))).andExpect(status().isNotFound());
        detail(app, first, "finance", 404);
        assertThat(page(app, "alice", Map.of()).path("items")).hasSize(2);
    }

    @Test
    void timestampTiesUseDatabasePrecisionAndCursorIsBoundToActorApplicationAndRound() throws Exception {
        var app = application("demo"); for (int i = 0; i < 5; i++) run(app, AssistRun.Status.QUEUED);
        var first = page(app, "alice", Map.of("limit", "2")); var current = first; var ids = new HashSet<String>();
        int pages = 0;
        do {
            assertThat(++pages).isLessThanOrEqualTo(3);
            current.path("items").forEach(item -> assertThat(ids.add(item.path("id").asText())).isTrue());
            if (current.path("nextCursor").isNull()) break;
            current = page(app, "alice", Map.of("limit", "2", "cursor", current.path("nextCursor").asText()));
        } while (true);
        assertThat(ids).hasSize(5);
        String cursor = first.path("nextCursor").asText();
        mvc.perform(get(path(app)).param("cursor", cursor).header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(app)).param("cursor", cursor).param("roundNo", "1").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(application("demo"))).param("cursor", cursor).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        assertThat(page(app, "alice", Map.of("roundNo", "1")).path("items")).hasSize(5);
        assertThat(page(app, "alice", Map.of("roundNo", "2")).path("items")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=x", "roundNo=0", "roundNo=", "cursor=bad", "cursor=", "tenantId=other", "status=COMPLETED"})
    void unsupportedAndMalformedFiltersFailAtBoundary(String input) throws Exception {
        var parts = input.split("=", -1); var app = application("demo");
        mvc.perform(get(path(app)).param(parts[0], parts[1]).header("Authorization", token("alice")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_AGENT_QUERY"));
    }

    @Test
    void rejectsRepeatedParametersAndDetailFiltersAndInvalidCreationRequests() throws Exception {
        var app = application("demo"); var run = run(app, AssistRun.Status.COMPLETED);
        mvc.perform(get(path(app)).param("limit", "1", "2").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(app) + "/" + run.id()).param("tenantId", "other").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(post(path(app)).header("Authorization", token("alice")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private Application application(String tenant) {
        return applications.save(Application.draft(UUID.randomUUID(), tenant, "AGENT-QUERY-" + UUID.randomUUID(),
                "expense-reimbursement", 1, "alice", "Agent 记录测试夹具", Map.of("amount", 6000)));
    }
    private Application submitted() throws Exception {
        var app = application("demo");
        mvc.perform(post("/api/v1/applications/" + app.id() + "/submit").header("Authorization", token("alice"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        return applications.findById("demo", app.id()).orElseThrow();
    }
    private AssistRun run(Application app, AssistRun.Status state) {
        var run = AssistRun.queue(UUID.randomUUID(), app.tenantId(), "finance", CREATED,
                new AssistInput(app.id(), app.version(), app.roundNo(), List.of(SOURCE)), "test-prompt-v1");
        runs.create(run);
        if (state == AssistRun.Status.QUEUED) return run;
        run.start(1, CREATED); runs.update(run, 1);
        if (state == AssistRun.Status.RUNNING) return run;
        if (state == AssistRun.Status.FAILED) run.fail(2, AssistRun.Failure.MODEL_TIMEOUT, CREATED.plusSeconds(1));
        else run.complete(2, new AssistSuggestion("test", "test-model-v1", "test-prompt-v1",
                List.of(new AssistSuggestion.Claim(MODEL_TEXT, List.of(SOURCE))), new BigDecimal("0.85")), CREATED.plusSeconds(1));
        runs.update(run, 2);
        if (state == AssistRun.Status.ADOPTED) run.adopt(3, app.version(), "alice", "人工核对后的摘要", "合成复核记录", CREATED.plusSeconds(2));
        else if (state == AssistRun.Status.DISMISSED) run.dismiss(3, "alice", "依据不足", CREATED.plusSeconds(2));
        if (run.version() == 4) runs.update(run, 3);
        return run;
    }
    private JsonNode page(Application app, String user, Map<String, String> parameters) throws Exception {
        var request = get(path(app)).header("Authorization", token(user)); parameters.forEach(request::param);
        return read(request, 200);
    }
    private JsonNode detail(Application app, AssistRun run, String user, int expected) throws Exception {
        return read(get(path(app) + "/" + run.id()).header("Authorization", token(user)), expected);
    }
    private JsonNode read(MockHttpServletRequestBuilder request, int expected) throws Exception {
        var result = mvc.perform(request).andExpect(status().is(expected));
        if (expected == 200) result.andExpect(header().string("Cache-Control", "no-store"));
        return json.read(result.andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String path(Application app) { return "/api/v1/applications/" + app.id() + "/assist-runs"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
