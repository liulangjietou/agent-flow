package io.agentflow.definition;

import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FieldVisibility;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static io.agentflow.approval.model.SubmissionRisk.Level.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 风险只能来自明确发布的公共字段规则，缺少依据与低风险必须区分。
 * @author owlzhangfq@gmail.com
 */
class ApprovalRiskPolicyTest {
    private final UUID definitionId = UUID.randomUUID();
    private final FormSchema schema = new FormSchema(1, List.of(
            field("amount", FormSchema.FieldType.NUMBER, false, null),
            field("memo", FormSchema.FieldType.TEXT, false, null)));

    @Test
    void keepsHighestOfAllMatchesWithExactDecimalAndVersionSource() {
        var policy = new ApprovalRiskPolicy(List.of(rule("routine", LOW, "amount >= 0"),
                rule("large", HIGH, "amount > 9999999999999999.123456789"), rule("review", MEDIUM, "amount > 1")));
        assertThat(policy.validate(schema, 2)).isEmpty();
        var result = policy.assess(definitionId, 7, schema, 2, Map.of("amount", "9999999999999999.123456790"));
        assertThat(result.level()).isEqualTo(HIGH);
        assertThat(result.definitionId()).isEqualTo(definitionId);
        assertThat(result.definitionVersion()).isEqualTo(7);
        assertThat(result.matches()).extracting(SubmissionRisk.Match::ruleId).containsExactly("routine", "large", "review");
        assertThat(policy.assess(definitionId, 7, schema, 2, Map.of("amount", "9999999999999999.123456789")).level()).isEqualTo(MEDIUM);
    }

    @Test
    void absenceNoMatchAndExplicitLowHaveDifferentMeanings() {
        var policy = new ApprovalRiskPolicy(List.of(rule("zero", LOW, "amount == 0")));
        assertThat(SubmissionRisk.unassessed().level()).isEqualTo(UNASSESSED);
        assertThat(policy.assess(definitionId, 1, schema, 2, Map.of()).level()).isEqualTo(UNMATCHED);
        assertThat(policy.assess(definitionId, 1, schema, 2, Map.of("amount", "1")).level()).isEqualTo(UNMATCHED);
        assertThat(policy.assess(definitionId, 1, schema, 2, Map.of("amount", "0")).level()).isEqualTo(LOW);
    }

    @Test
    void rejectsEveryRestrictedReferenceEvenBehindShortCircuitAndNegation() {
        for (var restricted : List.of(field("secret", FormSchema.FieldType.TEXT, true, null),
                field("secret", FormSchema.FieldType.TEXT, false, Map.of("review", FieldVisibility.MASKED)),
                field("secret", FormSchema.FieldType.TEXT, false, Map.of("review", FieldVisibility.HIDDEN)))) {
            var fields = new FormSchema(1, List.of(schema.fields().get(0), restricted));
            for (String condition : List.of("amount EXISTS OR secret EXISTS", "amount < 0 AND !secret EXISTS")) {
                assertThat(new ApprovalRiskPolicy(List.of(rule("private", HIGH, condition))).validate(fields, 2))
                        .containsExactly("RISK_FIELD_RESTRICTED:risk:private");
            }
        }
    }

    @Test
    void requiresSchemaAndChecksUnknownFieldsTypesAndSyntax() {
        assertThat(new ApprovalRiskPolicy(List.of(rule("known", LOW, "amount == 1"))).validate(null, 2))
                .containsExactly("RISK_REQUIRES_FORM_SCHEMA");
        for (String condition : List.of("unknown EXISTS", "memo > 1", "amount IN [\"1\"]", "bean.run()")) {
            assertThat(new ApprovalRiskPolicy(List.of(rule("bad", HIGH, condition))).validate(schema, 2)).hasSize(1);
        }
        var readonly = new FormSchema(1, List.of(field("memo", FormSchema.FieldType.TEXT, false,
                Map.of("review", FieldVisibility.READ_ONLY))));
        assertThat(new ApprovalRiskPolicy(List.of(rule("known", LOW, "memo EXISTS"))).validate(readonly, 2)).isEmpty();
    }

    @Test
    void boundsPolicyAndRejectsImplicitLevelsOrDuplicateIdentifiers() {
        assertThatThrownBy(() -> new ApprovalRiskPolicy(List.of())).isInstanceOf(DomainException.class);
        var one = rule("same", LOW, "amount EXISTS");
        assertThatThrownBy(() -> new ApprovalRiskPolicy(List.of(one, one))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ApprovalRiskPolicy(IntStream.range(0, 11)
                .mapToObj(i -> rule("rule" + i, LOW, "amount EXISTS")).toList())).isInstanceOf(DomainException.class);
        for (var level : List.of(UNASSESSED, UNMATCHED)) {
            assertThatThrownBy(() -> rule("invalid", level, "amount EXISTS")).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> rule("empty", LOW, " ")).isInstanceOf(DomainException.class);
    }

    @Test
    void upgradesRiskConditionsWithoutReinterpretingLegacyLiteral() {
        var legacy = new DefinitionModels.Graph(List.of(), List.of(), 1,
                new ApprovalRiskPolicy(List.of(rule("text", HIGH, "memo == a && b"))));
        var upgraded = new ConditionLanguageUpgrade().upgrade(legacy);
        for (String memo : List.of("a && b", "a", "b")) {
            assertThat(upgraded.riskPolicy().assess(definitionId, 2, schema, 2, Map.of("memo", memo)))
                    .isEqualTo(legacy.riskPolicy().assess(definitionId, 2, schema, 1, Map.of("memo", memo)));
        }
        assertThat(legacy.conditionLanguageVersion()).isEqualTo(1);
        assertThat(new ConditionLanguageUpgrade().upgrade(upgraded)).isEqualTo(upgraded);
    }

    @Test
    void rejectsInventedRiskSourcesAndInconsistentSummary() {
        var high = new SubmissionRisk.Match("large", "大额复核", HIGH);
        assertThatThrownBy(() -> new SubmissionRisk(LOW, definitionId, 1, List.of(high))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubmissionRisk(HIGH, null, 1, List.of(high))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubmissionRisk(UNASSESSED, definitionId, 1, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SubmissionRisk(HIGH, definitionId, 1, List.of(high, high))).isInstanceOf(DomainException.class);
    }

    private ApprovalRiskPolicy.Rule rule(String id, SubmissionRisk.Level level, String condition) {
        return new ApprovalRiskPolicy.Rule(id, "公开规则 " + id, level, condition);
    }

    private FormSchema.Field field(String key, FormSchema.FieldType type, boolean sensitive,
                                   Map<String, FieldVisibility> access) {
        return new FormSchema.Field(key, key, type, false, null, null, null, null, null, null, null, sensitive, access);
    }
}
