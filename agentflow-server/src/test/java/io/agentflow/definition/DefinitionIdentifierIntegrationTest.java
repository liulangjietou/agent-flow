package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;
import static io.agentflow.definition.DefinitionModels.DraftStatus;
import static io.agentflow.definition.DefinitionModels.Edge;
import static io.agentflow.definition.DefinitionModels.Graph;
import static io.agentflow.definition.DefinitionModels.Node;
import static io.agentflow.definition.DefinitionModels.NodeType;
import static io.agentflow.support.MutationRequests.post;
import static io.agentflow.support.MutationRequests.put;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预检、保存和历史草稿发布均在引擎部署前报告标识冲突，并保持原状态可修正。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-identifiers;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionIdentifierIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService service;
    @Autowired DefinitionDraftRepository drafts;
    @Autowired DefinitionPublicationRepository publications;
    @Autowired RepositoryService engine;

    @ParameterizedTest
    @ValueSource(strings = {"node-edge", "process-node", "process-edge", "invalid-node", "invalid-edge", "invalid-process"})
    void previewReportsEveryIdentifierNamespaceConflict(String scenario) throws Exception {
        String key = key(scenario);
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token())
                        .contentType("application/json").content(json.write(Map.of("key", key, "graph", graph(scenario)))))
                .andExpect(status().isOk()).andExpect(jsonPath("errors", hasItem(error(scenario))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"node-edge", "process-node", "process-edge", "invalid-node", "invalid-edge"})
    void invalidCreationAndUpdateNeverPersistPartialState(String scenario) throws Exception {
        String key = key(scenario);
        int originalCount = drafts.findAll("demo", null).size();
        mvc.perform(post("/api/v1/process-definitions").header("Authorization", token()).contentType("application/json")
                        .content(json.write(Map.of("key", key, "name", "非法创建", "graph", graph(scenario)))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_DEFINITION"))
                .andExpect(jsonPath("details.definitionErrors", hasItem(error(scenario))));
        assertThat(drafts.findAll("demo", null)).hasSize(originalCount);

        var original = service.create("demo", key, "原草稿", validGraph());
        mvc.perform(put("/api/v1/process-definitions/" + original.id()).header("Authorization", token()).contentType("application/json")
                        .content(json.write(Map.of("name", "错误更新", "graph", graph(scenario), "expectedRevision", 0))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("details.definitionErrors", hasItem(error(scenario))));
        var unchanged = service.get("demo", original.id());
        assertThat(unchanged.name()).isEqualTo(original.name());
        assertThat(unchanged.graph()).isEqualTo(original.graph());
        assertThat(unchanged.revision()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"node-edge", "process-node", "process-edge", "invalid-node", "invalid-edge"})
    void legacyDraftCanBeCorrectedAfterPublicationIsRejectedBeforeDeployment(String scenario) throws Exception {
        String key = key(scenario);
        // 仓储中模拟旧版本已经接受的非法草稿，不经过新版创建用例。
        var legacy = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "历史冲突草稿", graph(scenario));
        drafts.save(legacy);
        long engineDefinitions = engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(key).count();
        mvc.perform(post("/api/v1/process-definitions/" + legacy.id() + "/publish?expectedRevision=0")
                        .header("Authorization", token()).contentType("application/json").content("{\"changeNote\":\"校验旧草稿\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("details.definitionErrors", hasItem(error(scenario))));
        var unchanged = service.get("demo", legacy.id());
        assertThat(unchanged.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(unchanged.revision()).isZero();
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.graph()).isEqualTo(legacy.graph());
        assertThat(publications.findByDefinition("demo", legacy.id())).isEmpty();
        assertThat(engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(key).count()).isEqualTo(engineDefinitions);

        service.update("demo", legacy.id(), "已修正", validGraph(), 0);
        var published = service.publish(new Actor("demo", "admin", Set.of("ADMIN")), legacy.id(), 1, "修正重复标识");
        assertThat(published.status()).isEqualTo(DraftStatus.PUBLISHED);
        assertThat(published.version()).isEqualTo(engineDefinitions + 1);
    }

    @Test
    void graphOnlyPreviewRemainsCompatibleWhenNoProcessKeyIsProvided() throws Exception {
        mvc.perform(post("/api/v1/process-definitions/validate").header("Authorization", token()).contentType("application/json")
                        .content(json.write(Map.of("graph", validGraph()))))
                .andExpect(status().isOk()).andExpect(jsonPath("errors").isEmpty());
    }

    @Test
    void invalidProcessKeyIsRejectedAtCreationAndLegacyPublicationWithoutChangingItsIdentity() throws Exception {
        String key = "0-" + UUID.randomUUID();
        int originalCount = drafts.findAll("demo", null).size();
        mvc.perform(post("/api/v1/process-definitions").header("Authorization", token()).contentType("application/json")
                        .content(json.write(Map.of("key", key, "name", "非法流程标识", "graph", validGraph()))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("details.definitionErrors", hasItem("INVALID_PROCESS_KEY:" + key)));
        assertThat(drafts.findAll("demo", null)).hasSize(originalCount);
        var legacy = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "历史非法流程标识", validGraph());
        drafts.save(legacy);
        mvc.perform(post("/api/v1/process-definitions/" + legacy.id() + "/publish?expectedRevision=0")
                        .header("Authorization", token()).contentType("application/json").content("{\"changeNote\":\"保留原标识\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("details.definitionErrors", hasItem("INVALID_PROCESS_KEY:" + key)));
        var unchanged = service.get("demo", legacy.id());
        assertThat(unchanged.key()).isEqualTo(key);
        assertThat(unchanged.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(unchanged.revision()).isZero();
        assertThat(unchanged.version()).isZero();
        assertThat(publications.findByDefinition("demo", legacy.id())).isEmpty();
        assertThat(engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(key).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"审批节点", "équipe", "_review-1.2", "a\u0301", "a\u00b7b", "xmlns"})
    void validUnicodeIdentifiersKeepTheirOriginalTextThroughRealEngineDeployment(String id) {
        String key = "流程-" + UUID.randomUUID();
        Graph graph = new Graph(List.of(new Node("begin", "开始", NodeType.START, Map.of()),
                new Node(id, "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("finish", "结束", NodeType.END, Map.of())),
                List.of(new Edge("入线-" + id, "begin", id, ""), new Edge("出线", id, "finish", "")));
        var draft = service.create("demo", key, "合法原标识", graph);
        var published = service.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "核对字符原样保留");
        var engineDefinition = engine.createProcessDefinitionQuery().processDefinitionTenantId("demo").processDefinitionKey(key).singleResult();
        var model = engine.getBpmnModel(engineDefinition.getId()).getMainProcess();
        assertThat(model.getId()).isEqualTo(key);
        assertThat(model.getFlowElement(id).getId()).isEqualTo(id);
        assertThat(model.getFlowElement("入线-" + id).getId()).isEqualTo("入线-" + id);
        assertThat(published.graph()).isEqualTo(graph);
    }

    private String key(String scenario) {
        return switch (scenario) {
            case "process-node" -> "approve";
            case "process-edge" -> "first";
            case "invalid-process" -> "0";
            default -> "identifier-" + UUID.randomUUID();
        };
    }

    private String error(String scenario) {
        return switch (scenario) {
            case "process-node" -> "PROCESS_KEY_CONFLICT:approve";
            case "process-edge" -> "PROCESS_KEY_CONFLICT:first";
            case "invalid-node" -> "INVALID_NODE_ID:0";
            case "invalid-edge" -> "INVALID_EDGE_ID:0";
            case "invalid-process" -> "INVALID_PROCESS_KEY:0";
            default -> "NODE_EDGE_ID_CONFLICT:approve";
        };
    }

    private Graph graph(String scenario) {
        var original = validGraph();
        String nodeId = scenario.equals("invalid-node") ? "0" : "approve";
        String edgeId = scenario.equals("node-edge") ? "approve" : scenario.equals("invalid-edge") ? "0" : "first";
        return new Graph(List.of(original.nodes().get(0), new Node(nodeId, "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")), original.nodes().get(2)),
                List.of(new Edge(edgeId, "begin", nodeId, ""), new Edge("last", nodeId, "finish", "")));
    }

    private Graph validGraph() {
        return new Graph(List.of(new Node("begin", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("finish", "结束", NodeType.END, Map.of())),
                List.of(new Edge("route-review", "begin", "review", ""), new Edge("route-finish", "review", "finish", "")));
    }

    private String token() { return "Bearer " + auth.login("demo", "admin", "demo").token(); }
}
