package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.definition.DefinitionDiffService.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 比较配置身份、执行顺序、表单约束与旧快照，不把展示重排误认为路由变化。
 * @author owlzhangfq@gmail.com
 */
class DefinitionDiffServiceTest {
    private final DefinitionDiffService service = new DefinitionDiffService();

    @Test
    void identicalSnapshotsAndGlobalEdgeReorderingHaveNoChanges() {
        Graph before = graph(List.of(edge("one", "gate", "a", "amount > 1", false),
                edge("two", "gate", "b", "amount > 2", false), edge("outside", "a", "end", "", false)));
        Graph reordered = graph(List.of(before.edges().get(2), before.edges().get(0), before.edges().get(1)));
        assertThat(service.compare(snapshot(before, null), snapshot(before, null))).isEmpty();
        assertThat(service.compare(snapshot(before, null), snapshot(reordered, null))).isEmpty();
    }

    @Test
    void detectsFirstMatchOrderButIgnoresDefaultPosition() {
        Edge one = edge("one", "gate", "a", "amount > 1", false);
        Edge two = edge("two", "gate", "b", "amount > 2", false);
        Edge fallback = edge("fallback", "gate", "end", "", true);
        Snapshot before = snapshot(graph(List.of(one, two, fallback)), null);
        assertThat(service.compare(before, snapshot(graph(List.of(fallback, one, two)), null))).isEmpty();
        var changes = service.compare(before, snapshot(graph(List.of(two, one, fallback)), null));
        assertThat(changes).containsExactly(new Change(Area.ROUTING, ChangeKind.MODIFIED, "gate", "gate", "branchOrder",
                List.of("one", "two"), List.of("two", "one")));
    }

    @Test
    void identifiesNodeRemovalAdditionAssigneeLayoutAndEdgeProperties() {
        Node oldNode = new Node("approve", "原审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER", "x", "40"));
        Node changedNode = new Node("approve", "新审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "x", "80"));
        Graph before = new Graph(List.of(oldNode, new Node("removed", "旧节点", NodeType.END, Map.of())),
                List.of(edge("route", "approve", "removed", "amount > 1", false)));
        Graph after = new Graph(List.of(changedNode, new Node("added", "新节点", NodeType.END, Map.of())),
                List.of(edge("route", "approve", "added", "", true)));
        var changes = service.compare(snapshot(before, null), snapshot(after, null));
        assertThat(changes).anySatisfy(change -> { assertThat(change.targetId()).isEqualTo("removed"); assertThat(change.kind()).isEqualTo(ChangeKind.REMOVED); });
        assertThat(changes).anySatisfy(change -> { assertThat(change.targetId()).isEqualTo("added"); assertThat(change.kind()).isEqualTo(ChangeKind.ADDED); });
        assertThat(changes).contains(new Change(Area.NODE, ChangeKind.MODIFIED, "approve", "新审批", "properties.assigneeRule", "role:MANAGER", "role:FINANCE"));
        assertThat(changes).contains(new Change(Area.LAYOUT, ChangeKind.MODIFIED, "approve", "新审批", "properties.x", "40", "80"));
        assertThat(changes).filteredOn(change -> change.area() == Area.EDGE).extracting(Change::property)
                .containsExactly("target", "condition", "defaultBranch");
        assertThat(before.nodes().get(0)).isEqualTo(oldNode);
        assertThatThrownBy(() -> changes.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void comparesExactFormConstraintsOptionsAndFieldOrder() {
        var number = field("amount", "金额", FormSchema.FieldType.NUMBER, false, null, "1.000000000000000001", null);
        var changed = field("amount", "申请金额", FormSchema.FieldType.NUMBER, true, "填写原币", "1.000000000000000002", null);
        var select = field("category", "类型", FormSchema.FieldType.SELECT, false, null, null,
                List.of(new FormSchema.Option("A", "甲"), new FormSchema.Option("B", "乙")));
        var reordered = field("category", "类型", FormSchema.FieldType.SELECT, false, null, null,
                List.of(new FormSchema.Option("B", "乙改"), new FormSchema.Option("A", "甲")));
        Graph graph = graph(List.of());
        var changes = service.compare(snapshot(graph, new FormSchema(1, List.of(number, select))),
                snapshot(graph, new FormSchema(1, List.of(reordered, changed))));
        assertThat(changes).extracting(Change::property).containsExactly("fieldOrder", "label", "required", "helpText", "minimum", "options");
        assertThat(changes).anySatisfy(change -> { assertThat(change.property()).isEqualTo("minimum"); assertThat(change.after()).isEqualTo("1.000000000000000002"); });
    }

    @Test
    void absentFormAndExplicitEmptyFormAreDifferentAndFieldRenameIsRemovalPlusAddition() {
        Graph graph = graph(List.of());
        assertThat(service.compare(snapshot(graph, null), snapshot(graph, new FormSchema(1, List.of()))))
                .containsExactly(new Change(Area.FORM, ChangeKind.MODIFIED, "", "", "schemaBinding", false, true));
        var before = new FormSchema(1, List.of(field("old", "相同名称", FormSchema.FieldType.TEXT, false, null, null, null)));
        var after = new FormSchema(1, List.of(field("new", "相同名称", FormSchema.FieldType.TEXT, false, null, null, null)));
        assertThat(service.compare(snapshot(graph, before), snapshot(graph, after))).filteredOn(change -> change.area() == Area.FIELD)
                .extracting(Change::kind).containsExactly(ChangeKind.REMOVED, ChangeKind.ADDED);
    }

    @Test
    void incompleteDraftCanBeComparedButDuplicateIdentityIsRejected() {
        Graph incomplete = graph(List.of());
        assertThat(service.compare(snapshot(new Graph(List.of(), List.of()), null), snapshot(incomplete, null))).hasSize(1);
        Graph ambiguous = new Graph(List.of(incomplete.nodes().get(0), incomplete.nodes().get(0)), List.of());
        assertThatThrownBy(() -> service.compare(snapshot(incomplete, null), snapshot(ambiguous, null)))
                .isInstanceOf(DomainException.class).hasMessageContaining("unique object identifiers");
    }

    private Snapshot snapshot(Graph graph, FormSchema form) { return new Snapshot("流程", graph, form); }
    private Graph graph(List<Edge> edges) { return new Graph(List.of(new Node("gate", "判断", NodeType.EXCLUSIVE_GATEWAY, Map.of())), edges); }
    private Edge edge(String id, String source, String target, String condition, boolean fallback) { return new Edge(id, source, target, condition, fallback); }
    private FormSchema.Field field(String key, String label, FormSchema.FieldType type, boolean required,
                                   String help, String minimum, List<FormSchema.Option> options) {
        return new FormSchema.Field(key, label, type, required, help, null, minimum, null, options);
    }
}
