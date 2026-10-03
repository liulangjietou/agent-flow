package io.agentflow.form;

import io.agentflow.common.DomainException;
import io.agentflow.definition.ConditionParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 附件表单只保存有界引用，隐藏和脱敏不能保留文件标识。
 * @author owlzhangfq@gmail.com
 */
class AttachmentFormSchemaTest {
    @Test
    void validatesImmutableReferencesAndPresenceWithoutTreatingThemAsText() {
        var field = new FormSchema.Field("proof", "证明", FormSchema.FieldType.valueOf("ATTACHMENT"), true,
                null, null, null, null, null);
        var schema = new FormSchema(1, List.of(field));
        String id = UUID.randomUUID().toString();
        schema.validateDraft(Map.of());
        schema.validateSubmission(Map.of("proof", List.of(id)));
        assertThatThrownBy(() -> schema.validateSubmission(Map.of("proof", List.of())))
                .isInstanceOf(FormValidationException.class);
        for (Object invalid : List.of(id, List.of("../../file"), List.of(id, id))) {
            assertThatThrownBy(() -> schema.validateDraft(Map.of("proof", invalid)))
                    .isInstanceOf(FormValidationException.class);
        }
        schema.validateCondition(new ConditionParser().parse("proof EXISTS"));
        assertThatThrownBy(() -> schema.validateCondition(new ConditionParser().parse("proof == \"x\"")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void restrictsAttachmentsInTablesWithoutLeakingIdsOrCount() {
        var field = new FormSchema.Field("proof", "证明", FormSchema.FieldType.valueOf("ATTACHMENT"), false,
                null, null, null, null, null, null, null, true, Map.of("review", FieldVisibility.READ_ONLY));
        var table = new FormSchema.Field("items", "明细", FormSchema.FieldType.TABLE, false,
                null, null, null, null, null, List.of(field), 10);
        var schema = new FormSchema(2, List.of(table));
        var payload = Map.<String,Object>of("items", List.of(Map.of("proof", List.of(UUID.randomUUID().toString()))));
        schema.validateSubmission(payload);
        assertThat(FormFieldProjection.forNodes(schema, payload, Set.of("review")).payload()).isEqualTo(payload);
        assertThat(FormFieldProjection.forNodes(schema, payload, Set.of()).payload())
                .isEqualTo(Map.of("items", List.of(Map.of("proof", "已脱敏"))));
    }
}
