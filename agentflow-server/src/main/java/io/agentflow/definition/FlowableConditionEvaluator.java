package io.agentflow.definition;

import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static io.agentflow.definition.DefinitionModels.EvaluationContext;

/**
 * Flowable 条件表达式唯一允许调用的固定 Bean。表达式参数是 Base64 编码的受限条件文本，
 * 运行时仍由领域 ConditionParser 解析，用户文本不会被当成 JUEL 语法执行。
 * @author owlzhangfq@gmail.com
 */
@Component("flowableConditionEvaluator")
public class FlowableConditionEvaluator {
    private final ConditionParser parser = new ConditionParser();

    /** 使用流程变量求值受限条件。 */
    public boolean matches(DelegateExecution execution, String encodedCondition) {
        if (execution == null || encodedCondition == null || encodedCondition.isBlank()) {
            return false;
        }
        String condition = new String(Base64.getDecoder().decode(encodedCondition), StandardCharsets.UTF_8);
        Map<String, Object> variables = new HashMap<>(execution.getVariables());
        Object formData = variables.get("formData");
        Object formFieldTypes = variables.get("formFieldTypes");
        if (formFieldTypes instanceof Map<?, ?> declaredTypes) {
            Map<String, String> types = new HashMap<>();
            declaredTypes.forEach((key, value) -> types.put((String) key, (String) value));
            Map<String, Object> values = new HashMap<>();
            if (formData instanceof Map<?, ?> formValues) formValues.forEach((key, value) -> values.put((String) key, value));
            return parser.parse(condition).evaluate(new EvaluationContext(values, types));
        }
        if (formData instanceof Map<?, ?> formValues) {
            formValues.forEach((key, value) -> {
                if (key != null) {
                    variables.putIfAbsent(String.valueOf(key), value);
                }
            });
        }
        return parser.parse(condition).evaluate(new EvaluationContext(variables));
    }
}
