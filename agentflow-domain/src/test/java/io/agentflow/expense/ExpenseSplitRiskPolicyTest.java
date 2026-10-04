package io.agentflow.expense;

import io.agentflow.definition.DefinitionModels.Edge;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从现有发布入口验证跨单规则边界，防止未知配置被静默当作已保护流程。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSplitRiskPolicyTest {
    @Test
    void absentOrDisabledPolicyPreservesExplicitLocalAmountSemantics() {
        assertThat(errors(graph(Map.of(), Map.of(), false))).isEmpty();
        assertThat(errors(graph(Map.of("expenseSplitRisk", "DISABLED"), aggregate(), false))).isEmpty();
        var disabled = enabled(); disabled.put("expenseSplitRisk", "DISABLED");
        assertThat(errors(graph(disabled, aggregate(), false))).isEmpty();
    }

    @Test
    void enabledPolicyRequiresCompleteExplicitRuleAndBusinessGateway() {
        for (String missing : List.of("expenseSplitWindowDays", "expenseSplitThreshold", "expenseSplitCurrency")) {
            var properties = enabled(); properties.remove(missing);
            assertThat(errors(graph(properties, aggregate(), false))).as(missing).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        }
        assertThat(errors(graph(enabled(), Map.of(), false))).contains("EXPENSE_SPLIT_ROUTING_REQUIRED");
        assertThat(errors(graph(Map.of(), aggregate(), false))).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
    }

    @Test
    void unknownModesAndNonCanonicalWindowCannotSilentlyEnableOrDisableProtection() {
        for (String mode : List.of("", "true", "ENABLED ", "enabled", "UNKNOWN")) {
            var properties = enabled(); properties.put("expenseSplitRisk", mode);
            assertThat(errors(graph(properties, aggregate(), false))).as(mode).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        }
        for (String window : List.of("0", "366", "1.5", "07", "-1", " 7", "99999999999999999")) {
            var properties = enabled(); properties.put("expenseSplitWindowDays", window);
            assertThat(errors(graph(properties, aggregate(), false))).as(window).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        }
    }

    @Test
    void thresholdMustBePositiveAndRespectCurrencyPrecision() {
        for (String amount : List.of("0", "-1", "1.001", "1e3", " 1000", "01.00")) {
            var properties = enabled(); properties.put("expenseSplitThreshold", amount);
            assertThat(errors(graph(properties, aggregate(), false))).as(amount).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        }
        var jpy = enabled(); jpy.put("expenseSplitCurrency", "JPY"); jpy.put("expenseSplitThreshold", "1.01");
        assertThat(errors(graph(jpy, aggregate(), false))).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        var missingCurrency = enabled(); missingCurrency.put("expenseSplitCurrency", "not-money");
        assertThat(errors(graph(missingCurrency, aggregate(), false))).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
    }

    @Test
    void validBusinessRuleRequiresOriginalStructuredExpenseSchema() {
        assertThat(errors(graph(enabled(), aggregate(), false))).isEmpty();
        assertThat(new DefinitionValidator().validate(graph(enabled(), aggregate(), false), null))
                .contains("EXPENSE_SPLIT_RISK_REQUIRES_EXPENSE_FORM");
    }

    @Test
    void financialReviewCannotReachAnAggregateAmountGateway() {
        assertThat(errors(graph(enabled(), aggregate(), true))).contains("EXPENSE_SPLIT_ROUTING_AFTER_FINANCE");
    }

    @Test
    void ruleCannotMoveFromStartToTaskAndGatewayMarkerCannotMoveToTask() {
        var original = graph(enabled(), aggregate(), false);
        var nodes = original.nodes().stream().map(node -> node.id().equals("business")
                ? new Node(node.id(), node.name(), node.type(), Map.of("assigneeRule", "user:business", "expenseSplitWindowDays", "7")) : node).toList();
        assertThat(errors(new Graph(nodes, original.edges()))).contains("EXPENSE_SPLIT_RISK_POLICY_INVALID");
        nodes = original.nodes().stream().map(node -> node.id().equals("business")
                ? new Node(node.id(), node.name(), node.type(), Map.of("assigneeRule", "user:business", "expenseSplitRouting", "AGGREGATE_AMOUNT")) : node).toList();
        assertThat(errors(new Graph(nodes, original.edges()))).contains("EXPENSE_SPLIT_ROUTING_INVALID");
    }

    @Test
    void aggregateMarkerMustAffectAnAmountCondition() {
        var original = graph(enabled(), aggregate(), false);
        var edges = original.edges().stream().map(edge -> edge.source().equals("gate") && !edge.defaultBranch()
                ? new Edge(edge.id(), edge.source(), edge.target(), "overPolicy == true", false) : edge).toList();
        assertThat(errors(new Graph(original.nodes(), edges))).contains("EXPENSE_SPLIT_ROUTING_AMOUNT_REQUIRED");
        assertThat(errors(graph(enabled(), Map.of("expenseSplitRouting", "OTHER"), false))).contains("EXPENSE_SPLIT_ROUTING_INVALID");
    }

    private List<String> errors(Graph graph) { return new DefinitionValidator().validate(graph, schema()); }
    private Map<String, String> enabled() {
        return new HashMap<>(Map.of("expenseSplitRisk", "ENABLED", "expenseSplitWindowDays", "7",
                "expenseSplitThreshold", "5000", "expenseSplitCurrency", "CNY"));
    }
    private Map<String, String> aggregate() { return Map.of("expenseSplitRouting", "AGGREGATE_AMOUNT"); }

    private Graph graph(Map<String, String> start, Map<String, String> gate, boolean afterFinance) {
        var nodes = List.of(new Node("start", "开始", NodeType.START, start),
                new Node("business", "业务审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:business")),
                new Node("gate", "金额条件", NodeType.EXCLUSIVE_GATEWAY, gate),
                new Node("higher", "上级审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:higher")),
                new Node("finance", "财务审核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of()));
        var edges = afterFinance ? List.of(new Edge("a", "start", "business", "", false), new Edge("b", "business", "finance", "", false),
                new Edge("c", "finance", "gate", "", false), new Edge("d", "gate", "higher", "amount > 5000", false),
                new Edge("e", "gate", "end", "", true), new Edge("f", "higher", "end", "", false))
                : List.of(new Edge("a", "start", "business", "", false), new Edge("b", "business", "gate", "", false),
                new Edge("c", "gate", "higher", "amount > 5000", false), new Edge("d", "gate", "finance", "", true),
                new Edge("e", "higher", "finance", "", false), new Edge("f", "finance", "end", "", false));
        return new Graph(nodes, edges);
    }

    private FormSchema schema() {
        return new FormSchema(2, List.of(new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, true, Map.of("business", FieldVisibility.READ_ONLY, "higher", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "本币金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
    }
}
