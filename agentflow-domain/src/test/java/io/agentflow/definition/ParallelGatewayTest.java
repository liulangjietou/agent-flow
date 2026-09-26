package io.agentflow.definition;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并行拆分与汇合必须在发布和模拟中使用同一语义。
 * @author owlzhangfq@gmail.com
 */
class ParallelGatewayTest {
    @Test
    void allBranchesAreVisitedAndJoinContinuesOnlyOnce() {
        Graph graph = parallelGraph();
        assertThat(new DefinitionValidator().validate(graph)).isEmpty();
        var result = new DefinitionSimulator().simulateDetailed(graph, null, new EvaluationContext(Map.of()));
        assertThat(result.path()).containsExactly("start", "fork", "a", "b", "join", "end");
        assertThat(result.edgeIds()).containsExactly("sf", "fa", "fb", "aj", "bj", "je");
        assertThat(result.decisions()).isEmpty();
    }

    @Test
    void nestedParallelRegionsWaitForTheirOwnBranches() {
        Graph graph = nestedGraph();
        assertThat(new DefinitionValidator().validate(graph)).isEmpty();
        var path = new DefinitionSimulator().simulate(graph, new EvaluationContext(Map.of()));
        assertThat(path).doesNotHaveDuplicates().containsAll(graph.nodes().stream().map(Node::id).toList());
        assertThat(path.indexOf("innerJoin")).isGreaterThan(path.indexOf("a")).isGreaterThan(path.indexOf("b"));
        assertThat(path.indexOf("join")).isGreaterThan(path.indexOf("innerJoin")).isGreaterThan(path.indexOf("c"));
    }

    @Test
    void aGatewayCanJoinBeforeStartingAnotherParallelRegion() {
        var graph = new Graph(List.of(node("start", NodeType.START), node("fork", NodeType.PARALLEL_GATEWAY),
                node("a", NodeType.USER_TASK), node("b", NodeType.USER_TASK), node("middle", NodeType.PARALLEL_GATEWAY),
                node("c", NodeType.USER_TASK), node("d", NodeType.USER_TASK), node("join", NodeType.PARALLEL_GATEWAY),
                node("end", NodeType.END)), List.of(edge("sf", "start", "fork"), edge("fa", "fork", "a"),
                edge("fb", "fork", "b"), edge("am", "a", "middle"), edge("bm", "b", "middle"),
                edge("mc", "middle", "c"), edge("md", "middle", "d"), edge("cj", "c", "join"),
                edge("dj", "d", "join"), edge("je", "join", "end")));
        assertThat(new DefinitionValidator().validate(graph)).isEmpty();
        assertThat(new DefinitionSimulator().simulate(graph, new EvaluationContext(Map.of())))
                .containsExactly("start", "fork", "a", "b", "middle", "c", "d", "join", "end");
    }

    @Test
    void exclusivePathsMayMergeWithinOneParallelBranch() {
        var nodes = new ArrayList<>(parallelGraph().nodes());
        nodes.add(node("choice", NodeType.EXCLUSIVE_GATEWAY));
        nodes.add(node("optional", NodeType.USER_TASK));
        var edges = new ArrayList<>(parallelGraph().edges());
        edges.removeIf(edge -> edge.id().equals("fa"));
        edges.addAll(List.of(edge("fc", "fork", "choice"), new Edge("co", "choice", "optional", "amount > 10"),
                new Edge("ca", "choice", "a", "", true), edge("oa", "optional", "a")));
        Graph graph = new Graph(nodes, edges);
        assertThat(new DefinitionValidator().validate(graph)).isEmpty();
        var simulator = new DefinitionSimulator();
        assertThat(simulator.simulate(graph, new EvaluationContext(Map.of("amount", "20"))))
                .contains("optional", "a", "b", "join", "end").doesNotHaveDuplicates();
        assertThat(simulator.simulate(graph, new EvaluationContext(Map.of("amount", "5"))))
                .contains("a", "b", "join", "end").doesNotContain("optional").doesNotHaveDuplicates();
    }

    @Test
    void exclusiveAlternativesCannotBeUsedAsSeparateJoinInputs() {
        var nodes = new ArrayList<>(parallelGraph().nodes());
        nodes.add(node("choice", NodeType.EXCLUSIVE_GATEWAY));
        var edges = new ArrayList<>(parallelGraph().edges());
        edges.removeIf(edge -> edge.id().equals("fa"));
        edges.addAll(List.of(edge("fc", "fork", "choice"), new Edge("ca", "choice", "a", "amount > 10"),
                new Edge("cj", "choice", "join", "", true)));
        assertThat(new DefinitionValidator().validate(new Graph(nodes, edges))).contains("PARALLEL_JOIN_MISMATCH:join");
    }

    @Test
    void crossedNestedBranchesCannotReleaseTheWrongJoin() {
        Graph graph = nestedGraph();
        var edges = graph.edges().stream().map(edge -> edge.id().equals("ai")
                ? edge("ai", "a", "join") : edge.id().equals("cj") ? edge("cj", "c", "innerJoin") : edge).toList();
        assertThat(new DefinitionValidator().validate(new Graph(graph.nodes(), edges)))
                .anyMatch(error -> error.startsWith("PARALLEL_JOIN_MISMATCH:"));
    }

    @Test
    void ordinaryNodeCannotMergeLiveBranches() {
        Graph graph = parallelGraph();
        var nodes = graph.nodes().stream().map(node -> node.id().equals("join") ? node("join", NodeType.USER_TASK) : node).toList();
        assertThat(new DefinitionValidator().validate(new Graph(nodes, graph.edges())))
                .contains("PARALLEL_MERGE_REQUIRES_GATEWAY:join");
    }

    @Test
    void aParallelBranchCannotEndBeforeJoining() {
        Graph graph = parallelGraph();
        var nodes = graph.nodes().stream().filter(node -> !node.id().equals("join")).toList();
        var edges = graph.edges().stream().filter(edge -> !edge.id().equals("je")).map(edge -> edge.target().equals("join")
                ? edge(edge.id(), edge.source(), "end") : edge).toList();
        assertThat(new DefinitionValidator().validate(new Graph(nodes, edges)))
                .contains("PARALLEL_MERGE_REQUIRES_GATEWAY:end");
    }

    @Test
    void conditionalParallelEdgesAreRejectedInsteadOfIgnoredByTheEngine() {
        Graph graph = parallelGraph();
        var edges = graph.edges().stream().map(edge -> edge.id().equals("fa")
                ? new Edge("fa", "fork", "a", "amount > 10") : edge).toList();
        assertThat(new DefinitionValidator().validate(new Graph(graph.nodes(), edges)))
                .contains("PARALLEL_CONDITION_FORBIDDEN:fa");
        edges = graph.edges().stream().map(edge -> edge.id().equals("aj")
                ? new Edge("aj", "a", "join", "amount > 10") : edge).toList();
        assertThat(new DefinitionValidator().validate(new Graph(graph.nodes(), edges)))
                .contains("PARALLEL_BRANCH_CONDITION_REQUIRES_GATEWAY:aj");
    }

    private Graph nestedGraph() {
        return new Graph(List.of(node("start", NodeType.START), node("fork", NodeType.PARALLEL_GATEWAY),
                node("innerFork", NodeType.PARALLEL_GATEWAY), node("a", NodeType.USER_TASK), node("b", NodeType.USER_TASK),
                node("c", NodeType.USER_TASK), node("innerJoin", NodeType.PARALLEL_GATEWAY),
                node("join", NodeType.PARALLEL_GATEWAY), node("end", NodeType.END)),
                List.of(edge("sf", "start", "fork"), edge("fi", "fork", "innerFork"), edge("fc", "fork", "c"),
                        edge("ia", "innerFork", "a"), edge("ib", "innerFork", "b"), edge("ai", "a", "innerJoin"),
                        edge("bi", "b", "innerJoin"), edge("ij", "innerJoin", "join"), edge("cj", "c", "join"), edge("je", "join", "end")));
    }

    private Graph parallelGraph() {
        return new Graph(List.of(node("start", NodeType.START), node("fork", NodeType.PARALLEL_GATEWAY),
                node("a", NodeType.USER_TASK), node("b", NodeType.USER_TASK),
                node("join", NodeType.PARALLEL_GATEWAY), node("end", NodeType.END)),
                List.of(edge("sf", "start", "fork"), edge("fa", "fork", "a"), edge("fb", "fork", "b"),
                        edge("aj", "a", "join"), edge("bj", "b", "join"), edge("je", "join", "end")));
    }

    private Node node(String id, NodeType type) {
        return new Node(id, id, type, type == NodeType.USER_TASK ? Map.of("assigneeRule", "user:manager") : Map.of());
    }

    private Edge edge(String id, String source, String target) { return new Edge(id, source, target, ""); }
}
