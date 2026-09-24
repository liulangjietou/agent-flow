package io.agentflow.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 权威解析器校对前端分组的固定序列化契约及独立业务样例，不在前端另建求值器。
 * @author owlzhangfq@gmail.com
 */
class ConditionGroupContractTest {
    @Test
    void visualSerializationPreservesDomainResultsIncludingAbsentFieldsAndExactDecimals() throws Exception {
        try (var input = getClass().getResourceAsStream("/condition-group-cases.json")) {
            assertThat(input).isNotNull();
            var fixture = new JsonUtil(new ObjectMapper()).read(new String(input.readAllBytes(), StandardCharsets.UTF_8), Fixture.class);
            var types = fixture.fields().stream().collect(Collectors.toMap(field -> field.get("key").toString(), field -> field.get("type").toString()));
            var parser = new ConditionParser();
            for (var item : fixture.cases()) {
                var original = parser.parse(item.source(), 2);
                var edited = parser.parse(item.serialized(), 2);
                for (var sample : item.samples()) {
                    var context = new DefinitionModels.EvaluationContext(sample.values(), types);
                    assertThat(original.evaluate(context)).as("original %s %s", item.name(), sample.values()).isEqualTo(sample.expected());
                    assertThat(edited.evaluate(context)).as("visual %s %s", item.name(), sample.values()).isEqualTo(sample.expected());
                }
            }
        }
    }

    /**
     * 前后端共享的字段和条件样例。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(List<Map<String, Object>> fields, List<Case> cases) { }
    /**
     * 原表达式、可视化输出与独立预期值。
     * @author owlzhangfq@gmail.com
     */
    private record Case(String name, String source, String serialized, List<Sample> samples) { }
    /**
     * 单个输入及其真实预期，不从前端执行结果反推。
     * @author owlzhangfq@gmail.com
     */
    private record Sample(Map<String, Object> values, boolean expected) { }
}
