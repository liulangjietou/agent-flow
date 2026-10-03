package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 固定日历引用和字面量校验，旧流程无期限配置时保持原义。
 * @author owlzhangfq@gmail.com
 */
class TaskDeadlinePolicyTest {
    private static final UUID CALENDAR = UUID.fromString("e7251050-b46b-40c3-9c4c-cc5d90f85688");

    @Test
    void absenceRemainsUnconfiguredAndCompleteRuleUsesExactRevision() {
        assertThat(TaskDeadlinePolicy.fromProperties(Map.of("assigneeRule", "role:MANAGER"))).isEmpty();
        assertThat(TaskDeadlinePolicy.fromProperties(properties())).contains(new TaskDeadlinePolicy(CALENDAR, 2, 480));
    }

    @Test
    void partialRuleAndAbbreviatedUuidCannotSilentlyDisableDeadline() {
        var missing = properties(); missing.remove(TaskDeadlinePolicy.WORKING_MINUTES);
        assertThatThrownBy(() -> TaskDeadlinePolicy.fromProperties(missing)).isInstanceOf(DomainException.class);
        var abbreviated = properties(); abbreviated.put(TaskDeadlinePolicy.CALENDAR_ID, "1-1-1-1-1");
        assertThatThrownBy(() -> TaskDeadlinePolicy.fromProperties(abbreviated)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "${bean.execute()}", "latest", "9223372036854775808"})
    void revisionCannotBeDynamicOrOutOfRange(String revision) {
        var values = properties(); values.put(TaskDeadlinePolicy.CALENDAR_REVISION, revision);
        assertThatThrownBy(() -> TaskDeadlinePolicy.fromProperties(values)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("INVALID_TASK_DEADLINE");
    }

    @Test
    void durationBoundsAndWrongNodeTypeAreReportedByDefinitionValidation() {
        var tooLong = properties(); tooLong.put(TaskDeadlinePolicy.WORKING_MINUTES, "527041");
        assertThat(validate(NodeType.USER_TASK, tooLong)).contains("DEADLINE_RULE_INVALID:review");
        assertThat(validate(NodeType.END, properties())).contains("DEADLINE_REQUIRES_USER_TASK:review");
        assertThat(validate(NodeType.USER_TASK, properties())).isEmpty();
    }

    private List<String> validate(NodeType type, Map<String, String> properties) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", type, properties), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("first", "start", "review", "", false), new Edge("last", "review", "end", "", false)));
        return new DefinitionValidator().validate(graph);
    }

    private Map<String, String> properties() {
        return new HashMap<>(Map.of("assigneeRule", "role:MANAGER", TaskDeadlinePolicy.CALENDAR_ID, CALENDAR.toString(),
                TaskDeadlinePolicy.CALENDAR_REVISION, "2", TaskDeadlinePolicy.WORKING_MINUTES, "480"));
    }
}
