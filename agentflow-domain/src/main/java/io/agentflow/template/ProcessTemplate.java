package io.agentflow.template;

import io.agentflow.definition.DefinitionModels.EvaluationContext;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionSimulator;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FormValidationException;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.procurement.ProcurementPaymentFormContract;
import io.agentflow.budget.BudgetAdjustmentFormContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内置模板值对象；目录发布后不可变，复制出的定义独立演进。
 * @author owlzhangfq@gmail.com
 */
public record ProcessTemplate(String key, long templateVersion, String name, String category,
                              String description, String scope, String businessType,
                              List<String> dependencies, List<String> defaultRoles,
                              Map<String, String> fieldDescriptions, List<String> risks, String upgradePolicy,
                              Map<String, String> notificationTexts, boolean notificationsAvailable,
                              Graph graph, FormSchema formSchema, List<Scenario> scenarios) {
    private static final String FORM = "FORM";
    private static final String PROCUREMENT_PAYMENT = "PROCUREMENT_PAYMENT";
    private static final String BUDGET_ADJUSTMENT = "BUDGET_ADJUSTMENT";

    /** 冻结目录说明与业务图；启用通知的模板仅支持已实现的三类站内文案。 */
    public ProcessTemplate {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || templateVersion < 1) {
            throw new IllegalArgumentException("Template identity is invalid");
        }
        for (String value : new String[]{name, category, description, scope, businessType, upgradePolicy}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Template description is required");
        }
        if (!java.util.Set.of(FORM, PROCUREMENT_PAYMENT, BUDGET_ADJUSTMENT).contains(businessType)) {
            throw new IllegalArgumentException("Template business type is not supported");
        }
        dependencies = List.copyOf(dependencies);
        defaultRoles = List.copyOf(defaultRoles);
        fieldDescriptions = Map.copyOf(fieldDescriptions);
        risks = List.copyOf(risks);
        notificationTexts = Map.copyOf(notificationTexts);
        graph = Objects.requireNonNull(graph);
        formSchema = Objects.requireNonNull(formSchema);
        scenarios = List.copyOf(scenarios);
        if (scenarios.isEmpty() || !java.util.Set.of("SUBMITTED", "RETURNED", "APPROVED").containsAll(notificationTexts.keySet())) {
            throw new IllegalArgumentException("Template scenarios or notification capability is invalid");
        }
        new NotificationTexts(notificationTexts.get("SUBMITTED"), notificationTexts.get("RETURNED"), notificationTexts.get("APPROVED"));
    }

    /** 复制时显式启用目录声明的文案；参考文案不被悄悄启用。 */
    public NotificationTexts copiedNotificationTexts() {
        return notificationsAvailable ? new NotificationTexts(notificationTexts.get("SUBMITTED"),
                notificationTexts.get("RETURNED"), notificationTexts.get("APPROVED")) : NotificationTexts.EMPTY;
    }

    /** 启动时执行同一套表单与条件语义，目录损坏必须阻止应用启动。 */
    public void verifyScenarios() {
        List<String> errors = new DefinitionValidator().validate(graph, formSchema);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Template graph is invalid: " + key + ": " + String.join(", ", errors));
        if (PROCUREMENT_PAYMENT.equals(businessType)) ProcurementPaymentFormContract.requireReview(graph, formSchema);
        else if (ProcurementPaymentFormContract.structured(formSchema)) throw new IllegalArgumentException("Procurement template must declare its actual business type");
        if (BUDGET_ADJUSTMENT.equals(businessType)) BudgetAdjustmentFormContract.requireReview(graph, formSchema);
        else if (BudgetAdjustmentFormContract.structured(formSchema)) throw new IllegalArgumentException("Budget adjustment template must declare its actual business type");
        if (scenarios.stream().map(Scenario::id).distinct().count() != scenarios.size()) {
            throw new IllegalArgumentException("Template scenario ids are duplicated: " + key);
        }
        for (Scenario scenario : scenarios) {
            Map<String, String> actualErrors = Map.of();
            try {
                formSchema.validateSubmission(scenario.payload());
            } catch (FormValidationException exception) {
                actualErrors = exception.fieldErrors();
            }
            if (!actualErrors.equals(scenario.expectedFieldErrors())) {
                throw new IllegalArgumentException("Template scenario field errors do not match: " + key + "/" + scenario.id());
            }
            List<String> actualPath = actualErrors.isEmpty()
                    ? new DefinitionSimulator().simulate(graph, new EvaluationContext(scenario.payload(), formSchema.fieldTypes()))
                    : List.of();
            if (!actualPath.equals(scenario.expectedPath())) {
                throw new IllegalArgumentException("Template scenario path does not match: " + key + "/" + scenario.id());
            }
        }
    }

    /**
     * 模板验收场景；递归冻结 JSON 值，保留显式 null，不共享调用方可变集合。
     * @author owlzhangfq@gmail.com
     */
    public record Scenario(String id, String name, String description, Map<String, Object> payload,
                           List<String> expectedPath, Map<String, String> expectedFieldErrors) {
        public Scenario {
            if (id == null || id.isBlank() || name == null || name.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException("Template scenario identity is required");
            }
            payload = immutableMap(payload);
            expectedPath = List.copyOf(expectedPath);
            expectedFieldErrors = Map.copyOf(expectedFieldErrors);
        }

        private static Map<String, Object> immutableMap(Map<String, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(Objects.requireNonNull(key), immutableValue(value)));
            return Collections.unmodifiableMap(result);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, nested) -> result.put((String) Objects.requireNonNull(key), immutableValue(nested)));
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>();
                list.forEach(nested -> result.add(immutableValue(nested)));
                return Collections.unmodifiableList(result);
            }
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
            throw new IllegalArgumentException("Template payload must contain JSON values");
        }
    }
}
