package io.agentflow.form;

import io.agentflow.common.DomainException;
import io.agentflow.definition.ConditionParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static io.agentflow.form.FormSchema.FieldType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重复明细只接受一层记录，按行列定位错误并保留原始值。
 * @author owlzhangfq@gmail.com
 */
class DetailTableSchemaTest {
    private final FormSchema.Field quantity = new FormSchema.Field("quantity", "数量", NUMBER, true, null, null, "0", null, null);
    private final FormSchema.Field confirmed = new FormSchema.Field("confirmed", "确认", BOOLEAN, false, null, null, null, null, null);

    @Test
    void draftsAllowMissingCellsButSubmitReportsExactRowsWithoutCoercion() {
        var schema = schema(table("items", List.of(quantity, confirmed), 5));
        schema.validateDraft(Map.of("items", List.of(Map.of())));
        schema.validateSubmission(Map.of("items", List.of(Map.of("quantity", "0009007199254740993.00", "confirmed", false))));
        assertErrors(schema, List.of(Map.of(), Map.of("quantity", 2, "confirmed", "false"), Map.of("quantity", "-1", "extra", "secret")),
                Map.of("items[0].quantity", "REQUIRED", "items[1].quantity", "INVALID_TYPE", "items[1].confirmed", "INVALID_TYPE",
                        "items[2].quantity", "BELOW_MINIMUM", "items[2].extra", "UNKNOWN_FIELD"));
    }

    @Test
    void enforcesRequiredRowsRowObjectsAndTechnicalLimits() {
        var schema = schema(table("items", List.of(quantity), 2));
        assertErrors(schema, List.of(), Map.of("items", "REQUIRED"));
        assertErrors(schema, "", Map.of("items", "INVALID_TYPE"));
        assertErrors(schema, List.of("wrong", List.of()), Map.of("items[0]", "INVALID_TYPE", "items[1]", "INVALID_TYPE"));
        assertErrors(schema, List.of(Map.of(), Map.of(), Map.of()), Map.of("items", "TOO_MANY_ROWS"));
        var columns = IntStream.range(0, 20).mapToObj(i -> new FormSchema.Field("c" + i, "列", TEXT, false, null, null, null, null, null)).toList();
        var budget = new FormSchema(2, List.of(table("items", columns, 100), table("more", List.of(quantity), 100)));
        var rows = IntStream.range(0, 100).mapToObj(i -> Map.of("c0", "值")).toList();
        schema(table("items", columns, 100)).validateSubmission(Map.of("items", rows));
        assertThatThrownBy(() -> budget.validateSubmission(Map.of("items", rows, "more", List.of(Map.of("quantity", "1")))))
                .isInstanceOfSatisfying(FormValidationException.class, error -> assertThat(error.fieldErrors()).isEqualTo(Map.of("more", "TOO_MANY_CELLS")));
    }

    @Test
    void configurationIsFlatVersionedBoundedAndImmutable() {
        var columns = new ArrayList<>(List.of(quantity));
        var table = table("items", columns, null); columns.clear();
        assertThat(table.columns()).containsExactly(quantity);
        assertThat(table.rowLimit()).isEqualTo(50);
        assertThatThrownBy(() -> table.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new FormSchema(1, List.of(table))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> table("items", List.of(table), 5)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> table("items", List.of(quantity, quantity), 5)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> table("items", List.of(), 5)).isInstanceOf(DomainException.class);
        for (int invalid : List.of(0, 101)) assertThatThrownBy(() -> table("items", List.of(quantity), invalid)).isInstanceOf(DomainException.class);
    }

    @Test
    void presenceUsesEmptyRowsAndRejectsScalarComparisonAndColumnRouting() {
        var schema = schema(table("items", List.of(quantity), 5));
        var exists = new ConditionParser().parse("items EXISTS");
        schema.validateCondition(exists);
        assertThat(exists.evaluate(new io.agentflow.definition.DefinitionModels.EvaluationContext(Map.of("items", List.of()), schema.fieldTypes()))).isFalse();
        assertThat(exists.evaluate(new io.agentflow.definition.DefinitionModels.EvaluationContext(Map.of("items", List.of(Map.of("quantity", "1"))), schema.fieldTypes()))).isTrue();
        for (String expression : List.of("items == 'x'", "items.quantity > 0", "quantity > 0")) {
            assertThatThrownBy(() -> schema.validateCondition(new ConditionParser().parse(expression))).isInstanceOf(DomainException.class);
        }
    }

    private FormSchema.Field table(String key, List<FormSchema.Field> columns, Integer maxRows) {
        return new FormSchema.Field(key, "明细", TABLE, true, null, null, null, null, null, columns, maxRows);
    }
    private FormSchema schema(FormSchema.Field field) { return new FormSchema(2, List.of(field)); }
    private void assertErrors(FormSchema schema, Object rows, Map<String, String> expected) {
        assertThatThrownBy(() -> schema.validateSubmission(Map.of("items", rows))).isInstanceOfSatisfying(FormValidationException.class,
                error -> assertThat(error.fieldErrors()).isEqualTo(expected));
    }
}
