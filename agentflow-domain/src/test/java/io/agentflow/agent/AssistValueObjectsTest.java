package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 输入与模型输出在各自边界完成结构校验，限制无证据陈述和无界数据。
 * @author owlzhangfq@gmail.com
 */
class AssistValueObjectsTest {
    private static final AssistInput.Reference SOURCE = new AssistInput.Reference("form:items[0].amount", "a".repeat(64));

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/private", "file:/private", "form:", "../private", "form:金额"})
    void sourceIdentifiersCannotBeExternalResourceAddresses(String sourceId) {
        fails("INVALID_AGENT_INPUT", () -> new AssistInput.Reference(sourceId, "a".repeat(64)));
    }

    @Test
    void duplicateSourcesDifferentDigestsAndOversizedListsAreRejected() {
        var otherDigest = new AssistInput.Reference(SOURCE.sourceId(), "b".repeat(64));
        fails("INVALID_AGENT_INPUT", () -> input(List.of(SOURCE, otherDigest)));
        fails("INVALID_AGENT_INPUT", () -> input(List.of()));
        var tooMany = IntStream.rangeClosed(0, AssistInput.MAX_REFERENCES)
                .mapToObj(index -> new AssistInput.Reference("form:field" + index, "a".repeat(64))).toList();
        fails("INVALID_AGENT_INPUT", () -> input(tooMany));
        assertThat(input(tooMany.subList(0, AssistInput.MAX_REFERENCES)).references()).hasSize(AssistInput.MAX_REFERENCES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aaa", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "z"})
    void sourceDigestsMustUseCanonicalSha256(String digest) {
        fails("INVALID_AGENT_INPUT", () -> new AssistInput.Reference("form:amount", digest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01"})
    void confidenceOutsideItsDeclaredRangeIsRejected(String confidence) {
        fails("INVALID_AGENT_OUTPUT", () -> suggestion(List.of(claim()), new BigDecimal(confidence)));
    }

    @Test
    void everyClaimRequiresBoundedTextAndDistinctEvidence() {
        fails("INVALID_AGENT_OUTPUT", () -> new AssistSuggestion.Claim("摘要", List.of()));
        fails("INVALID_AGENT_OUTPUT", () -> new AssistSuggestion.Claim("摘要", List.of(SOURCE, SOURCE)));
        fails("INVALID_AGENT_OUTPUT", () -> new AssistSuggestion.Claim(" ", List.of(SOURCE)));
        fails("INVALID_AGENT_OUTPUT", () -> new AssistSuggestion.Claim("x".repeat(AssistSuggestion.MAX_CLAIM_LENGTH + 1), List.of(SOURCE)));
        fails("INVALID_AGENT_OUTPUT", () -> suggestion(List.of(), BigDecimal.ONE));
        fails("INVALID_AGENT_OUTPUT", () -> suggestion(java.util.Collections.nCopies(AssistSuggestion.MAX_CLAIMS + 1, claim()), BigDecimal.ONE));
        fails("INVALID_AGENT_OUTPUT", () -> suggestion(List.of(claim()), null));
    }

    private AssistInput input(List<AssistInput.Reference> references) { return new AssistInput(UUID.randomUUID(), 1, 1, references); }
    private AssistSuggestion.Claim claim() { return new AssistSuggestion.Claim("摘要", List.of(SOURCE)); }
    private AssistSuggestion suggestion(List<AssistSuggestion.Claim> claims, BigDecimal confidence) {
        return new AssistSuggestion("test", "test-model-v1", "prompt-v1", claims, confidence);
    }
    private static void fails(String code, Runnable operation) {
        assertThatExceptionOfType(DomainException.class).isThrownBy(operation::run)
                .satisfies(exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
