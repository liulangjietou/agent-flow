package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 按稳定标识比较定义配置，不执行流程，也不要求编辑中的图已经具备发布条件。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionDiffService {
    private static final Set<String> LAYOUT_PROPERTIES = Set.of("x", "y");

    /** 比较两个不可变快照；字段改名按删除与新增处理，不推测用户意图。 */
    public List<Change> compare(Snapshot before, Snapshot after) {
        List<Change> changes = new ArrayList<>();
        modified(changes, Area.DEFINITION, "", after.name(), "name", before.name(), after.name());
        compareNodes(changes, before.graph(), after.graph());
        compareEdges(changes, before.graph(), after.graph());
        compareForm(changes, before.formSchema(), after.formSchema());
        return List.copyOf(changes);
    }

    private void compareNodes(List<Change> changes, Graph before, Graph after) {
        Map<String, Node> oldNodes = index(before.nodes(), Node::id);
        Map<String, Node> newNodes = index(after.nodes(), Node::id);
        for (String id : union(oldNodes, newNodes)) {
            Node oldNode = oldNodes.get(id), newNode = newNodes.get(id);
            String label = newNode == null ? oldNode.name() : newNode.name();
            if (entity(changes, Area.NODE, id, label, oldNode, newNode)) continue;
            modified(changes, Area.NODE, id, label, "name", oldNode.name(), newNode.name());
            modified(changes, Area.NODE, id, label, "type", oldNode.type(), newNode.type());
            for (String property : union(oldNode.properties(), newNode.properties())) {
                Area area = LAYOUT_PROPERTIES.contains(property) ? Area.LAYOUT : Area.NODE;
                modified(changes, area, id, label, "properties." + property,
                        oldNode.properties().get(property), newNode.properties().get(property));
            }
        }
    }

    private void compareEdges(List<Change> changes, Graph before, Graph after) {
        Map<String, Edge> oldEdges = index(before.edges(), Edge::id);
        Map<String, Edge> newEdges = index(after.edges(), Edge::id);
        for (String id : union(oldEdges, newEdges)) {
            Edge oldEdge = oldEdges.get(id), newEdge = newEdges.get(id);
            if (entity(changes, Area.EDGE, id, id, oldEdge, newEdge)) continue;
            modified(changes, Area.EDGE, id, id, "source", oldEdge.source(), newEdge.source());
            modified(changes, Area.EDGE, id, id, "target", oldEdge.target(), newEdge.target());
            modified(changes, Area.EDGE, id, id, "condition", oldEdge.condition(), newEdge.condition());
            modified(changes, Area.EDGE, id, id, "defaultBranch", oldEdge.defaultBranch(), newEdge.defaultBranch());
        }
        Map<String, List<String>> oldOrder = branchOrder(before), newOrder = branchOrder(after);
        for (String source : union(oldOrder, newOrder)) {
            List<String> oldBranches = oldOrder.getOrDefault(source, List.of());
            List<String> newBranches = newOrder.getOrDefault(source, List.of());
            // 跨网关的全局连线重排没有语义变化；默认分支不参与首匹配优先级。
            if (oldBranches.size() > 1 || newBranches.size() > 1) {
                modified(changes, Area.ROUTING, source, source, "branchOrder", oldBranches, newBranches);
            }
        }
    }

    private Map<String, List<String>> branchOrder(Graph graph) {
        Set<String> gateways = new LinkedHashSet<>();
        graph.nodes().stream().filter(node -> node.type() == NodeType.EXCLUSIVE_GATEWAY).forEach(node -> gateways.add(node.id()));
        Map<String, List<String>> order = new LinkedHashMap<>();
        graph.edges().stream().filter(edge -> gateways.contains(edge.source()) && !edge.defaultBranch())
                .forEach(edge -> order.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.id()));
        order.replaceAll((source, ids) -> List.copyOf(ids));
        return order;
    }

    private void compareForm(List<Change> changes, FormSchema before, FormSchema after) {
        modified(changes, Area.FORM, "", "", "schemaBinding", before != null, after != null);
        if (before != null && after != null) {
            modified(changes, Area.FORM, "", "", "schemaVersion", before.schemaVersion(), after.schemaVersion());
        }
        Map<String, FormSchema.Field> oldFields = index(before == null ? List.of() : before.fields(), FormSchema.Field::key);
        Map<String, FormSchema.Field> newFields = index(after == null ? List.of() : after.fields(), FormSchema.Field::key);
        modified(changes, Area.FORM, "", "", "fieldOrder", List.copyOf(oldFields.keySet()), List.copyOf(newFields.keySet()));
        for (String key : union(oldFields, newFields)) {
            FormSchema.Field oldField = oldFields.get(key), newField = newFields.get(key);
            String label = newField == null ? oldField.label() : newField.label();
            if (entity(changes, Area.FIELD, key, label, oldField, newField)) continue;
            modified(changes, Area.FIELD, key, label, "label", oldField.label(), newField.label());
            modified(changes, Area.FIELD, key, label, "type", oldField.type(), newField.type());
            modified(changes, Area.FIELD, key, label, "required", oldField.required(), newField.required());
            modified(changes, Area.FIELD, key, label, "helpText", oldField.helpText(), newField.helpText());
            modified(changes, Area.FIELD, key, label, "maxLength", oldField.maxLength(), newField.maxLength());
            modified(changes, Area.FIELD, key, label, "minimum", oldField.minimum(), newField.minimum());
            modified(changes, Area.FIELD, key, label, "maximum", oldField.maximum(), newField.maximum());
            modified(changes, Area.FIELD, key, label, "options", oldField.options(), newField.options());
        }
    }

    private boolean entity(List<Change> changes, Area area, String id, String label, Object before, Object after) {
        if (before != null && after != null) return false;
        changes.add(new Change(area, before == null ? ChangeKind.ADDED : ChangeKind.REMOVED, id, label, "entity", before, after));
        return true;
    }

    private void modified(List<Change> changes, Area area, String id, String label, String property, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.add(new Change(area, ChangeKind.MODIFIED, id, label, property, before, after));
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> identity) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : values) {
            String id = identity.apply(value);
            if (indexed.put(id, value) != null) throw new DomainException("COMPARISON_ID_AMBIGUOUS", "Comparison requires unique object identifiers");
        }
        return indexed;
    }

    private Set<String> union(Map<String, ?> before, Map<String, ?> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys;
    }

    /**
     * 定义配置快照，不包含租户数据或申请内容。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(String name, Graph graph, FormSchema formSchema) { }

    /**
     * 布局单独分组，避免与执行配置混淆。
     * @author owlzhangfq@gmail.com
     */
    public enum Area { DEFINITION, NODE, EDGE, ROUTING, FORM, FIELD, LAYOUT }

    /**
     * 稳定对象的增删与属性修改。
     * @author owlzhangfq@gmail.com
     */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED }

    /**
     * 前后值仅为不可变模型、标量或不可变顺序列表；缺值表示该侧没有配置。
     * @author owlzhangfq@gmail.com
     */
    public record Change(Area area, ChangeKind kind, String targetId, String label, String property, Object before, Object after) { }
}
