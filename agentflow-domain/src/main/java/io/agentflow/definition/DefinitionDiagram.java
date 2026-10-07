package io.agentflow.definition;

import java.util.ArrayList;
import java.util.List;

import static io.agentflow.definition.DefinitionModels.*;

/** 定义快照的图形边界；与当前画布尺寸一致，不参与审批行为。 */
public final class DefinitionDiagram {
    public static final double MAX_COORDINATE = 1_000_000;
    public static final int MAX_WAYPOINTS = 32;
    private static final double DEFAULT_X = 40, DEFAULT_Y = 180, DEFAULT_SPACING = 180;
    private static final double COMPACT_WIDTH = 67, COMPACT_HEIGHT = 42, NODE_WIDTH = 126, NODE_HEIGHT = 64;

    private DefinitionDiagram() { }

    /** 图形在保存、校验和发布的统一入口验证，旧快照的缺项保持兼容。 */
    public static List<String> validate(Graph graph) {
        var errors = new ArrayList<String>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            var node = graph.nodes().get(index);
            try {
                var box = bounds(node, index);
                if (!coordinate(box.x()) || !coordinate(box.y()) || box.x() + box.width() > MAX_COORDINATE
                        || box.y() + box.height() > MAX_COORDINATE) errors.add("DIAGRAM_NODE_POSITION_INVALID:" + node.id());
            } catch (NumberFormatException invalid) { errors.add("DIAGRAM_NODE_POSITION_INVALID:" + node.id()); }
        }
        for (var edge : graph.edges()) {
            if (edge.waypoints().isEmpty()) continue;
            if (edge.waypoints().size() < 2 || edge.waypoints().size() > MAX_WAYPOINTS
                    || edge.waypoints().stream().anyMatch(point -> !coordinate(point.x()) || !coordinate(point.y()))) {
                errors.add("DIAGRAM_EDGE_WAYPOINTS_INVALID:" + edge.id());
            }
        }
        return List.copyOf(errors);
    }

    /** 已校验的坐标采用原数值；旧图按节点原顺序补充展示位置，不回写历史图。 */
    public static Bounds bounds(Node node, int index) {
        boolean compact = node.type() == NodeType.START || node.type() == NodeType.END;
        return new Bounds(position(node, "x", DEFAULT_X + index * DEFAULT_SPACING), position(node, "y", DEFAULT_Y),
                compact ? COMPACT_WIDTH : NODE_WIDTH, compact ? COMPACT_HEIGHT : NODE_HEIGHT);
    }

    private static double position(Node node, String axis, double fallback) {
        String value = node.properties().get(axis);
        return value == null ? fallback : Double.parseDouble(value);
    }
    private static boolean coordinate(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= MAX_COORDINATE;
    }

    /** 未缩放的逻辑外框，不包含视口或鼠标位置。 */
    public record Bounds(double x, double y, double width, double height) { }
}
