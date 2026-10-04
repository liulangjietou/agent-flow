package io.agentflow.template;

import io.agentflow.definition.DefinitionModels.Edge;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FieldVisibility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板领域风险测试，覆盖不可变场景与真实表单、路径验收。
 * @author owlzhangfq@gmail.com
 */
class ProcessTemplateTest {
    @Test
    void expenseScenarioRetainsItsFormWhenCheckingReceiptAndFinanceStages() {
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("receipt", "原件签收", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "expenseStage", "RECEIPT")),
                new Node("finance", "财务审核", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "expenseStage", "FINANCE_REVIEW")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "receipt", ""),
                new Edge("b", "receipt", "finance", ""), new Edge("c", "finance", "end", "")));
        FormSchema schema = new FormSchema(2, List.of(
                new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true, null, null, null, null, List.of(),
                        null, null, true, Map.of("receipt", FieldVisibility.READ_ONLY, "finance", FieldVisibility.READ_ONLY)),
                new FormSchema.Field("amount", "核定额", FormSchema.FieldType.NUMBER, true, null, null, null, null, List.of()),
                new FormSchema.Field("currency", "本位币", FormSchema.FieldType.TEXT, true, null, null, null, null, List.of()),
                new FormSchema.Field("overPolicy", "制度例外", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, List.of())));
        var scenario = scenario(Map.of("expenseDetails", "原明细", "amount", "80.00", "currency", "CNY", "overPolicy", false),
                List.of("start", "receipt", "finance", "end"), Map.of());
        var template = new ProcessTemplate("expense-case", 1, "报销", "财务", "说明", "范围", "EXPENSE", List.of(), List.of("FINANCE"),
                Map.of(), List.of(), "不覆盖副本", Map.of(), false, graph, schema, List.of(scenario));
        template.verifyScenarios();
    }

    @Test
    void freezesNestedPayloadAndRetainsExplicitNulls() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("value", null);
        List<Object> lines = new ArrayList<>(List.of(nested));
        Map<String, Object> source = new HashMap<>();
        source.put("lines", lines);
        var scenario = scenario(source, List.of(), Map.of("lines", "UNKNOWN_FIELD"));
        nested.put("value", "changed");
        lines.clear();
        source.clear();
        List<?> retained = (List<?>) scenario.payload().get("lines");
        Map<?, ?> retainedMap = (Map<?, ?>) retained.get(0);
        assertThat(retainedMap.containsKey("value")).isTrue();
        assertThat(retainedMap.get("value")).isNull();
        assertThatThrownBy(retained::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(retainedMap::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(scenario.payload()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesExactFieldErrorsAndSuccessfulPathUsingDomainRules() {
        template(List.of(scenario(Map.of("reason", "说明"), List.of("start", "approve", "end"), Map.of()))).verifyScenarios();
        template(List.of(scenario(Map.of(), List.of(), Map.of("reason", "REQUIRED")))).verifyScenarios();
        assertThatThrownBy(() -> template(List.of(scenario(Map.of(), List.of(), Map.of("reason", "INVALID_TYPE")))).verifyScenarios())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field errors");
        assertThatThrownBy(() -> template(List.of(scenario(Map.of("reason", "说明"), List.of("start", "end"), Map.of()))).verifyScenarios())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
    }

    @Test
    void rejectsRepeatedScenarioIdentityAndFreezesCatalogCollections() {
        var scenario = scenario(Map.of("reason", "说明"), List.of("start", "approve", "end"), Map.of());
        assertThatThrownBy(() -> template(List.of(scenario, scenario)).verifyScenarios()).hasMessageContaining("duplicated");
        ProcessTemplate template = template(List.of(scenario));
        assertThatThrownBy(template.scenarios()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(template.dependencies()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(template.fieldDescriptions()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(template.graph().nodes()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(template.formSchema().fields()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private ProcessTemplate.Scenario scenario(Map<String, Object> payload, List<String> path, Map<String, String> errors) {
        return new ProcessTemplate.Scenario("case", "场景", "场景说明", payload, path, errors);
    }

    private ProcessTemplate template(List<ProcessTemplate.Scenario> scenarios) {
        Graph graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("a", "start", "approve", ""), new Edge("b", "approve", "end", "")));
        FormSchema schema = new FormSchema(1, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT,
                true, null, 100, null, null, List.of())));
        return new ProcessTemplate("example", 1, "示例", "OA", "说明", "范围", "FORM", List.of("基础表单"), List.of("MANAGER"),
                Map.of("reason", "说明"), List.of("示例风险"), "不自动升级", Map.of("SUBMITTED", "已提交"), false, graph, schema, scenarios);
    }
}
