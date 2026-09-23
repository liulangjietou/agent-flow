package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 未保存设计试算不落库，并与同图的真实 Flowable 路由一致。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-preview;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionPreviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired DefinitionApplicationService definitions;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void previewIsReadOnlyAndReturnsTypedDecisionTrace() throws Exception {
        var template = catalog.get("leave-request");
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("graph", template.graph(),
                                "formSchema", template.formSchema(), "values", values("4")))))
                .andExpect(status().isOk()).andExpect(jsonPath("path[3]").value("review"))
                .andExpect(jsonPath("decisions[0].branches[0].outcome").value("MATCHED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void permissionsAndInvalidTestDataCannotCreateResources() throws Exception {
        var template = catalog.get("leave-request");
        String body = json.write(Map.of("graph", template.graph(), "formSchema", template.formSchema(), "values", Map.of()));
        var before = snapshot();
        mvc.perform(post("/api/v1/process-definitions/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("employee"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.fieldErrors.durationDays").value("REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void structuralErrorsIdentifyObjectsAndAreAlsoRejectedWhenCreatingDrafts() throws Exception {
        Graph bad = new Graph(List.of(new Node("s", "开始", NodeType.START, Map.of()), new Node("e", "结束", NodeType.END, Map.of())),
                List.of(new Edge("one", "s", "e", ""), new Edge("two", "s", "e", "")));
        String body = json.write(Map.of("graph", bad, "values", Map.of()));
        mvc.perform(post("/api/v1/process-definitions/simulate").header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.definitionErrors[0]").value("SINGLE_OUTGOING_REQUIRED:s"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> definitions.create("demo", "invalid-preview", "错误图", bad))
                .isInstanceOf(DefinitionValidationException.class);
    }

    @Test
    void unsavedChangedConditionMatchesActualEngineAtBothSidesOfThreshold() {
        var template = catalog.get("leave-request");
        Graph changed = new Graph(template.graph().nodes(), template.graph().edges().stream().map(edge ->
                edge.condition().isBlank() ? edge : new Edge(edge.id(), edge.source(), edge.target(), "durationDays > 2", false)).toList());
        var draft = definitions.create("demo", "preview-" + UUID.randomUUID(), "模拟一致性", changed, template.formSchema());
        var published = definitions.publish("demo", draft.id(), draft.revision());
        for (String days : List.of("2", "2.5")) {
            var preview = definitions.simulatePreview(changed, template.formSchema(), new EvaluationContext(values(days)));
            var instance = runtime.startProcessInstanceByKeyAndTenantId(published.key(), Map.of("formData", values(days),
                    "formFieldTypes", template.formSchema().fieldTypes()), "demo");
            var manager = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(manager.getTaskDefinitionKey()).isEqualTo("manager");
            tasks.complete(manager.getId());
            var review = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(review != null).isEqualTo(preview.path().contains("review"));
            if (review != null) { assertThat(review.getTaskDefinitionKey()).isEqualTo("review"); tasks.complete(review.getId()); }
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
        }
    }

    private Map<String, Object> values(String days) {
        return Map.of("leaveType", "ANNUAL", "startDate", "2026-09-24", "durationDays", days, "reason", "仅用于模拟验收");
    }

    private Map<String, Integer> snapshot() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String table : List.of("approval_definition", "approval_application", "audit_event", "request_idempotency", "ACT_RU_EXECUTION", "ACT_RU_TASK")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
