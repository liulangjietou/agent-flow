package io.agentflow.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 图形边界及转换保留测试，不以引擎能运行代替图形完整性。 */
class DefinitionDiagramTest {
    @Test
    void legacyGeometryRemainsAbsentAndUsesStableDefaultBounds() {
        var graph = graph(List.of());
        assertThat(DefinitionDiagram.validate(graph)).isEmpty();
        assertThat(DefinitionDiagram.bounds(graph.nodes().get(0), 0)).isEqualTo(new DefinitionDiagram.Bounds(40, 180, 67, 42));
        assertThat(DefinitionDiagram.bounds(graph.nodes().get(1), 1)).isEqualTo(new DefinitionDiagram.Bounds(220, 180, 126, 64));
        assertThat(graph.nodes().get(0).properties()).isEmpty();
        assertThat(graph.edges().get(0).waypoints()).isEmpty();
    }

    @Test
    void invalidNumbersAndUnboundedPathsAreRejectedWhileValidDecimalsAreRetained() {
        for (var points : List.of(List.of(new DiagramPoint(0.0, 0.0)),
                List.of(new DiagramPoint(-1.0, 0.0), new DiagramPoint(2.0, 2.0)),
                List.of(new DiagramPoint(Double.NaN, 0.0), new DiagramPoint(2.0, 2.0)),
                List.of(new DiagramPoint(null, 0.0), new DiagramPoint(2.0, 2.0)),
                java.util.Collections.nCopies(33, new DiagramPoint(2.0, 2.0)))) {
            assertThat(new DefinitionValidator().validate(graph(points))).contains("DIAGRAM_EDGE_WAYPOINTS_INVALID:first");
        }
        var points = new ArrayList<>(List.of(new DiagramPoint(107.5, 201.0), new DiagramPoint(220.0, 212.5)));
        var graph = graph(points);
        points.clear();
        assertThat(new DefinitionValidator().validate(graph)).isEmpty();
        assertThat(graph.edges().get(0).waypoints()).hasSize(2);
        assertThatThrownBy(() -> graph.edges().get(0).waypoints().clear()).isInstanceOf(UnsupportedOperationException.class);
        for (String value : List.of("", "not-a-number", "Infinity", "-1", "1000000")) {
            var invalid = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of("x", value)), graph.nodes().get(1), graph.nodes().get(2)), graph.edges());
            assertThat(DefinitionDiagram.validate(invalid)).contains("DIAGRAM_NODE_POSITION_INVALID:start");
        }
    }

    @Test
    void languageUpgradeAndComparisonKeepWaypointsSeparateFromRouting() {
        var before = graph(List.of());
        var after = graph(List.of(new DiagramPoint(107.0, 201.0), new DiagramPoint(220.0, 212.0)));
        assertThat(new ConditionLanguageUpgrade().upgrade(after).edges().get(0).waypoints()).isEqualTo(after.edges().get(0).waypoints());
        var changes = new DefinitionDiffService().compare(snapshot(before), snapshot(after));
        assertThat(changes).hasSize(1).allSatisfy(change -> {
            assertThat(change.area()).isEqualTo(DefinitionDiffService.Area.LAYOUT);
            assertThat(change.property()).isEqualTo("waypoints");
        });
    }

    private DefinitionDiffService.Snapshot snapshot(Graph graph) {
        return new DefinitionDiffService.Snapshot("流程", graph, null);
    }
    private Graph graph(List<DiagramPoint> points) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("first", "start", "review", "", false, points), new Edge("last", "review", "end", "")));
    }
}
