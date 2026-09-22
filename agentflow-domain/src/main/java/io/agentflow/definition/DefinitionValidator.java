package io.agentflow.definition;

import java.util.*;

import static io.agentflow.definition.DefinitionModels.*;

/** 校验流程图的结构、节点职责和条件语法。 */
public final class DefinitionValidator {
    /** 返回全部校验问题；没有问题时返回空列表。 */
    public List<String> validate(Graph graph) {
        List<String> errors = new ArrayList<>();
        Map<String, Node> nodes = new HashMap<>();
        for (Node n : graph.nodes()) if (nodes.put(n.id(), n) != null) errors.add("DUPLICATE_NODE:" + n.id());
        long starts = graph.nodes().stream().filter(n -> n.type() == NodeType.START).count();
        long ends = graph.nodes().stream().filter(n -> n.type() == NodeType.END).count();
        if (starts != 1) errors.add("START_COUNT_MUST_BE_ONE");
        if (ends < 1) errors.add("END_REQUIRED");
        Set<String> incoming = new HashSet<>(), outgoing = new HashSet<>();
        ConditionParser parser = new ConditionParser();
        for (Edge e : graph.edges()) {
            if (!nodes.containsKey(e.source()) || !nodes.containsKey(e.target())) errors.add("EDGE_NODE_NOT_FOUND:" + e.id());
            outgoing.add(e.source()); incoming.add(e.target());
            try { parser.parse(e.condition()); } catch (RuntimeException ex) { errors.add("INVALID_CONDITION:" + e.id()); }
        }
        for (Node n : graph.nodes()) {
            if (n.type() != NodeType.START && !incoming.contains(n.id())) errors.add("NODE_UNREACHABLE:" + n.id());
            if (n.type() != NodeType.END && !outgoing.contains(n.id())) errors.add("NODE_DEAD_END:" + n.id());
            if (n.type() == NodeType.USER_TASK && !n.properties().containsKey("assigneeRule")) errors.add("ASSIGNEE_RULE_REQUIRED:" + n.id());
        }
        return List.copyOf(errors);
    }
}
