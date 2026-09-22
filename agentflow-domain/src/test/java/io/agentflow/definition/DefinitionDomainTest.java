package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionDomainTest {
    private final ConditionParser parser = new ConditionParser();
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void parsesAllowlistedConditionAndEvaluatesIt() {
        ConditionAst condition = parser.parse("amount >= 1000 AND department == 'finance'");

        assertThat(condition.evaluate(new EvaluationContext(Map.of("amount", 1500, "department", "finance")))).isTrue();
        assertThat(condition.evaluate(new EvaluationContext(Map.of("amount", 800, "department", "finance")))).isFalse();
    }

    @Test
    void rejectsExpressionInjectionSyntax() {
        assertThatThrownBy(() -> parser.parse("amount == ${evil}"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("allowlisted");
    }

    @Test
    void reportsMissingAssigneeAndUnreachableNodes() {
        Graph graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of()),
                new Node("orphan", "孤立", NodeType.USER_TASK, Map.of()),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(
                new Edge("e1", "start", "approve", ""),
                new Edge("e2", "approve", "end", "")
        ));

        assertThat(validator.validate(graph)).contains("ASSIGNEE_RULE_REQUIRED:approve", "NODE_UNREACHABLE:orphan");
    }

    @Test
    void publishedDefinitionCannotBeUpdated() {
        DefinitionModels.DefinitionDraft draft = DefinitionModels.DefinitionDraft.create(
                UUID.randomUUID(), "tenant-a", "demo", "演示", validGraph());
        draft.publish(0);

        assertThatThrownBy(() -> draft.update("修改", validGraph(), draft.revision()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("cannot be changed");
    }

    private Graph validGraph() {
        return new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(new Edge("e1", "start", "approve", ""), new Edge("e2", "approve", "end", "")));
    }
}
