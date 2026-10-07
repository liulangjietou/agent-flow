package io.agentflow.definition;

import io.agentflow.common.Actor;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证持久草稿经真实 Flowable 部署后保留图形，审批语义仍由原流程图决定。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-diagram;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
class DefinitionDiagramIntegrationTest {
    @Autowired DefinitionApplicationService service;
    @Autowired RepositoryService repository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired io.agentflow.common.JsonUtil json;
    @Autowired DefinitionAvailabilityService availability;
    @Autowired io.agentflow.servicetask.ServiceTaskDefinitionSnapshots snapshots;

    @Test
    void savedCoordinatesBecomeEngineShapesAndEdges() {
        var graph = graph("84.5");
        var draft = service.create("demo", "diagram-" + UUID.randomUUID(), "布局发布", graph);
        assertThat(service.get("demo", draft.id()).graph()).isEqualTo(graph);
        service.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "核对图形导出");
        var definition = repository.createProcessDefinitionQuery().processDefinitionKey(draft.key()).processDefinitionTenantId("demo").singleResult();
        var model = repository.getBpmnModel(definition.getId());
        assertThat(model.getLocationMap()).containsOnlyKeys("start", "review", "end");
        var start = model.getGraphicInfo("start");
        assertThat(start.getX()).isEqualTo(84.5);
        assertThat(start.getY()).isEqualTo(90);
        assertThat(start.getWidth()).isEqualTo(67);
        assertThat(start.getHeight()).isEqualTo(42);
        assertThat(model.getFlowLocationGraphicInfo("first")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(model.getMainProcess().getFlowElement("review")).isInstanceOf(org.flowable.bpmn.model.UserTask.class);
    }

    @Test
    void invalidCoordinateIsReportedBeforeDeployment() {
        assertThat(service.validate(graph("NaN"))).contains("DIAGRAM_NODE_POSITION_INVALID:start");
    }

    @Test
    void oldPublishedGraphAndServiceBindingDigestRemainIdenticalWhenAvailabilityChanges() {
        var actor = new Actor("demo", "admin", Set.of("ADMIN"));
        var draft = service.create("demo", "diagram-" + UUID.randomUUID(), "旧定义保持", graph("84.5"));
        var published = service.publish(actor, draft.id(), draft.revision(), "原发布");
        var tree = json.read(jdbc.queryForObject("SELECT graph_json FROM approval_definition WHERE id=?", String.class, draft.id().toString()),
                com.fasterxml.jackson.databind.JsonNode.class);
        tree.path("edges").forEach(edge -> ((com.fasterxml.jackson.databind.node.ObjectNode) edge).remove("waypoints"));
        String original = json.write(tree);
        jdbc.update("UPDATE approval_definition SET graph_json=? WHERE id=?", original, draft.id().toString());
        String digest = snapshots.find("demo", draft.key(), published.version()).digest();
        var disabled = availability.change(actor, draft.id(), published.revision(), false, "暂停新发起");
        availability.change(actor, draft.id(), disabled.revision(), true, "恢复新发起");
        assertThat(jdbc.queryForObject("SELECT graph_json FROM approval_definition WHERE id=?", String.class, draft.id().toString())).isEqualTo(original);
        assertThat(snapshots.find("demo", draft.key(), published.version()).digest()).isEqualTo(digest);
    }

    @Test
    void explicitWaypointsSurviveStorageAndNewVersionWithoutChangingOldDeployment() throws Exception {
        var initial = graph("84.5");
        var points = List.of(new DiagramPoint(151.5, 111.0), new DiagramPoint(200.25, 111.0),
                new DiagramPoint(200.25, 172.0), new DiagramPoint(300.0, 172.0));
        var first = initial.edges().get(0);
        var graph = new Graph(initial.nodes(), List.of(new Edge(first.id(), first.source(), first.target(), first.condition(), false, points), initial.edges().get(1)));
        var draft = service.create("demo", "diagram-" + UUID.randomUUID(), "拐点发布", graph);
        assertThat(service.get("demo", draft.id()).graph().edges().get(0).waypoints()).isEqualTo(points);
        service.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "第一版图形");
        var definition = repository.createProcessDefinitionQuery().processDefinitionKey(draft.key()).processDefinitionVersion(1).singleResult();
        byte[] oldXml;
        try (var resource = repository.getProcessModel(definition.getId())) { oldXml = resource.readAllBytes(); }
        // 引擎内存中的连线位置会转换为整数；精确小数以实际部署资源为准。
        assertThat(repository.getBpmnModel(definition.getId()).getFlowLocationGraphicInfo(first.id())).hasSize(points.size());
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(oldXml));
        var path = (org.w3c.dom.Element) document.getElementsByTagName("bpmndi:BPMNEdge").item(0);
        assertThat(path.getAttribute("bpmnElement")).isEqualTo(first.id());
        var elements = path.getElementsByTagName("di:waypoint"); var deployedPoints = new java.util.ArrayList<DiagramPoint>();
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (org.w3c.dom.Element) elements.item(index);
            deployedPoints.add(new DiagramPoint(Double.valueOf(element.getAttribute("x")), Double.valueOf(element.getAttribute("y"))));
        }
        assertThat(deployedPoints).containsExactlyElementsOf(points);
        var next = service.create("demo", draft.key(), "第二版图形", graph("120"));
        service.publish(new Actor("demo", "admin", Set.of("ADMIN")), next.id(), next.revision(), "只改变布局");
        try (var resource = repository.getProcessModel(definition.getId())) { assertThat(resource.readAllBytes()).isEqualTo(oldXml); }
        assertThat(repository.getBpmnModel(definition.getId()).getGraphicInfo("start").getX()).isEqualTo(84.5);
    }

    @Test
    void diagramIdentifiersDoNotCollideWithValidBusinessIdentifiers() throws Exception {
        var graph = new Graph(List.of(new Node("agentflow_diagram", "开始", NodeType.START, Map.of()),
                new Node("agentflow_shape_agentflow_diagram", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("agentflow_plane", "结束", NodeType.END, Map.of())),
                List.of(new Edge("agentflow_edge_last", "agentflow_diagram", "agentflow_shape_agentflow_diagram", ""),
                        new Edge("last", "agentflow_shape_agentflow_diagram", "agentflow_plane", "")));
        var draft = service.create("demo", "diagram-" + UUID.randomUUID(), "标识碰撞", graph);
        service.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), draft.revision(), "图形标识隔离");
        var definition = repository.createProcessDefinitionQuery().processDefinitionKey(draft.key()).singleResult();
        try (var resource = repository.getProcessModel(definition.getId())) {
            var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            var all = document.getElementsByTagName("*"); var ids = new java.util.HashSet<String>();
            for (int index = 0; index < all.getLength(); index++) {
                var element = (org.w3c.dom.Element) all.item(index);
                if (element.hasAttribute("id")) assertThat(ids.add(element.getAttribute("id"))).as(element.getTagName()).isTrue();
            }
        }
    }

    private Graph graph(String x) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of("x", x, "y", "90")),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("x", "300", "y", "140", "assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of("x", "580", "y", "90"))),
                List.of(new Edge("first", "start", "review", ""), new Edge("last", "review", "end", "")));
    }
}
