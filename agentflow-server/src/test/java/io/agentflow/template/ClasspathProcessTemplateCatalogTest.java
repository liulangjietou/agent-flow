package io.agentflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FormSchemaJsonDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 固定目录资源启动验收，损坏的定义或场景不能以部分可用状态对外服务。
 * @author owlzhangfq@gmail.com
 */
class ClasspathProcessTemplateCatalogTest {
    private final ResourceLoader resources = new DefaultResourceLoader();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(
            new SimpleModule().addDeserializer(FormSchema.class, new FormSchemaJsonDeserializer())));

    @Test
    void loadsDeliveredTemplatesAndVerifiesEveryScenario() {
        var catalog = new ClasspathProcessTemplateCatalog(resources, json);
        assertThat(catalog.list()).extracting(ProcessTemplate::key).containsExactly("leave-request", "seal-application", "contract-review", "procurement-payment", "budget-adjustment",
                "expense-report", "expense-plan", "advance-request");
        assertThat(catalog.list().stream().mapToInt(template -> template.scenarios().size()).sum()).isEqualTo(43);
        catalog.list().forEach(ProcessTemplate::verifyScenarios);
        assertThatThrownBy(catalog.list()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"procurement-payment,business-type", "procurement-payment,masked", "procurement-payment,missing-sensitive",
            "budget-adjustment,business-type", "budget-adjustment,masked", "budget-adjustment,missing-sensitive",
            "expense-report,business-type", "expense-report,masked", "expense-report,missing-sensitive",
            "expense-plan,business-type", "expense-plan,masked", "expense-plan,missing-sensitive",
            "advance-request,business-type", "advance-request,masked", "advance-request,missing-sensitive"})
    void structuredTemplateCannotMislabelItsBusinessOrHideEvidenceFromApprovers(String templateKey, String corruption) throws Exception {
        String location = "classpath:process-templates/" + templateKey + ".json";
        ObjectNode template;
        try (var input = resources.getResource(location).getInputStream()) { template = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), ObjectNode.class); }
        var field = (ObjectNode) template.at("/formSchema/fields/0");
        switch (corruption) {
            case "business-type" -> template.put("businessType", "FORM");
            case "masked" -> ((ObjectNode) field.path("nodeAccess")).put("finance", "MASKED");
            default -> field.put("sensitive", false);
        }
        ResourceLoader replaced = mock(ResourceLoader.class);
        when(replaced.getResource(anyString())).thenAnswer(call -> location.equals(call.getArgument(0))
                ? new ByteArrayResource(json.write(template).getBytes(StandardCharsets.UTF_8)) : resources.getResource(call.getArgument(0)));
        assertThatThrownBy(() -> new ClasspathProcessTemplateCatalog(replaced, json)).isInstanceOf(IllegalStateException.class).hasMessageContaining(location);
    }

    @ParameterizedTest
    @CsvSource({"receipt,EXPENSE_RECEIPT_PATH_REQUIRED", "finance,EXPENSE_FINANCE_PATH_REQUIRED"})
    void expenseTemplateRequiresReceiptAndFinanceOnEveryCompletionPath(String nodeId, String expectedCode) throws Exception {
        String location = "classpath:process-templates/expense-report.json";
        ObjectNode template;
        try (var input = resources.getResource(location).getInputStream()) {
            template = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), ObjectNode.class);
        }
        for (var node : template.path("graph").path("nodes")) {
            if (nodeId.equals(node.path("id").asText())) ((ObjectNode) node.path("properties")).put("expenseStage", "BUSINESS");
        }
        ResourceLoader replaced = mock(ResourceLoader.class);
        when(replaced.getResource(anyString())).thenAnswer(call -> location.equals(call.getArgument(0))
                ? new ByteArrayResource(json.write(template).getBytes(StandardCharsets.UTF_8)) : resources.getResource(call.getArgument(0)));
        assertThatThrownBy(() -> new ClasspathProcessTemplateCatalog(replaced, json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(location)
                .hasCauseInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error.getCause()).code()).isEqualTo(expectedCode));
    }

    @Test
    void corruptedGraphReportsResourcePathAndConcreteValidationReasons() throws Exception {
        String location = "classpath:process-templates/leave-request.json";
        ObjectNode template;
        try (var input = resources.getResource(location).getInputStream()) {
            template = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), ObjectNode.class);
        }
        ObjectNode edge = (ObjectNode) template.path("graph").path("edges").get(0);
        String edgeId = edge.path("id").asText();
        edge.put("target", "missing-node");
        ResourceLoader replaced = mock(ResourceLoader.class);
        when(replaced.getResource(location)).thenReturn(new ByteArrayResource(json.write(template).getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> new ClasspathProcessTemplateCatalog(replaced, json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(location)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .satisfies(error -> assertThat(error.getCause().getMessage())
                        .contains("leave-request", "EDGE_NODE_NOT_FOUND:" + edgeId, "NODE_UNREACHABLE:manager"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"syntax", "missing", "key", "unknown-property", "path", "field-errors", "notification", "version-fraction", "version-string", "business-type"})
    void rejectsCorruptedResourcesAtStartup(String corruption) throws Exception {
        String location = "classpath:process-templates/leave-request.json";
        ObjectNode template;
        try (var input = resources.getResource(location).getInputStream()) {
            template = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), ObjectNode.class);
        }
        switch (corruption) {
            case "key" -> template.put("key", "wrong-key");
            case "unknown-property" -> template.put("unavailable-feature", true);
            case "path" -> ((ObjectNode) template.path("scenarios").get(0)).putArray("expectedPath").add("start").add("end");
            case "field-errors" -> ((ObjectNode) template.path("scenarios").get(0)).putObject("expectedFieldErrors").put("reason", "REQUIRED");
            case "notification" -> ((ObjectNode) template.get("notificationTexts")).put("APPROVED", "长".repeat(501));
            case "version-fraction" -> template.put("templateVersion", 1.5);
            case "version-string" -> template.put("templateVersion", "1");
            case "business-type" -> template.put("businessType", "EXPENSE");
            default -> { }
        }
        ResourceLoader replaced = mock(ResourceLoader.class);
        String content = corruption.equals("syntax") ? "{" : json.write(template);
        when(replaced.getResource(anyString())).thenAnswer(call -> {
            String requested = call.getArgument(0);
            if (!location.equals(requested)) return resources.getResource(requested);
            if (corruption.equals("missing")) return resources.getResource("classpath:process-templates/missing.json");
            return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
        });
        assertThatThrownBy(() -> new ClasspathProcessTemplateCatalog(replaced, json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(location);
    }
}
