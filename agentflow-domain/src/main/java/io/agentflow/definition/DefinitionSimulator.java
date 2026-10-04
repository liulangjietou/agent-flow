package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseFormContract;
import io.agentflow.expense.ExpenseSplitRiskPolicy;
import io.agentflow.finance.Money;
import io.agentflow.form.FormSchema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
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
        return simulateDetailed(graph, formSchema, context, null);
    }

    /** 合成路由金额只影响明确标记的业务网关，不查询费用记录，也不改写本单输入。 */
    public Result simulateDetailed(Graph graph, FormSchema formSchema, EvaluationContext context, Money splitRoutingAmount) {
        List<String> errors = new DefinitionValidator().validate(graph, formSchema);
        if (!errors.isEmpty()) throw new DefinitionValidationException(errors);
        if (formSchema != null) {
            formSchema.validateSubmission(context.values());
            context = new EvaluationContext(context.values(), formSchema.fieldTypes());
        }
        var splitPolicy = ExpenseSplitRiskPolicy.from(graph);
        var splitContext = splitContext(context, splitPolicy, splitRoutingAmount);
        List<String> path = new ArrayList<>();
        List<String> edgeIds = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        Node start = graph.nodes().stream().filter(node -> node.type() == NodeType.START).findFirst().orElseThrow();
        var ready = new ArrayDeque<Node>();
        ready.add(start);
        Map<String, Integer> arrivals = new HashMap<>();
        // 合法并行区域的每条入线只产生一个令牌；全部到齐才记录和越过汇合点。
        while (!ready.isEmpty()) {
            Node current = ready.removeFirst();
            if (current.type() == NodeType.PARALLEL_GATEWAY) {
                long expected = graph.edges().stream().filter(edge -> edge.target().equals(current.id())).count();
                if (arrivals.merge(current.id(), 1, Integer::sum) < expected) continue;
            }
            path.add(current.id());
            if (current.type() == NodeType.END) continue;
            String source = current.id();
            List<Edge> outgoing = graph.edges().stream().filter(edge -> edge.source().equals(source)).toList();
            List<Edge> selected = outgoing;
            if (current.type() != NodeType.PARALLEL_GATEWAY) {
                Decision decision = select(source, outgoing, splitPolicy.gatewayIds().contains(source) ? splitContext : context, graph.conditionLanguageVersion());
                if (current.type() == NodeType.EXCLUSIVE_GATEWAY && outgoing.size() > 1) decisions.add(decision);
                selected = outgoing.stream().filter(edge -> edge.id().equals(decision.selectedEdgeId())).toList();
            }
            for (Edge edge : selected) {
                edgeIds.add(edge.id());
                ready.addLast(graph.node(edge.target()));
            }
        }
        return new Result(path, edgeIds, decisions);
    }

    private EvaluationContext splitContext(EvaluationContext original, ExpenseSplitRiskPolicy.Configuration policy, Money synthetic) {
        if (policy.mode() != ExpenseSplitRiskPolicy.Mode.ENABLED) {
            if (synthetic != null) throw new DomainException("EXPENSE_SPLIT_SIMULATION_UNEXPECTED", "Synthetic routing amount requires an enabled split rule");
            return original;
        }
        if (synthetic == null) throw new DomainException("EXPENSE_SPLIT_SIMULATION_REQUIRED", "Enabled split simulation requires an explicit synthetic routing amount");
        Money ownAmount;
        try {
            ownAmount = new Money(new BigDecimal(String.valueOf(original.value(ExpenseFormContract.AMOUNT))),
                    String.valueOf(original.value(ExpenseFormContract.CURRENCY)));
        } catch (IllegalArgumentException | DomainException invalidMoney) { throw invalidSplitAmount(); }
        if (!synthetic.currency().equals(policy.rule().threshold().currency()) || !synthetic.currency().equals(ownAmount.currency())
                || synthetic.value().compareTo(ownAmount.value()) < 0) throw invalidSplitAmount();
        var values = new HashMap<>(original.values());
        values.put(ExpenseFormContract.AMOUNT, synthetic.value());
        return new EvaluationContext(values, original.fieldTypes());
    }

    private DomainException invalidSplitAmount() {
        return new DomainException("EXPENSE_SPLIT_SIMULATION_INVALID", "Synthetic routing amount must match the rule currency and cannot reduce the original amount");
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
