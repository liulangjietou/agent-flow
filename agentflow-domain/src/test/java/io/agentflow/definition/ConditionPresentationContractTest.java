package io.agentflow.definition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 前端说明和领域解析共同消费固定语义样例，防止中文说明误报优先级或字面量。
 * @author owlzhangfq@gmail.com
 */
class ConditionPresentationContractTest {
    @Test
    void sharedPresentationCasesMatchAuthoritativeDomainTree() throws Exception {
        var json = new JsonUtil(new ObjectMapper());
        try (var input = getClass().getResourceAsStream("/condition-presentation-cases.json")) {
            assertThat(input).isNotNull();
            List<Fixture> cases = json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), new TypeReference<>() { });
            for (Fixture fixture : cases) {
                if (fixture.tree() == null) {
                    assertThatThrownBy(() -> new ConditionParser().parse(fixture.source(), 2)).as(fixture.source()).isInstanceOf(ConditionSyntaxException.class);
                } else {
                    assertThat(tree(new ConditionParser().parse(fixture.source(), 2))).as(fixture.source()).isEqualTo(fixture.tree());
                }
            }
        }
    }

    private Map<String, Object> tree(ConditionAst ast) {
        if (ast instanceof Logical logical) return Map.of("kind", logical.kind().name(), "terms", logical.terms().stream().map(this::tree).toList());
        if (ast instanceof Negation negation) return Map.of("kind", "not", "term", tree(negation.term()));
        if (ast instanceof Membership membership) return Map.of("kind", "comparison", "row", Map.of("field", membership.field(), "operator", "IN", "value", "", "values", membership.literals()));
        var comparison = (Comparison) ast;
        String operator = switch (comparison.operator()) {
            case EQ -> "=="; case NE -> "!="; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<=";
            case EXISTS -> "EXISTS"; case NOT_EXISTS -> "NOT_EXISTS";
        };
        return Map.of("kind", "comparison", "row", Map.of("field", comparison.field(), "operator", operator, "value", comparison.literal()));
    }

    /**
     * null 语义树表示必须拒绝的语法。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(String source, Map<String, Object> tree) { }
}
