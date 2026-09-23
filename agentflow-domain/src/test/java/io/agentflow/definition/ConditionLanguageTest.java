package io.agentflow.definition;

import io.agentflow.form.FormSchema;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 条件扩展必须与历史语义隔离，金额不能经过浮点转换。
 * @author owlzhangfq@gmail.com
 */
class ConditionLanguageTest {
    private final ConditionParser parser = new ConditionParser();

    @Test
    void evaluatesGroupingNegationAndEnumMembership() {
        var condition = parser.parse("(amount > 5000 && category in [\"TRAVEL\",\"DAILY\"]) || !approved EXISTS", 2);
        var types = Map.of("amount", "NUMBER", "category", "SELECT", "approved", "BOOLEAN");
        assertThat(condition.evaluate(new EvaluationContext(Map.of("amount", "5000.000000001", "category", "TRAVEL", "approved", false), types))).isTrue();
        assertThat(condition.evaluate(new EvaluationContext(Map.of("amount", "5000", "category", "TRAVEL", "approved", false), types))).isFalse();
        assertThat(condition.evaluate(new EvaluationContext(Map.of("amount", "5000", "category", "OTHER"), types))).isTrue();
    }

    @Test
    void keepsLegacyConnectorTextLiteralAndRejectsNewSyntaxInV1() {
        assertThat(parser.parse("memo == a && b", 1).evaluate(new EvaluationContext(Map.of("memo", "a && b")))).isTrue();
        assertThatThrownBy(() -> parser.parse("(amount > 1)", 1)).hasMessageContaining("allowlisted");
        assertThatThrownBy(() -> parser.parse("category in [\"TRAVEL\"]", 1)).hasMessageContaining("allowlisted");
        assertThat(new Graph(List.of(), List.of()).conditionLanguageVersion()).isEqualTo(1);
        assertThatThrownBy(() -> parser.parse("", 3)).hasMessageContaining("version");
    }

    @Test
    void precedenceAndPresenceRemainExplicitIncludingNegationOfMissingValues() {
        var values = new EvaluationContext(Map.of("a", false, "b", true, "c", true));
        assertThat(parser.parse("a == true && b == true || c == true", 2).evaluate(values)).isTrue();
        assertThat(parser.parse("a == true AND (b == true OR c == true)", 2).evaluate(values)).isFalse();
        assertThat(parser.parse("!(a == true || !b == true)", 2).evaluate(values)).isTrue();
        assertThat(parser.parse("!missing == true", 2).evaluate(values)).isTrue();
        assertThat(parser.parse("missing EXISTS && !missing == true", 2).evaluate(values)).isFalse();
        assertThat(parser.parse("category IN [\"A\"]", 2).evaluate(new EvaluationContext(Map.of(), Map.of("category", "SELECT")))).isFalse();
    }

    @Test
    void rejectsMalformedOrExecutableContentWithBoundedCharacterPositions() {
        for (String source : List.of("()", "(a == 1", "a == 1)", "a == 1 &&", "a in []", "a IN [\"A\",]", "a IN [1]",
                "a IN [\"A\" \"B\"]", "a == \"x\" trailing", "bean.run()", "${bean.run()}", "a == x", "a == 'unterminated",
                "a == \"\\q\"", "a == \"\\u12xx\"", "a == \"\\u003b\"", "a == \"\\u0024{bean}\"", "a == 1;")) {
            assertThatThrownBy(() -> parser.parse(source, 2)).as(source).isInstanceOfSatisfying(ConditionSyntaxException.class,
                    error -> assertThat(error.position()).isBetween(1, source.length() + 1));
        }
        assertThatThrownBy(() -> parser.parse("amount >", 2)).isInstanceOfSatisfying(ConditionSyntaxException.class,
                error -> assertThat(error.position()).isEqualTo(9));
        assertThat(parser.parse("memo == \"a (b) AND \\\"c\\\" \\u4e2d\\n\"", 2)
                .evaluate(new EvaluationContext(Map.of("memo", "a (b) AND \"c\" 中\n")))).isTrue();
    }

    @Test
    void enforcesLimitsAtTheirBoundariesAndDoesNotRetainParserState() {
        parser.parse("(".repeat(16) + "a EXISTS" + ")".repeat(16), 2);
        assertThatThrownBy(() -> parser.parse("(".repeat(17) + "a EXISTS" + ")".repeat(17), 2)).hasMessageContaining("nesting");
        parser.parse(String.join(" AND ", java.util.Collections.nCopies(100, "a EXISTS")), 2);
        assertThatThrownBy(() -> parser.parse(String.join(" OR ", java.util.Collections.nCopies(101, "a EXISTS")), 2)).hasMessageContaining("comparisons");
        parser.parse("a IN [" + String.join(",", java.util.Collections.nCopies(50, "\"A\"")) + "]", 2);
        assertThatThrownBy(() -> parser.parse("a IN [" + String.join(",", java.util.Collections.nCopies(51, "\"A\"")) + "]", 2)).hasMessageContaining("membership");
        parser.parse("a == \"" + "x".repeat(256) + "\"", 2);
        assertThatThrownBy(() -> parser.parse("a == \"" + "x".repeat(257) + "\"", 2)).hasMessageContaining("long");
        assertThatThrownBy(() -> parser.parse(" ".repeat(4001), 2)).hasMessageContaining("long");
        assertThat(IntStream.range(0, 30).parallel().allMatch(i -> parser.parse("a == 1", 2)
                .evaluate(new EvaluationContext(Map.of("a", 1))))).isTrue();
    }

    @Test
    void validatesAllTermsAgainstTheSchemaEvenWhenRuntimeWouldShortCircuit() {
        var schema = new FormSchema(1, List.of(
                new FormSchema.Field("category", "类型", FormSchema.FieldType.SELECT, false, null, null, null, null,
                        List.of(new FormSchema.Option("A", "甲"), new FormSchema.Option("B", "乙"))),
                new FormSchema.Field("amount", "金额", FormSchema.FieldType.NUMBER, false, null, null, null, null, null)));
        schema.validateCondition(parser.parse("category IN [\"A\",\"B\"] && !(amount > 1)", 2));
        for (String source : List.of("category IN [\"UNKNOWN\"]", "amount IN [\"1\"]", "category EXISTS OR !unknown EXISTS")) {
            assertThatThrownBy(() -> schema.validateCondition(parser.parse(source, 2))).as(source).isInstanceOfSatisfying(io.agentflow.common.DomainException.class,
                    error -> assertThat(error.code()).isEqualTo("INVALID_CONDITION"));
        }
        assertThatThrownBy(() -> parser.parse("category IN [\"A\"]", 2).evaluate(new EvaluationContext(Map.of("category", "A"))))
                .hasMessageContaining("select");
    }

    @Test
    void upgradesLegacyAstWithoutReinterpretingSymbolsQuotesOrPrecision() {
        for (String source : List.of("memo == a && b", "memo == a || b", "memo == O'Reilly", "memo == 'R AND D OR Finance'",
                "memo == a\\b\"c'd", "amount >= 99999999999999999999.123456789", "amount > 1 AND memo EXISTS OR amount < 0", "")) {
            var old = new Graph(List.of(), List.of(new Edge("route", "s", "e", source, true)));
            var upgraded = new ConditionLanguageUpgrade().upgrade(old);
            assertThat(upgraded.conditionLanguageVersion()).isEqualTo(2);
            assertThat(upgraded.edges().get(0).defaultBranch()).isTrue();
            assertThat(old.conditionLanguageVersion()).isEqualTo(1);
            for (String memo : List.of("a && b", "a || b", "O'Reilly", "R AND D OR Finance", "a\\b\"c'd")) {
                var context = new EvaluationContext(Map.of("memo", memo, "amount", "99999999999999999999.123456788"));
                assertThat(parser.parse(upgraded.edges().get(0).condition(), 2).evaluate(context)).as(source)
                        .isEqualTo(parser.parse(source, 1).evaluate(context));
            }
            assertThat(new ConditionLanguageUpgrade().upgrade(upgraded)).isEqualTo(upgraded);
        }
        var invalid = new Graph(List.of(), List.of(new Edge("good", "s", "e", "a == 1"), new Edge("bad", "s", "e", "a IN []")));
        assertThatThrownBy(() -> new ConditionLanguageUpgrade().upgrade(invalid)).hasMessageContaining("allowlisted");
        assertThat(invalid.edges().get(0).condition()).isEqualTo("a == 1");
    }
}
