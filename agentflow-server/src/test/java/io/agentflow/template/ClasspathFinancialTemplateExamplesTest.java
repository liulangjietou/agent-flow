package io.agentflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配套样例必须符合实际财务输入；未知类别、不平衡金额或损坏的资源不能进入目录。
 * @author owlzhangfq@gmail.com
 */
class ClasspathFinancialTemplateExamplesTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules());
    private final ClasspathFinancialTemplateExamples examples = new ClasspathFinancialTemplateExamples(new DefaultResourceLoader(), json);

    @Test
    void relatedTemplatesShareAValidatedPackWithoutSharingMutableDocuments() {
        var pack = examples.get("expense-report");
        assertThat(pack.path("version").asInt()).isEqualTo(7);
        assertThat(pack.path("setupSteps").toString()).contains("EXPENSE_BUDGET_REVIEW", "实际预算确认");
        assertThat(pack.path("setupSteps").toString()).contains("相邻同人业务审批自动通过", "来源任务和规则版本审计");
        assertThat(pack.path("setupSteps").toString()).contains("跨单拆分风险默认关闭", "合成路由金额");
        assertThat(pack.path("scenarios")).hasSize(12);
        assertThat(examples.summary("expense-plan")).isEqualTo(examples.summary("advance-request"));
        assertThat(examples.summary("expense-plan").scenarioCount()).isEqualTo(12);
        assertThat(examples.summary("leave-request")).isNull();
        assertThatThrownBy(() -> examples.get("leave-request")).hasMessageContaining("not found");
        assertThatThrownBy(() -> examples.get("../../employee-finance")).hasMessageContaining("not found");
        ((ObjectNode) pack).put("name", "changed");
        ((ObjectNode) pack.at("/configuration/expensePolicy")).removeAll();
        assertThat(examples.get("expense-report").path("name").asText()).isEqualTo("员工财务配套样例");
        assertThat(examples.get("expense-report").at("/configuration/expensePolicy/rules")).hasSize(2);
    }

    @Test
    void riskyScenariosContainActualDistinctBusinessInputs() {
        var scenarios = examples.get("expense-report").path("scenarios");
        var owner = java.util.stream.StreamSupport.stream(scenarios.spliterator(), false)
                .filter(value -> value.path("id").asText().equals("expense-invoice-owner")).findFirst().orElseThrow();
        var duplicate = java.util.stream.StreamSupport.stream(scenarios.spliterator(), false)
                .filter(value -> value.path("id").asText().equals("expense-invoice-duplicate")).findFirst().orElseThrow();
        assertThat(owner.at("/content/lines/0/invoiceIds")).isEqualTo(duplicate.at("/content/lines/0/invoiceIds"));
        assertThat(owner.at("/content/title")).isNotEqualTo(duplicate.at("/content/title"));
        var reduction = java.util.stream.StreamSupport.stream(scenarios.spliterator(), false)
                .filter(value -> value.path("id").asText().equals("expense-reduction")).findFirst().orElseThrow();
        assertThat(reduction.at("/content/lines/0/claimedGross/value").asText()).isEqualTo("10500.00");
        assertThat(reduction.at("/reductions/0/approvedGross").asText()).isEqualTo("9500.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "allocation", "receipt", "template", "duplicate-id", "reduction-type"})
    void corruptConfigurationOrBusinessInputsPreventStartup(String corruption) {
        ObjectNode pack = (ObjectNode) examples.get("expense-report");
        switch (corruption) {
            case "category" -> ((ObjectNode) pack.at("/configuration/categories/0")).put("code", "UNKNOWN");
            case "allocation" -> ((ObjectNode) pack.at("/scenarios/2/content/lines/0/allocations/0/amount")).put("value", "1.00");
            case "receipt" -> ((ObjectNode) pack.at("/configuration/receiptOptions/0")).put("paperReceiptRequired", false);
            case "template" -> ((ObjectNode) pack.at("/scenarios/0")).put("templateKey", "leave-request");
            case "reduction-type" -> ((ObjectNode) pack.at("/scenarios/4/reductions/0")).put("approvedGross", 9500);
            default -> ((ObjectNode) pack.at("/scenarios/1")).put("id", pack.at("/scenarios/0/id").asText());
        }
        ResourceLoader resources = mock(ResourceLoader.class);
        when(resources.getResource(anyString())).thenReturn(new ByteArrayResource(json.write(pack).getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> new ClasspathFinancialTemplateExamples(resources, json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("process-template-examples/employee-finance.json");
    }
}
