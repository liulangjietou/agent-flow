package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 比较接口读取受租户保护的真实版本，不改变定义、审批和引擎状态。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-comparison;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionComparisonIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired DefinitionApplicationService service;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void unchangedAndUnsavedSnapshotsUseRealBaselineWithoutWriting() throws Exception {
        var baseline = published("demo");
        Map<String, Object> body = body(baseline);
        var before = snapshot();
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("baseline.id").value(baseline.id().toString()))
                .andExpect(jsonPath("baseline.version").value(1)).andExpect(jsonPath("changes").isEmpty());
        body.put("name", "未保存的新名称");
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("changes[0].property").value("name"))
                .andExpect(jsonPath("changes[0].before").value(baseline.name())).andExpect(jsonPath("changes[0].after").value("未保存的新名称"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(service.get("demo", baseline.id()).name()).isEqualTo(baseline.name());
    }

    @Test
    void anonymousEmployeeAndForeignTenantCannotReadBaseline() throws Exception {
        var baseline = published("foreign");
        String body = json.write(body(baseline));
        var before = snapshot();
        mvc.perform(post(path(baseline)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path(baseline)).header("Authorization", token("employee")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"))
                .andExpect(jsonPath("baseline").doesNotExist());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void rejectsWrongKeyDraftBaselineAndMissingSnapshotFields() throws Exception {
        var baseline = published("demo");
        var body = body(baseline);
        body.put("key", "different-process");
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("COMPARISON_KEY_MISMATCH"));
        var draft = service.create("demo", baseline.key(), baseline.name(), baseline.graph(), baseline.formSchema());
        mvc.perform(post(path(draft)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body(draft))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("COMPARISON_BASELINE_REQUIRED"));
        body.remove("graph");
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
    }

    @Test
    void nullFormIsExplicitRemovalAndDuplicateNodeCannotHideChanges() throws Exception {
        var baseline = published("demo");
        var body = body(baseline);
        body.put("formSchema", null);
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("changes[0].property").value("schemaBinding"))
                .andExpect(jsonPath("changes[0].before").value(true)).andExpect(jsonPath("changes[0].after").value(false));
        var node = baseline.graph().nodes().get(0);
        body.put("graph", new DefinitionModels.Graph(List.of(node, node), List.of()));
        mvc.perform(post(path(baseline)).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("COMPARISON_ID_AMBIGUOUS"));
    }

    private DefinitionModels.DefinitionDraft published(String tenant) {
        var template = catalog.get("leave-request");
        var draft = service.create(tenant, "compare-" + UUID.randomUUID(), "比较基线", template.graph(), template.formSchema());
        return service.publish(new io.agentflow.common.Actor(tenant, "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "集成测试发布");
    }
    private Map<String, Object> body(DefinitionModels.DefinitionDraft draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", draft.key()); body.put("name", draft.name()); body.put("graph", draft.graph()); body.put("formSchema", draft.formSchema());
        return body;
    }
    private String path(DefinitionModels.DefinitionDraft draft) { return "/api/v1/process-definitions/" + draft.id() + "/compare"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private Map<String, String> snapshot() {
        Map<String, String> data = new LinkedHashMap<>();
        for (String table : List.of("approval_definition", "approval_application", "audit_event", "request_idempotency", "ACT_RU_EXECUTION", "ACT_RU_TASK")) {
            data.put(table, json.write(jdbc.query("SELECT * FROM " + table, (row, index) -> {
                List<String> values = new java.util.ArrayList<>();
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) values.add(row.getString(column));
                return values;
            })));
        }
        return data;
    }
}
