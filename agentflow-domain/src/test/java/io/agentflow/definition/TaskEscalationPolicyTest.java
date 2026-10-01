package io.agentflow.definition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 升级必须依附完整期限和明确收件规则，非法配置不能被静默忽略。 @author owlzhangfq@gmail.com */
class TaskEscalationPolicyTest {
    @Test
    void rejectsEscalationWithoutADeadlineOrOnANonHumanNode() {
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK,
                Map.of("assigneeRule", "user:manager", "escalationWorkingMinutes", "30", "escalationRecipientRule", "user:finance"))))
                .contains("ESCALATION_REQUIRES_DEADLINE:review");
        assertThat(new DefinitionValidator().validate(graph(NodeType.COPY, properties())))
                .contains("ESCALATION_REQUIRES_USER_TASK:review");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "1.5", " 30", "01", "527041", "9223372036854775808"})
    void rejectsInvalidDelayInsteadOfPublishingAnInertRule(String minutes) {
        var values = properties(); values.put("escalationWorkingMinutes", minutes);
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK, values))).contains("ESCALATION_RULE_INVALID:review");
    }

    @Test
    void rejectsPartialAndExpressionRecipients() {
        var values = properties(); values.remove("escalationRecipientRule");
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK, values))).contains("ESCALATION_RULE_INVALID:review");
        values.put("escalationRecipientRule", "${recipients}");
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK, values))).contains("ESCALATION_RULE_INVALID:review");
    }

    @Test
    void acceptsExplicitRuleAndPreservesLegacyDeadlinesWithoutEscalation() {
        var values = properties();
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK, values))).isEmpty();
        values.remove("escalationWorkingMinutes"); values.remove("escalationRecipientRule");
        assertThat(new DefinitionValidator().validate(graph(NodeType.USER_TASK, values))).isEmpty();
    }

    private Map<String, String> properties() {
        return new HashMap<>(Map.of("assigneeRule", "user:manager", "recipientRule", "user:finance",
                "deadlineCalendarId", UUID.randomUUID().toString(), "deadlineCalendarRevision", "1", "deadlineWorkingMinutes", "60",
                "escalationWorkingMinutes", "30", "escalationRecipientRule", "user:finance"));
    }
    private Graph graph(NodeType type, Map<String, String> properties) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "审核", type, properties),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("first", "start", "review", ""), new Edge("last", "review", "end", "")));
    }
}
