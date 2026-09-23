package io.agentflow.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.auth.AuthService;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.Edge;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static io.agentflow.support.MutationRequests.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证流程定义的租户可见性和管理员写权限。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:definition-security;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"
})
@AutoConfigureMockMvc
class DefinitionSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService authService;
    @Autowired ObjectMapper mapper;
    @Autowired DefinitionApplicationService service;

    @Test
    void employeeCannotCreateOrReadDraftDefinitions() throws Exception {
        String key = "security-draft-" + UUID.randomUUID();
        DefinitionDraft draft = service.create("demo", key, "仅管理员可见", graph());
        String token = token("employee");

        mvc.perform(post("/api/v1/process-definitions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "key", key + "-new", "name", "非法创建", "graph", graph()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/process-definitions").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + draft.id() + "')]").isEmpty());
        mvc.perform(get("/api/v1/process-definitions/" + draft.id()).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void processAdminCanCreateDefinition() throws Exception {
        mvc.perform(post("/api/v1/process-definitions")
                        .header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "key", "security-admin-" + UUID.randomUUID(), "name", "管理员创建", "graph", graph()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("DRAFT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user:${'alice'.toUpperCase()}", "role:#{'FINANCE'}", "user:alice,bob"})
    void refusesExecutableOrMultipleAssigneeIdentifiers(String rule) throws Exception {
        mvc.perform(post("/api/v1/process-definitions")
                        .header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "key", "security-rule-" + UUID.randomUUID(), "name", "审批人安全校验",
                                "graph", graph("审批", rule)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("INVALID_DEFINITION"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"${authService.login('demo','admin','demo').token()}", "审批 #{'FINANCE'}"})
    void refusesTaskNamesThatFlowableWouldEvaluate(String name) throws Exception {
        mvc.perform(post("/api/v1/process-definitions")
                        .header("Authorization", token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "key", "security-name-" + UUID.randomUUID(), "name", "节点名称安全校验",
                                "graph", graph(name, "role:FINANCE")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("INVALID_DEFINITION"));
    }

    @Test
    void simulationUsesTheSameDraftVisibilityAsReading() throws Exception {
        DefinitionDraft draft = service.create("demo", "security-simulation-" + UUID.randomUUID(), "待发布流程", graph());
        String url = "/api/v1/process-definitions/" + draft.id() + "/simulate";

        mvc.perform(post(url).header("Authorization", token("employee")))
                .andExpect(status().isNotFound());
        mvc.perform(post(url).header("Authorization", token("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("path[1]").value("approve"));

        service.publish(new io.agentflow.common.Actor("demo", "test-admin", java.util.Set.of("ADMIN")), draft.id(), draft.revision(), "集成测试发布");
        mvc.perform(post(url).header("Authorization", token("employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("path[1]").value("approve"));
    }

    private Graph graph() {
        return graph("审批", "role:FINANCE");
    }

    private Graph graph(String taskName, String rule) {
        return new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", taskName, NodeType.USER_TASK, Map.of("assigneeRule", rule)),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("start-approve", "start", "approve", ""),
                        new Edge("approve-end", "approve", "end", "")));
    }

    private String token(String user) {
        return "Bearer " + authService.login("demo", user, "demo").token();
    }
}
