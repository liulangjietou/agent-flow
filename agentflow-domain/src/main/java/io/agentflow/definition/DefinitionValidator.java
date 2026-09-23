package io.agentflow.definition;

import java.util.*;
import io.agentflow.form.FormSchema;
import java.util.regex.Pattern;

import static io.agentflow.definition.DefinitionModels.*;

/**
 * 校验流程图的结构、节点职责和条件语法。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionValidator {
    private static final Pattern LITERAL_ASSIGNEE_RULE =
            Pattern.compile("(?:role|user):[\\p{L}\\p{N}_][\\p{L}\\p{N}_.@-]{0,127}");

    /** 返回全部校验问题；没有问题时返回空列表。 */
    public List<String> validate(Graph graph) {
        return validate(graph, null);
    }

    /** 在结构检查之外验证条件字段和表单类型。 */
    public List<String> validate(Graph graph, FormSchema formSchema) {
        List<String> errors = new ArrayList<>();
        Map<String, Node> nodes = new HashMap<>();
        for (Node n : graph.nodes()) {
            String approvalMode = n.properties().get("approvalMode");
            if (approvalMode != null) {
                if (n.type() != NodeType.USER_TASK) errors.add("APPROVAL_MODE_REQUIRES_USER_TASK:" + n.id());
                else if (java.util.Arrays.stream(ApprovalMode.values()).noneMatch(mode -> mode.name().equals(approvalMode))) {
                    errors.add("APPROVAL_MODE_INVALID:" + n.id());
                }
            }
            if (nodes.put(n.id(), n) != null) errors.add("DUPLICATE_NODE:" + n.id());
            if (n.type() == NodeType.SERVICE_TASK || n.type() == NodeType.PARALLEL_GATEWAY) {
                errors.add("UNSUPPORTED_NODE_TYPE:" + n.id());
            }
            if (n.type() == NodeType.USER_TASK) {
                // Flowable 会对任务名称求值，业务标签必须保持字面量，不能成为访问 Spring Bean 的入口。
                if (n.name().contains("${") || n.name().contains("#{")) {
                    errors.add("TASK_NAME_EXPRESSION_FORBIDDEN:" + n.id());
                }
                String assigneeRule = n.properties().get("assigneeRule");
                if (assigneeRule == null || assigneeRule.isBlank()) {
                    errors.add("ASSIGNEE_RULE_REQUIRED:" + n.id());
                } else if (!LITERAL_ASSIGNEE_RULE.matcher(assigneeRule).matches()) {
                    errors.add("ASSIGNEE_RULE_INVALID:" + n.id());
                }
            }
        }
        long starts = graph.nodes().stream().filter(n -> n.type() == NodeType.START).count();
        long ends = graph.nodes().stream().filter(n -> n.type() == NodeType.END).count();
        if (starts != 1) errors.add("START_COUNT_MUST_BE_ONE");
        if (ends < 1) errors.add("END_REQUIRED");
        Set<String> incoming = new HashSet<>(), outgoing = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        Map<String, List<Edge>> outgoingEdges = new HashMap<>();
        Map<String, Integer> defaultBranches = new HashMap<>();
        ConditionParser parser = new ConditionParser();
        for (Edge e : graph.edges()) {
            if (!edgeIds.add(e.id())) errors.add("DUPLICATE_EDGE:" + e.id());
            if (!nodes.containsKey(e.source()) || !nodes.containsKey(e.target())) errors.add("EDGE_NODE_NOT_FOUND:" + e.id());
            outgoing.add(e.source()); incoming.add(e.target());
            outgoingEdges.computeIfAbsent(e.source(), ignored -> new ArrayList<>()).add(e);
            try {
                ConditionAst condition = parser.parse(e.condition());
                if (formSchema != null) formSchema.validateCondition(condition);
            } catch (RuntimeException ex) { errors.add("INVALID_CONDITION:" + e.id()); }
            Node sourceNode = nodes.get(e.source());
            if (sourceNode != null && sourceNode.type() == NodeType.EXCLUSIVE_GATEWAY
                    && !e.defaultBranch() && e.condition().isBlank()) {
                errors.add("GATEWAY_BRANCH_CONDITION_REQUIRED:" + e.id());
            }
            if (e.defaultBranch()) {
                defaultBranches.merge(e.source(), 1, Integer::sum);
                if (!e.condition().isBlank()) errors.add("DEFAULT_BRANCH_MUST_HAVE_NO_CONDITION:" + e.id());
                if (sourceNode != null && sourceNode.type() != NodeType.EXCLUSIVE_GATEWAY) {
                    errors.add("DEFAULT_BRANCH_REQUIRES_GATEWAY:" + e.id());
                }
            }
        }
        defaultBranches.forEach((source, count) -> {
            if (count > 1) errors.add("MULTIPLE_DEFAULT_BRANCHES:" + source);
        });
        for (Node n : graph.nodes()) {
            if (n.type() != NodeType.START && !incoming.contains(n.id())) errors.add("NODE_UNREACHABLE:" + n.id());
            int outgoingCount = outgoingEdges.getOrDefault(n.id(), List.of()).size();
            if (n.type() == NodeType.START && incoming.contains(n.id())) errors.add("START_MUST_HAVE_NO_INCOMING:" + n.id());
            if (n.type() == NodeType.END && outgoingCount != 0) errors.add("END_MUST_HAVE_NO_OUTGOING:" + n.id());
            // 当前只支持顺序与排他分支，普通节点的多出线会在引擎中产生隐式并行。
            if ((n.type() == NodeType.START || n.type() == NodeType.USER_TASK) && outgoingCount > 1) {
                errors.add("SINGLE_OUTGOING_REQUIRED:" + n.id());
            }
            if (n.type() != NodeType.END && !outgoing.contains(n.id())) errors.add("NODE_DEAD_END:" + n.id());
            if (n.type() == NodeType.EXCLUSIVE_GATEWAY && outgoingEdges.getOrDefault(n.id(), List.of()).size() < 2) {
                errors.add("GATEWAY_BRANCH_REQUIRED:" + n.id());
            }
        }
        Node start = graph.nodes().stream().filter(n -> n.type() == NodeType.START).findFirst().orElse(null);
        if (start != null) {
            Set<String> reachable = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start.id());
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (!reachable.add(current)) continue;
                outgoingEdges.getOrDefault(current, List.of()).stream().map(Edge::target).forEach(queue::addLast);
            }
            for (Node n : graph.nodes()) {
                if (!reachable.contains(n.id()) && !errors.contains("NODE_UNREACHABLE:" + n.id())) {
                    errors.add("NODE_UNREACHABLE:" + n.id());
                }
            }
        }
        if (containsCycle(nodes.keySet(), outgoingEdges)) errors.add("GRAPH_LOOP");
        return List.copyOf(errors);
    }

    private boolean containsCycle(Set<String> nodeIds, Map<String, List<Edge>> outgoing) {
        Map<String, Integer> inDegree = new HashMap<>();
        nodeIds.forEach(id -> inDegree.put(id, 0));
        outgoing.forEach((source, edges) -> {
            if (nodeIds.contains(source)) edges.stream().filter(edge -> nodeIds.contains(edge.target()))
                    .forEach(edge -> inDegree.merge(edge.target(), 1, Integer::sum));
        });
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (inDegree.containsKey(edge.target()) && inDegree.merge(edge.target(), -1, Integer::sum) == 0) ready.add(edge.target());
            }
        }
        return visited != nodeIds.size();
    }
}
