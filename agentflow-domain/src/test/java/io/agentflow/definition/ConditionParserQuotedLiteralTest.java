package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.agentflow.definition.DefinitionModels.EvaluationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖引号字面量与布尔连接符的词法边界，防止表单文本改变审批路由。
 * @author owlzhangfq@gmail.com
 */
class ConditionParserQuotedLiteralTest {
    private final ConditionParser parser = new ConditionParser();

    @ParameterizedTest
    @ValueSource(strings = {"reason == 'Research AND Development'", "reason == \"Research AND Development\""})
    void preservesConnectorsInsideEitherQuotedLiteral(String condition) {
        assertThat(parser.parse(condition).evaluate(context("Research AND Development", "other"))).isTrue();
    }

    @Test
    void quotedTextCannotBecomeAnotherPublishedBranchCondition() {
        FormSchema schema = new FormSchema(1, List.of(textField("reason"), textField("department")));
        var condition = parser.parse("reason == 'x OR department == y'");
        schema.validateCondition(condition);

        assertThat(condition.evaluate(context("x OR department == y", "other"))).isTrue();
        assertThat(condition.evaluate(context("other", "y'"))).isFalse();
    }

    @Test
    void keepsAndPrecedenceWithRealConnectorsAndQuotedWords() {
        var condition = parser.parse("reason == 'Research AND Development' AND department == \"legal OR finance\" OR reason == fallback");

        assertThat(condition.evaluate(context("Research AND Development", "legal OR finance"))).isTrue();
        assertThat(condition.evaluate(context("Research AND Development", "other"))).isFalse();
        assertThat(condition.evaluate(context("fallback", "other"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reason == 'unterminated", "reason == \"unterminated", "reason == 'closed' suffix", "reason == \"closed\"suffix"})
    void rejectsUnclosedQuotesAndTextAfterACompleteLiteral(String condition) {
        assertInvalid(condition);
    }

    @Test
    void preservesExistingUnquotedValuesAndWholeWordConnectors() {
        assertThat(parser.parse("reason == O'Reilly Development AND department == finance")
                .evaluate(context("O'Reilly Development", "finance"))).isTrue();
        assertThat(parser.parse("reason == O'Reilly")
                .evaluate(context("O'Reilly", "finance"))).isTrue();
        assertThat(parser.parse("reason == CANDOR OR department == finance")
                .evaluate(context("CANDOR", "other"))).isTrue();
        assertThat(parser.parse("reason EXISTS AND department NOT_EXISTS")
                .evaluate(new EvaluationContext(Map.of("reason", "present")))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reason == '${runtime.exec}'", "reason == '#{bean}'", "reason == 'x;y'", "reason == '(x)'"})
    void stillRejectsTheExistingForbiddenExpressionSyntax(String condition) {
        assertInvalid(condition);
    }

    private void assertInvalid(String condition) {
        assertThatThrownBy(() -> parser.parse(condition)).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("INVALID_CONDITION"));
    }

    private EvaluationContext context(String reason, String department) {
        return new EvaluationContext(Map.of("reason", reason, "department", department),
                Map.of("reason", "TEXT", "department", "TEXT"));
    }

    private FormSchema.Field textField(String key) {
        return new FormSchema.Field(key, key, FormSchema.FieldType.TEXT, false, null, null, null, null, null);
    }
}
