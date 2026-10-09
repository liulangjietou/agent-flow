package io.agentflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兼容提供者可以省略用量，但缺失、异常和真实零用量必须能够区分。
 * @author owlzhangfq@gmail.com
 */
class OpenAiTextUsageTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper());

    @Test void preservesReportedCountsAndRealZero() {
        var usage = OpenAiTextClient.usage(json.read("{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150}", JsonNode.class));
        assertThat(usage).isEqualTo(new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, 120L, 30L, 150L));
        assertThat(OpenAiTextClient.usage(json.read("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}", JsonNode.class)).status())
                .isEqualTo(OpenAiTextClient.UsageStatus.REPORTED);
    }

    @Test void missingUsageIsNotZero() {
        for (var node : java.util.List.of(json.read("null", JsonNode.class), json.read("{}", JsonNode.class).path("usage"))) {
            var usage = OpenAiTextClient.usage(node);
            assertThat(usage.status()).isEqualTo(OpenAiTextClient.UsageStatus.NOT_REPORTED);
            assertThat(usage.totalTokens()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"prompt_tokens\":\"1\",\"completion_tokens\":2,\"total_tokens\":3}",
            "{\"prompt_tokens\":-1,\"completion_tokens\":2,\"total_tokens\":1}",
            "{\"prompt_tokens\":1.0,\"completion_tokens\":2,\"total_tokens\":3}",
            "{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":4}",
            "{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":1,\"total_tokens\":0}",
            "{\"prompt_tokens\":9223372036854775808,\"completion_tokens\":0,\"total_tokens\":9223372036854775808}"})
    void malformedOrOverflowingUsageRemainsUnknown(String value) {
        var usage = OpenAiTextClient.usage(json.read(value, JsonNode.class));
        assertThat(usage.status()).isEqualTo(OpenAiTextClient.UsageStatus.INVALID);
        assertThat(usage.inputTokens()).isNull(); assertThat(usage.outputTokens()).isNull(); assertThat(usage.totalTokens()).isNull();
    }
}
