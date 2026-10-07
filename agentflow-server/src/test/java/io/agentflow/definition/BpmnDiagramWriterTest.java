package io.agentflow.definition;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 旧图缺省连线的退化几何仍满足 BPMN DI 的两端点契约。 */
class BpmnDiagramWriterTest {
    @Test
    void coincidentLegacyEndpointsRetainBothEnds() {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of("x", "80", "y", "60")),
                new Node("end", "结束", NodeType.END, Map.of("x", "147", "y", "60"))),
                List.of(new Edge("flow", "start", "end", "")));
        var xml = new StringBuilder();
        BpmnDiagramWriter.append(xml, graph, "diagram", List.of());
        assertThat(xml.toString().split("<di:waypoint", -1).length - 1).isEqualTo(2);
        assertThat(graph.edges().get(0).waypoints()).isEmpty();
    }
}
