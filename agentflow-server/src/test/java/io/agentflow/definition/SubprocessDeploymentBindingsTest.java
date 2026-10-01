package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 有向依赖的路径、共享后代及有界遍历测试；引擎绑定和事务另由真实数据库验证。
 * @author owlzhangfq@gmail.com
 */
class SubprocessDeploymentBindingsTest {
    private final SubprocessDefinitionResolver resolver = mock(SubprocessDefinitionResolver.class);
    private final Map<String, Graph> graphs = new HashMap<>();
    private final SubprocessDeploymentBindings bindings = new SubprocessDeploymentBindings(resolver, mock(DefinitionReferenceInspector.class));

    @Test
    void ordinaryGraphsDoNotReadTheSubprocessDirectory() {
        assertThat(bindings.bind(root(empty()))).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void rejectsDirectAndIndirectVersionCycles() {
        directory();
        failure(() -> bindings.bind(root(graph(call("self", "root")))), "SUBPROCESS_RECURSION_FORBIDDEN");
        graphs.put("a", graph(call("b", "b"))); graphs.put("b", graph(call("back", "a")));
        failure(() -> bindings.bind(root(graph(call("first", "a")))), "SUBPROCESS_RECURSION_FORBIDDEN");
    }

    @Test
    void acceptsSixteenCallLevelsAndRejectsTheNextLevel() {
        directory(); chain(16);
        assertThat(bindings.bind(root(graph(call("first", "n1"))))).containsEntry("first", "n1:1:fixed");
        chain(17);
        failure(() -> bindings.bind(root(graph(call("first", "n1")))), "SUBPROCESS_DEPTH_EXCEEDED");
    }

    @Test
    void cachedSharedDescendantsStillCountOnADeeperPath() {
        directory(); chain(16);
        graphs.put("detour", graph(call("deep", "n1")));
        failure(() -> bindings.bind(root(graph(call("short", "n1"), call("long", "detour")))), "SUBPROCESS_DEPTH_EXCEEDED");
    }

    @Test
    void sharedSubgraphsAreExpandedOnceWithoutSkippingEitherRootBinding() {
        directory(); graphs.put("a", graph(call("same", "leaf"))); graphs.put("b", graph(call("same", "leaf")));
        graphs.put("leaf", empty());
        assertThat(bindings.bind(root(graph(call("left", "a"), call("right", "b")))))
                .containsExactlyInAnyOrderEntriesOf(Map.of("left", "a:1:fixed", "right", "b:1:fixed"));
        verify(resolver, times(3)).inspect(eq("demo"), any(), anyString(), isNull());
    }

    @Test
    void everyIncomingMappingIsCheckedEvenIfTheVersionWasAlreadyRead() {
        var source = schema("amount"); var target = schema("total");
        when(resolver.inspect(eq("demo"), any(), anyString(), eq(source))).thenReturn(new SubprocessDefinitionResolver.Bound(
                UUID.randomUUID(), "leaf", 1, "共享子审批", "leaf:1:fixed", empty(), target, NotificationTexts.EMPTY, null));
        var good = new Node("first", "首次调用", NodeType.SUB_PROCESS, new SubprocessPolicy("leaf", 1, Map.of("total", "amount")).properties());
        var bad = new Node("second", "第二次调用", NodeType.SUB_PROCESS, new SubprocessPolicy("leaf", 1, Map.of("total", "missing")).properties());
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "root", "父审批", graph(good, bad), source); draft.publish(0, 1);
        failure(() -> bindings.bind(draft), "SUBPROCESS_INPUT_FIELD_UNKNOWN");
        verify(resolver, times(1)).inspect(eq("demo"), any(), anyString(), eq(source));
    }

    @Test
    void boundsTheNumberOfDistinctDefinitions() {
        directory(); var nodes = new ArrayList<Node>();
        for (int index = 0; index < 256; index++) { String key = "wide" + index; graphs.put(key, empty()); nodes.add(call(key, key)); }
        assertThat(bindings.bind(root(new Graph(nodes, List.of())))).hasSize(256);
        nodes.add(call("overflow", "overflow")); graphs.put("overflow", empty());
        failure(() -> bindings.bind(root(new Graph(nodes, List.of()))), "SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED");
    }

    @Test
    void alsoBoundsRepeatedCallNodesToTheSameDefinition() {
        directory(); graphs.put("leaf", empty()); var nodes = new ArrayList<Node>();
        for (int index = 0; index < 4096; index++) nodes.add(call("call" + index, "leaf"));
        assertThat(bindings.bind(root(new Graph(nodes, List.of())))).hasSize(4096);
        nodes.add(call("overflow", "leaf"));
        failure(() -> bindings.bind(root(new Graph(nodes, List.of()))), "SUBPROCESS_DEPENDENCY_LIMIT_EXCEEDED");
    }

    private void directory() {
        when(resolver.inspect(eq("demo"), any(), anyString(), isNull())).thenAnswer(invocation -> {
            SubprocessPolicy policy = invocation.getArgument(1);
            return new SubprocessDefinitionResolver.Bound(UUID.randomUUID(), policy.processKey(), policy.version(), "依赖",
                    policy.processKey() + ":" + policy.version() + ":fixed", graphs.get(policy.processKey()), null, NotificationTexts.EMPTY, null);
        });
    }
    private void chain(int length) {
        for (int index = 1; index <= length; index++) graphs.put("n" + index,
                index == length ? empty() : graph(call("next", "n" + (index + 1))));
    }
    private DefinitionDraft root(Graph graph) {
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "root", "父审批", graph); draft.publish(0, 1); return draft;
    }
    private Node call(String id, String key) { return new Node(id, "子审批", NodeType.SUB_PROCESS, new SubprocessPolicy(key, 1, Map.of()).properties()); }
    private Graph empty() { return graph(); }
    private Graph graph(Node... nodes) { return new Graph(List.of(nodes), List.of()); }
    private FormSchema schema(String key) { return new FormSchema(2, List.of(new FormSchema.Field(key, key, FormSchema.FieldType.NUMBER, true, null, null, null, null, null))); }
    private void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
