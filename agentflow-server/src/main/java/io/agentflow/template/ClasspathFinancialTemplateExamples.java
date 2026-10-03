package io.agentflow.template;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.AdvanceRequestContent;
import io.agentflow.expense.ExpenseCategoryCatalog;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpensePlanContent;
import io.agentflow.expense.ExpensePolicyDefinition;
import io.agentflow.expense.ExpenseReductionService;
import io.agentflow.finance.AccountMappingDefinition;
import io.agentflow.finance.FinanceCatalog;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 固定员工财务样例只提供配置和业务输入；加载时复用领域契约，不调用财务或配置写入用例。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ClasspathFinancialTemplateExamples {
    private static final String LOCATION = "classpath:process-template-examples/employee-finance.json";
    private static final Set<String> TEMPLATE_KEYS = Set.of("expense-report", "expense-plan", "advance-request");
    private final JsonNode document;
    private final Summary summary;

    /** 模板内的配置、金额和业务输入损坏时阻止启动，不能提供无法使用的演示包。 */
    public ClasspathFinancialTemplateExamples(ResourceLoader resources, JsonUtil json) {
        try (var input = resources.getResource(LOCATION).getInputStream()) {
            document = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            if (document.path("schemaVersion").asInt() != 1 || !document.path("version").isIntegralNumber()
                    || document.path("version").asLong() < 1 || !document.path("key").asText().equals("employee-finance")) {
                throw new IllegalArgumentException("Financial example identity is invalid");
            }
            var declared = json.read(json.write(document.path("templateKeys")), String[].class);
            if (declared.length != TEMPLATE_KEYS.size() || !new HashSet<>(List.of(declared)).equals(TEMPLATE_KEYS)) {
                throw new IllegalArgumentException("Financial example templates do not match");
            }
            var configuration = document.path("configuration");
            var categories = new ExpenseCategoryCatalog("template-example", 1,
                    List.of(json.read(json.write(configuration.path("categories")), ExpenseCategoryCatalog.Category[].class)));
            json.read(json.write(configuration.path("expensePolicy")), ExpensePolicyDefinition.class).requirePublishable(categories);
            json.read(json.write(configuration.path("accountMapping")), AccountMappingDefinition.class).requirePublishable(categories);
            var receipts = json.read(json.write(configuration.path("receiptOptions")), FinanceCatalog.LegalEntity[].class);
            if (receipts.length != 2 || receipts[0].paperReceiptRequired() == receipts[1].paperReceiptRequired()) {
                throw new IllegalArgumentException("Financial examples require both receipt settings");
            }
            var ids = new HashSet<String>();
            for (var scenario : document.required("scenarios")) {
                if (scenario.path("id").asText().isBlank() || !ids.add(scenario.path("id").asText())) {
                    throw new IllegalArgumentException("Financial example scenario identity is invalid");
                }
                String content = json.write(scenario.required("content"));
                switch (scenario.path("templateKey").asText()) {
                    case "expense-report" -> json.read(content, ExpenseContent.class);
                    case "expense-plan" -> json.read(content, ExpensePlanContent.class);
                    case "advance-request" -> json.read(content, AdvanceRequestContent.class);
                    default -> throw new IllegalArgumentException("Financial example template is unsupported");
                }
                if (scenario.has("reductions")) {
                    json.read(json.write(scenario.get("reductions")), ExpenseReductionService.LineInput[].class);
                }
            }
            if (ids.isEmpty()) throw new IllegalArgumentException("Financial examples require scenarios");
            summary = new Summary(document.path("key").asText(), document.path("version").asLong(),
                    document.path("name").asText(), document.path("description").asText(), ids.size());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Unable to load financial template examples: " + LOCATION, error);
        }
    }

    /** 仅三个关联模板携带包摘要，旧模板不显示无关财务样例。 */
    public Summary summary(String templateKey) { return TEMPLATE_KEYS.contains(templateKey) ? summary : null; }

    /** 返回独立 JSON 副本，调用方不能修改共享目录；未知模板不能被当作资源路径。 */
    public JsonNode get(String templateKey) {
        if (!TEMPLATE_KEYS.contains(templateKey)) throw new DomainException("NOT_FOUND", "Financial template examples not found");
        return document.deepCopy();
    }

    /**
     * 目录只携带包摘要，完整配置和输入按用户选择读取。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(String key, long version, String name, String description, int scenarioCount) { }
}
