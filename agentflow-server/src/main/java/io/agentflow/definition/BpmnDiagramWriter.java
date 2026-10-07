package io.agentflow.definition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static io.agentflow.definition.DefinitionModels.*;

/**
 * 受限定义的 BPMN DI 输出，图形标识独立生成，不修改任何流程元素。
 * @author owlzhangfq@gmail.com
 */
final class BpmnDiagramWriter {
    private BpmnDiagramWriter() { }

    static void append(StringBuilder xml, Graph graph, String processKey, Collection<String> messageIds) {
        var used = new HashSet<>(messageIds);
        used.add(processKey);
        graph.nodes().forEach(node -> used.add(node.id()));
        graph.edges().forEach(edge -> used.add(edge.id()));
        var boxes = new LinkedHashMap<String, DefinitionDiagram.Bounds>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            var node = graph.nodes().get(index);
            boxes.put(node.id(), DefinitionDiagram.bounds(node, index));
        }
        xml.append("<bpmndi:BPMNDiagram id=\"").append(identity("agentflow_diagram", used)).append("\">")
                .append("<bpmndi:BPMNPlane id=\"").append(identity("agentflow_plane", used)).append("\" bpmnElement=\"")
                .append(processKey).append("\">");
        boxes.forEach((id, box) -> xml.append("<bpmndi:BPMNShape id=\"").append(identity("agentflow_shape_" + id, used))
                .append("\" bpmnElement=\"").append(id).append("\"><dc:Bounds x=\"").append(box.x())
                .append("\" y=\"").append(box.y()).append("\" width=\"").append(box.width())
                .append("\" height=\"").append(box.height()).append("\"/></bpmndi:BPMNShape>"));
        for (var edge : graph.edges()) {
            xml.append("<bpmndi:BPMNEdge id=\"").append(identity("agentflow_edge_" + edge.id(), used))
                    .append("\" bpmnElement=\"").append(edge.id()).append("\">");
            var points = edge.waypoints().isEmpty() ? fallback(boxes.get(edge.source()), boxes.get(edge.target())) : edge.waypoints();
            for (var point : points) xml.append("<di:waypoint x=\"").append(point.x()).append("\" y=\"").append(point.y()).append("\"/>");
            xml.append("</bpmndi:BPMNEdge>");
        }
        xml.append("</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>");
    }

    /** 缺拐点的旧图补充有序正交线，不回填快照或重排出线。 */
    private static List<DiagramPoint> fallback(DefinitionDiagram.Bounds source, DefinitionDiagram.Bounds target) {
        var start = new DiagramPoint(source.x() + source.width(), source.y() + source.height() / 2);
        var end = new DiagramPoint(target.x(), target.y() + target.height() / 2);
        double middle = (start.x() + end.x()) / 2;
        var points = new ArrayList<DiagramPoint>();
        for (var point : List.of(start, new DiagramPoint(middle, start.y()), new DiagramPoint(middle, end.y()), end)) {
            if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) points.add(point);
        }
        // 零长连线仍有两个端点，不能压缩为不符合 DI 契约的单点路径。
        if (points.size() == 1) points.add(end);
        return points;
    }

    /** 来源标识已由统一 NCName 校验；新增 DI 标识同时避开业务和消息标识。 */
    private static String identity(String candidate, Set<String> used) {
        while (!used.add(candidate)) candidate = "_" + candidate;
        return candidate;
    }
}
