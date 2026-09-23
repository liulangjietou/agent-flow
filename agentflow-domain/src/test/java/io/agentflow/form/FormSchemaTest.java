package io.agentflow.form;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.definition.ConditionParser;
import io.agentflow.definition.DefinitionModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.form.FormSchema.FieldType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验表单数据、不可变版本和有类型条件的领域边界。
 * @author owlzhangfq@gmail.com
 */
class FormSchemaTest {
    @Test
    void supportsSixTypesAndPreservesExactNumbersAndFalse() {
        FormSchema schema = allTypes();
        Map<String, Object> values = Map.of("reason", "文字", "note", "多行\n说明", "amount", "0009007199254740993.00",
                "date", "2028-02-29", "choice", "01", "confirmed", false);
        schema.validateSubmission(values);
        Application application = application(schema, values);
        application.submit(1);
        assertThat(application.payload()).containsEntry("amount", "0009007199254740993.00").containsEntry("confirmed", false);
    }

    @Test
    void incompleteDraftIsAllowedButFailedSubmitDoesNotMutateAggregate() {
        Application application = application(allTypes(), Map.of("reason", "  ", "confirmed", false));
        assertThatThrownBy(() -> application.submit(1)).isInstanceOfSatisfying(FormValidationException.class,
                error -> assertThat(error.fieldErrors()).containsEntry("reason", "REQUIRED").containsEntry("amount", "REQUIRED")
                        .doesNotContainKey("confirmed"));
        assertThat(application.status()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(application.version()).isEqualTo(1);
        assertThat(application.roundNo()).isEqualTo(1);
    }

    @Test
    void invalidRevisionChangesNeitherTitleNorVersionNorPayload() {
        Application application = application(new FormSchema(1, List.of(field("amount", NUMBER, true))), Map.of("amount", "2"));
        assertThatThrownBy(() -> application.revise(1, "不能提前写入", Map.of("amount", 2.0)))
                .isInstanceOfSatisfying(FormValidationException.class, error -> assertThat(error.fieldErrors()).containsEntry("amount", "INVALID_TYPE"));
        assertThat(application.title()).isEqualTo("原标题");
        assertThat(application.version()).isEqualTo(1);
        assertThat(application.payload()).containsEntry("amount", "2");
    }

    @Test
    void returnsOnlyFieldErrorCodesAndAllowsOptionalExplicitNull() {
        FormSchema schema = new FormSchema(1, List.of(field("note", TEXT, false)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", null);
        schema.validateSubmission(payload);
        payload.put("injected", Map.of("secret", "sensitive-value"));
        assertThatThrownBy(() -> schema.validateDraft(payload)).isInstanceOfSatisfying(FormValidationException.class,
                error -> { assertThat(error.fieldErrors()).isEqualTo(Map.of("injected", "UNKNOWN_FIELD"));
                    assertThat(error.getMessage()).doesNotContain("sensitive-value"); });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e3", "+1", " 1", "1 ", " ", ".5", "1.", "NaN", "1.0000000000000000001", "123456789012345678901234567890123456789"})
    void rejectsInvalidOrUnrepresentableDecimals(String value) {
        FormSchema schema = new FormSchema(1, List.of(field("amount", NUMBER, false)));
        assertThatThrownBy(() -> schema.validateDraft(Map.of("amount", value))).isInstanceOfSatisfying(FormValidationException.class,
                error -> assertThat(error.fieldErrors()).containsEntry("amount", "INVALID_NUMBER"));
    }

    @Test
    void acceptsDecimalBoundariesAndRejectsOversizedLeadingZeroStrings() {
        FormSchema schema = new FormSchema(1, List.of(field("amount", NUMBER, true)));
        schema.validateSubmission(Map.of("amount", "12345678901234567890.123456789012345678"));
        schema.validateSubmission(Map.of("amount", "-0.00"));
        schema.validateSubmission(Map.of("amount", "0".repeat(80)));
        assertThatThrownBy(() -> schema.validateDraft(Map.of("amount", "0".repeat(81)))).isInstanceOf(FormValidationException.class);
    }

    @Test
    void validatesBoundsTextLengthsRealDatesAndOptions() {
        FormSchema schema = new FormSchema(1, List.of(
                new FormSchema.Field("amount", "金额", NUMBER, true, null, null, "-2.01", "8.00", null),
                new FormSchema.Field("reason", "理由", TEXT, true, null, 2, null, null, null), field("date", DATE, true),
                new FormSchema.Field("choice", "选择", SELECT, true, null, null, null, null, List.of(new FormSchema.Option("01", "选项")))));
        assertThatThrownBy(() -> schema.validateSubmission(Map.of("amount", "-2.02", "reason", "abc", "date", "2026-02-29", "choice", "1")))
                .isInstanceOfSatisfying(FormValidationException.class, error -> assertThat(error.fieldErrors())
                        .containsEntry("amount", "BELOW_MINIMUM").containsEntry("reason", "TOO_LONG")
                        .containsEntry("date", "INVALID_DATE").containsEntry("choice", "INVALID_OPTION"));
        assertThatThrownBy(() -> schema.validateDraft(Map.of("amount", "8.01"))).isInstanceOfSatisfying(FormValidationException.class,
                error -> assertThat(error.fieldErrors()).containsEntry("amount", "ABOVE_MAXIMUM"));
        assertThatThrownBy(() -> new FormSchema(1, List.of(field("reason", TEXT, false))).validateDraft(Map.of("reason", "x".repeat(10001))))
                .isInstanceOf(FormValidationException.class);
    }

    @Test
    void onlyTextWhitespaceCountsAsEmptyAndDateRequiresExactIsoShape() {
        for (String date : List.of("2026-2-03", "2026-02-30", "0000-01-01", "2026-09-22T00:00:00", "  ")) {
            assertThatThrownBy(() -> new FormSchema(1, List.of(field("date", DATE, false))).validateDraft(Map.of("date", date)))
                    .isInstanceOf(FormValidationException.class);
        }
        FormSchema schema = new FormSchema(1, List.of(field("reason", TEXT, false), field("amount", NUMBER, false)));
        schema.validateSubmission(Map.of("reason", "  ", "amount", ""));
        assertThatThrownBy(() -> schema.validateDraft(Map.of("amount", "  "))).isInstanceOf(FormValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"constructor", "prototype", "tenantId", "applicationId", "businessNo", "roundNo", "formData", "formFieldTypes", "lastAction", "a.b", "_hidden", "1field"})
    void rejectsUnsafeOrSystemFieldKeys(String key) {
        assertThatThrownBy(() -> field(key, TEXT, false)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_FORM_SCHEMA"));
    }

    @Test
    void validatesSchemaCollectionsAndFreezesOriginalLists() {
        List<FormSchema.Option> options = new ArrayList<>(List.of(new FormSchema.Option("a", "原选项")));
        List<FormSchema.Field> fields = new ArrayList<>(List.of(new FormSchema.Field("choice", "选择", SELECT, false, null, null, null, null, options)));
        FormSchema schema = new FormSchema(1, fields);
        options.clear(); fields.clear();
        assertThat(schema.fields()).hasSize(1);
        assertThat(schema.fields().get(0).options()).hasSize(1);
        assertThatThrownBy(() -> schema.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> schema.fields().get(0).options().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new FormSchema(3, List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new FormSchema(1, List.of(field("a", TEXT, false), field("a", NUMBER, false)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new FormSchema(1, java.util.stream.IntStream.range(0, 51).mapToObj(i -> field("f" + i, TEXT, false)).toList()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new FormSchema.Field("s", "选项", SELECT, false, null, null, null, null,
                List.of(new FormSchema.Option("a", "甲"), new FormSchema.Option("a", "乙")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new FormSchema.Field("n", "金额", NUMBER, false, null, null, "9", "1", null)).isInstanceOf(DomainException.class);
    }

    @Test
    void typedConditionsKeepTextAndEnumDistinctWhileNumbersRemainNumeric() {
        FormSchema schema = allTypes();
        var context = new DefinitionModels.EvaluationContext(Map.of("choice", "01", "reason", "01", "amount", "00001.00", "confirmed", false,
                "date", "2026-09-22"), schema.fieldTypes());
        ConditionParser parser = new ConditionParser();
        assertThat(parser.parse("choice == '1'").evaluate(context)).isFalse();
        assertThat(parser.parse("reason == '1'").evaluate(context)).isFalse();
        assertThat(parser.parse("amount == 1").evaluate(context)).isTrue();
        assertThat(parser.parse("confirmed == false").evaluate(context)).isTrue();
        assertThat(parser.parse("date > 2026-09-21").evaluate(context)).isTrue();
        assertThat(parser.parse("choice == '1'").evaluate(new DefinitionModels.EvaluationContext(Map.of("choice", "01")))).isTrue();
        for (String condition : List.of("undeclared == 1", "reason > 'a'", "confirmed == 0", "amount == 1e3", "date == 2026-02-30", "choice == 'other'")) {
            assertThatThrownBy(() -> schema.validateCondition(parser.parse(condition))).isInstanceOf(DomainException.class);
        }
        schema.validateCondition(parser.parse("amount > 99999999999 AND confirmed == false"));
    }

    @Test
    void existsUsesTypedEmptySemanticsAndFalseIsPresent() {
        FormSchema schema = allTypes();
        var context = new DefinitionModels.EvaluationContext(Map.of("reason", "  ", "amount", "", "confirmed", false), schema.fieldTypes());
        ConditionParser parser = new ConditionParser();
        assertThat(parser.parse("reason NOT_EXISTS").evaluate(context)).isTrue();
        assertThat(parser.parse("amount NOT_EXISTS").evaluate(context)).isTrue();
        assertThat(parser.parse("confirmed EXISTS").evaluate(context)).isTrue();
    }

    @Test
    void oldSubmissionRetainsSchemaAndPayloadAfterRevision() {
        FormSchema schema = new FormSchema(1, List.of(field("reason", TEXT, true)));
        Application application = application(schema, Map.of("reason", "原始理由"));
        application.submit(1);
        SubmissionRound original = SubmissionRound.submitted(application, "original", "alice", Instant.now());
        application.returnToApplicant(2);
        application.revise(3, "补正", Map.of("reason", "补正理由"));
        application.submit(4);
        assertThat(original.formSchema()).isSameAs(schema);
        assertThat(original.payload()).containsEntry("reason", "原始理由");
        assertThat(application.formSchema()).isSameAs(schema);
        assertThat(application.roundNo()).isEqualTo(2);
    }

    private FormSchema allTypes() {
        return new FormSchema(1, List.of(field("reason", TEXT, true), field("note", TEXTAREA, false), field("amount", NUMBER, true),
                field("date", DATE, true), new FormSchema.Field("choice", "选项", SELECT, true, null, null, null, null,
                List.of(new FormSchema.Option("01", "第一项"), new FormSchema.Option("1", "第二项"))), field("confirmed", BOOLEAN, true)));
    }

    private FormSchema.Field field(String key, FormSchema.FieldType type, boolean required) {
        return new FormSchema.Field(key, "字段", type, required, null, null, null, null, null);
    }

    private Application application(FormSchema schema, Map<String, Object> payload) {
        return Application.draft(UUID.randomUUID(), "demo", "FORM-1", "leave", 1, "alice", "原标题", payload, schema);
    }
}
