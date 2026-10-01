package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.attachment.AttachmentReferences;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.form.FormSchema.FieldType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子流程输入按固定契约投影，覆盖权限降级、结构化数据、附件归属和原始值冻结边界。
 * @author owlzhangfq@gmail.com
 */
class SubprocessInputsTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "01", "latest", "-1", "1.5", "2147483648", "99999999999999999999", "${version}"})
    void refusesNonCanonicalOrUnsupportedEngineVersions(String version) {
        failure(() -> SubprocessPolicy.fromProperties(Map.of("subprocessKey", "review", "subprocessVersion", version)), "SUBPROCESS_REFERENCE_INVALID");
    }

    @Test
    void preservesExactReferenceAndNeverCreatesImplicitMappings() {
        var input = new LinkedHashMap<>(Map.of("total", "amount"));
        var policy = new SubprocessPolicy("review", 1, input); input.clear();
        assertThat(SubprocessPolicy.fromProperties(policy.properties())).isEqualTo(policy);
        assertThat(policy.version()).isEqualTo(1);
        assertThat(policy.inputs()).containsExactlyEntriesOf(Map.of("total", "amount"));
        assertThatThrownBy(() -> policy.inputs().put("secret", "secret")).isInstanceOf(UnsupportedOperationException.class);
        failure(() -> SubprocessPolicy.fromProperties(Map.of("subprocessKey", "review")), "SUBPROCESS_REFERENCE_REQUIRED");
        failure(() -> new SubprocessPolicy("${bean}", 1, Map.of()), "SUBPROCESS_REFERENCE_INVALID");
        failure(() -> new SubprocessPolicy(" review", 1, Map.of()), "SUBPROCESS_REFERENCE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"applicationId", "tenantId", "formData", "prototype", "line.amount", "${secret}", " amount"})
    void refusesEngineVariableNamesAndExpressionsOnEitherSide(String field) {
        failure(() -> new SubprocessPolicy("review", 1, Map.of("amount", field)), "SUBPROCESS_INPUT_MAPPING_INVALID");
        failure(() -> new SubprocessPolicy("review", 1, Map.of(field, "amount")), "SUBPROCESS_INPUT_MAPPING_INVALID");
    }

    @Test
    void mapsOnlyExplicitFieldsAndPreservesFalseZeroAndNull() {
        var source = schema(field("amount", NUMBER, true), field("flag", BOOLEAN, true), field("note", TEXT, false), field("extra", TEXT, false));
        var target = schema(field("total", NUMBER, true), field("allowed", BOOLEAN, true), field("description", TEXT, false), field("extra", TEXT, false));
        var mapping = bind(Map.of("total", "amount", "allowed", "flag", "description", "note"), source, target);
        var values = new LinkedHashMap<String, Object>(); values.put("amount", "0.00"); values.put("flag", false); values.put("note", null); values.put("extra", "不能隐式复制");
        var projected = mapping.project(values); values.put("amount", "99");
        assertThat(projected.values()).containsEntry("total", "0.00").containsEntry("allowed", false).containsEntry("description", null).doesNotContainKey("extra");
        assertThat(projected.attachments()).isEmpty();
        assertThatThrownBy(() -> projected.values().put("total", "99")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesDeclaredFieldsTypesAndEveryRequiredTarget() {
        var source = schema(field("amount", NUMBER, true)); var target = schema(field("total", NUMBER, true));
        failure(() -> bind(Map.of(), source, target), "SUBPROCESS_REQUIRED_INPUT_MISSING");
        failure(() -> bind(Map.of("total", "missing"), source, target), "SUBPROCESS_INPUT_FIELD_UNKNOWN");
        failure(() -> bind(Map.of("missing", "amount"), source, target), "SUBPROCESS_INPUT_FIELD_UNKNOWN");
        failure(() -> bind(Map.of("total", "amount"), source, schema(field("total", TEXT, true))), "SUBPROCESS_INPUT_TYPE_MISMATCH");
        assertThatThrownBy(() -> bind(Map.of("total", "amount"), source, target).project(Map.of())).isInstanceOf(io.agentflow.form.FormValidationException.class);
    }

    @Test
    void appliesTargetBoundsAndEnumsAtActivationWithoutChangingTheValue() {
        var source = schema(field("amount", NUMBER, true), select("kind", "normal", "special"));
        var target = schema(new FormSchema.Field("total", "合计", NUMBER, true, null, null, "0", "100", null), select("type", "normal"));
        var mapping = bind(Map.of("total", "amount", "type", "kind"), source, target);
        assertThat(mapping.project(Map.of("amount", "99.990", "kind", "normal")).values()).containsEntry("total", "99.990");
        assertThatThrownBy(() -> mapping.project(Map.of("amount", "101", "kind", "normal"))).isInstanceOf(io.agentflow.form.FormValidationException.class);
        assertThatThrownBy(() -> mapping.project(Map.of("amount", "1", "kind", "special"))).isInstanceOf(io.agentflow.form.FormValidationException.class);
    }

    @Test
    void hiddenAndMaskedFieldsCannotBeForwardedAndSensitivityCannotBeRenamedAway() {
        var child = schema(sensitive("childSecret", Map.of("review", FieldVisibility.READ_ONLY)));
        for (var visibility : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            failure(() -> bind(Map.of("childSecret", "secret"), schema(sensitive("secret", Map.of("call", visibility))), child), "SUBPROCESS_INPUT_NOT_READABLE");
        }
        var readable = schema(sensitive("secret", Map.of("call", FieldVisibility.READ_ONLY)));
        failure(() -> bind(Map.of("plain", "secret"), readable, schema(field("plain", TEXT, true))), "SUBPROCESS_INPUT_SENSITIVITY_LOSS");
        assertThat(bind(Map.of("childSecret", "secret"), readable, child).project(Map.of("secret", "受控值")).values()).containsEntry("childSecret", "受控值");
        assertThat(child.fields().get(0).visibility(java.util.Set.of())).isEqualTo(FieldVisibility.MASKED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void freezesDetailRowsAndDropsUndeclaredSourceColumns() {
        var source = schema(table("lines", field("amount", NUMBER, true), sensitive("secret", Map.of("call", FieldVisibility.HIDDEN))));
        var target = schema(table("details", field("amount", NUMBER, true), field("note", TEXT, false)));
        var row = new LinkedHashMap<String, Object>(Map.of("amount", "1", "secret", "不能传入"));
        var rows = new ArrayList<>(List.of(row));
        var projected = bind(Map.of("details", "lines"), source, target).project(Map.of("lines", rows));
        row.put("amount", "2"); rows.clear();
        var detail = (List<Map<String, Object>>) projected.values().get("details");
        assertThat(detail).containsExactly(Map.of("amount", "1"));
        assertThatThrownBy(() -> detail.get(0).put("amount", "3")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.clear()).isInstanceOf(UnsupportedOperationException.class);
        failure(() -> bind(Map.of("details", "lines"), source, schema(table("details", field("absent", TEXT, true)))), "SUBPROCESS_REQUIRED_INPUT_MISSING");
    }

    @Test
    void attachmentTransferPlanPreservesSourceAndTargetFieldPaths() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var source = schema(field("file", ATTACHMENT, true), table("lines", field("proof", ATTACHMENT, true)));
        var target = schema(field("document", ATTACHMENT, true), table("details", field("proof", ATTACHMENT, true)));
        var ids = new ArrayList<>(List.of(a.toString()));
        var projected = bind(Map.of("document", "file", "details", "lines"), source, target)
                .project(Map.of("file", ids, "lines", List.of(Map.of("proof", List.of(b.toString())))));
        ids.clear();
        assertThat(projected.values().get("document")).isEqualTo(List.of(a.toString()));
        assertThat(projected.attachments()).containsExactlyInAnyOrder(new SubprocessInputs.AttachmentInput("file", "document", a),
                new SubprocessInputs.AttachmentInput("lines.proof", "details.proof", b));
        failure(() -> bind(Map.of("document", "file"), source, schema(field("document", ATTACHMENT, true)))
                .project(Map.of("file", List.of("not-an-id"))), "SUBPROCESS_INPUT_VALUE_INVALID");
    }

    @Test
    void detailTransferPlanUsesThePersistedAttachmentIdentityAcrossRepeatedRows() {
        var id = UUID.randomUUID();
        var source = schema(table("lines", field("proof", ATTACHMENT, true)));
        var target = schema(table("details", field("proof", ATTACHMENT, true)));
        var rows = List.of(Map.of("proof", List.of(id.toString())), Map.of("proof", List.of(id.toString())));
        var payload = Map.<String, Object>of("lines", rows);
        var projected = bind(Map.of("details", "lines"), source, target).project(payload);
        var sourceReferences = AttachmentReferences.collect(source, payload);
        var targetReferences = AttachmentReferences.collect(target, projected.values());
        assertThat(projected.attachments()).hasSize(1).allSatisfy(input -> {
            assertThat(sourceReferences).contains(new AttachmentReferences.Reference(input.sourceFieldPath(), input.sourceAttachmentId()));
            assertThat(targetReferences).contains(new AttachmentReferences.Reference(input.targetFieldPath(), input.sourceAttachmentId()));
        });
        assertThat(projected.values().get("details")).isEqualTo(rows);
    }

    private SubprocessInputs bind(Map<String, String> inputs, FormSchema source, FormSchema target) {
        return SubprocessInputs.bind(new SubprocessPolicy("review", 1, inputs), "call", source, target);
    }
    private FormSchema schema(FormSchema.Field... fields) { return new FormSchema(2, List.of(fields)); }
    private FormSchema.Field field(String key, FormSchema.FieldType type, boolean required) { return new FormSchema.Field(key, key, type, required, null, null, null, null, null); }
    private FormSchema.Field sensitive(String key, Map<String, FieldVisibility> access) {
        return new FormSchema.Field(key, key, TEXT, true, null, null, null, null, null, null, null, true, access);
    }
    private FormSchema.Field table(String key, FormSchema.Field... columns) {
        return new FormSchema.Field(key, key, TABLE, true, null, null, null, null, null, List.of(columns), null);
    }
    private FormSchema.Field select(String key, String... values) {
        return new FormSchema.Field(key, key, SELECT, true, null, null, null, null, java.util.Arrays.stream(values).map(v -> new FormSchema.Option(v, v)).toList());
    }
    private void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
