package io.agentflow.definition;

import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单选人只能引用显式必填单选项，不能把任意表单文本当作账号或执行表达式。
 * @author owlzhangfq@gmail.com
 */
class FormAssigneeValidationTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void acceptsExplicitOrganizationRelationsFromRequiredSelectFields() {
        for (String relation : List.of("PERSON", "DEPARTMENT_HEAD", "DEPARTMENT_MEMBERS", "POSITION_MEMBERS")) {
            assertThat(validator.validate(graph("field:responsible:" + relation), schema(true, UUID.randomUUID().toString())))
                    .as(relation).isEmpty();
        }
    }

    @Test
    void rejectsMissingOptionalOrUntypedSourcesBeforePublication() {
        var graph = graph("field:responsible:PERSON");
        assertThat(validator.validate(graph)).contains("FORM_ASSIGNEE_FIELD_REQUIRED:review");
        assertThat(validator.validate(graph, schema(false, UUID.randomUUID().toString())))
                .contains("FORM_ASSIGNEE_FIELD_REQUIRED:review");
        assertThat(validator.validate(graph, schema(true, "user:admin")))
                .contains("FORM_ASSIGNEE_OPTION_INVALID:review");
        var text = new FormSchema(1, List.of(new FormSchema.Field("responsible", "经办人员", FormSchema.FieldType.TEXT,
                true, null, null, null, null, null)));
        assertThat(validator.validate(graph, text)).contains("FORM_ASSIGNEE_FIELD_REQUIRED:review");
    }

    @Test
    void rejectsExpressionsUnknownRelationsAndCopyRecipientReuse() {
        var schema = schema(true, UUID.randomUUID().toString());
        for (String rule : List.of("field:responsible:${admin}", "field:items[0].person:PERSON", "field:responsible:ROLE")) {
            assertThat(validator.validate(graph(rule), schema)).contains("FORM_ASSIGNEE_RULE_INVALID:review");
        }
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("copy", "抄送", NodeType.COPY, Map.of("recipientRule", "field:responsible:PERSON")),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:manager")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(edge("e1", "start", "copy"), edge("e2", "copy", "review"), edge("e3", "review", "end")));
        assertThat(validator.validate(graph, schema)).contains("ASSIGNEE_RULE_INVALID:copy");
    }

    private static FormSchema schema(boolean required, String value) {
        return new FormSchema(1, List.of(new FormSchema.Field("responsible", "经办人员", FormSchema.FieldType.SELECT,
                required, null, null, null, null, List.of(new FormSchema.Option(value, "指定人员")))));
    }

    private static Graph graph(String rule) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", rule)),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(edge("e1", "start", "review"), edge("e2", "review", "end")));
    }

    private static Edge edge(String id, String source, String target) { return new Edge(id, source, target, "", false); }
}
