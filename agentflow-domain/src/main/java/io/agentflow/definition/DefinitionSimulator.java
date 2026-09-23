package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.List;
import static io.agentflow.definition.DefinitionModels.*;

/**
 * 在内存中执行受限图的路由语义，使用与实际流程相同的条件 AST，不访问流程引擎。
 * @author owlzhangfq@gmail.com
 */
public final class DefinitionSimulator {
    /** 保留原路径接口，模板校验与已有调用方继续使用同一算法。 */
    public List<String> simulate(Graph graph, EvaluationContext context) {
        return simulateDetailed(graph, null, context).path();
    }

    /** 一次完成图与表单校验，并返回节点、连线及按实际顺序产生的分支依据。 */
    public Result simulateDetailed(Graph graph, FormSchema formSchema, EvaluationContext context) {
        List<String> errors = new DefinitionValidator().validate(graph, formSchema);
        if (!errors.isEmpty()) throw new DefinitionValidationException(errors);
        if (formSchema != null) {
            formSchema.validateSubmission(context.values());
            context = new EvaluationContext(context.values(), formSchema.fieldTypes());
        }
        List<String> path = new ArrayList<>();
        List<String> edgeIds = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        Node current = graph.nodes().stream().filter(node -> node.type() == NodeType.START).findFirst().orElseThrow();
        // 入口已验证无环，因此访问节点数不会超过图中节点数。
        while (current.type() != NodeType.END) {
            path.add(current.id());
            String source = current.id();
            List<Edge> outgoing = graph.edges().stream().filter(edge -> edge.source().equals(source)).toList();
            Decision decision = select(source, outgoing, context, graph.conditionLanguageVersion());
            if (current.type() == NodeType.EXCLUSIVE_GATEWAY) decisions.add(decision);
            Edge selected = outgoing.stream().filter(edge -> edge.id().equals(decision.selectedEdgeId())).findFirst().orElseThrow();
            edgeIds.add(selected.id());
            current = graph.node(selected.target());
        }
        path.add(current.id());
        return new Result(path, edgeIds, decisions);
    }

    private Decision select(String nodeId, List<Edge> outgoing, EvaluationContext context, int languageVersion) {
        String selectedId = null;
        String defaultId = null;
        List<Branch> branches = new ArrayList<>();
        ConditionParser parser = new ConditionParser();
        for (Edge edge : outgoing) {
            if (edge.defaultBranch()) { defaultId = edge.id(); continue; }
            Outcome outcome = Outcome.SKIPPED;
            if (selectedId == null) {
                boolean matches = parser.parse(edge.condition(), languageVersion).evaluate(context);
                outcome = matches ? Outcome.MATCHED : Outcome.NOT_MATCHED;
                if (matches) selectedId = edge.id();
            }
            branches.add(new Branch(edge.id(), edge.target(), edge.condition(), outcome));
        }
        if (selectedId == null) selectedId = defaultId;
        if (selectedId == null) throw new DomainException("NO_BRANCH_MATCHED", "No outgoing branch matched at node: " + nodeId);
        if (defaultId != null) {
            String id = defaultId;
            Edge fallback = outgoing.stream().filter(edge -> edge.id().equals(id)).findFirst().orElseThrow();
            branches.add(new Branch(fallback.id(), fallback.target(), "", fallback.id().equals(selectedId)
                    ? Outcome.DEFAULT_SELECTED : Outcome.DEFAULT_SKIPPED));
        }
        return new Decision(nodeId, selectedId, branches);
    }

    /**
     * 首条条件命中后的条件不再执行；默认分支仅在全部条件不匹配时选中。
     * @author owlzhangfq@gmail.com
     */
    public enum Outcome { MATCHED, NOT_MATCHED, SKIPPED, DEFAULT_SELECTED, DEFAULT_SKIPPED }

    /**
     * 分支结果不回显字段填写值。
     * @author owlzhangfq@gmail.com
     */
    public record Branch(String edgeId, String targetNodeId, String condition, Outcome outcome) { }

    /**
     * 网关的本次决策及其执行依据。
     * @author owlzhangfq@gmail.com
     */
    public record Decision(String nodeId, String selectedEdgeId, List<Branch> branches) {
        public Decision { branches = List.copyOf(branches); }
    }

    /**
     * 本次模拟的不可变快照。
     * @author owlzhangfq@gmail.com
     */
    public record Result(List<String> path, List<String> edgeIds, List<Decision> decisions) {
        public Result { path = List.copyOf(path); edgeIds = List.copyOf(edgeIds); decisions = List.copyOf(decisions); }
    }
}
