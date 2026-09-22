package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author owlzhangfq@gmail.com
 */
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
    void rejectsNodesThatThePublisherCannotRepresent() {
        Graph graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("service", "自动处理", NodeType.SERVICE_TASK, Map.of()),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(new Edge("e1", "start", "service", ""), new Edge("e2", "service", "end", "")));

        assertThat(validator.validate(graph)).contains("UNSUPPORTED_NODE_TYPE:service");
    }

    @Test
    void requiresAnExclusiveGatewayToHaveAtLeastTwoBranches() {
        Graph graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("gate", "分支", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(new Edge("e1", "start", "gate", ""), new Edge("e2", "gate", "end", "", true)));

        assertThat(validator.validate(graph)).contains("GATEWAY_BRANCH_REQUIRED:gate");
    }

    @Test
    void publishedDefinitionCannotBeUpdated() {
        DefinitionModels.DefinitionDraft draft = DefinitionModels.DefinitionDraft.create(
                UUID.randomUUID(), "tenant-a", "demo", "演示", validGraph());
        draft.publish(0, 1);

        assertThatThrownBy(() -> draft.update("修改", validGraph(), draft.revision()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void simulationUsesExplicitDefaultBranchWhenNoConditionMatches() {
        Graph graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("gate", "金额判断", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("default", "默认审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("conditional", "条件审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("start-gate", "start", "gate", ""),
                        new Edge("gate-default", "gate", "default", "", true),
                        new Edge("gate-conditional", "gate", "conditional", "amount > 10000"),
                        new Edge("default-end", "default", "end", ""),
                        new Edge("conditional-end", "conditional", "end", "")));

        assertThat(new DefinitionSimulator().simulate(graph, new EvaluationContext(Map.of("amount", 9000))))
                .containsExactly("start", "gate", "default", "end");
    }

    private Graph validGraph() {
        return new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(new Edge("e1", "start", "approve", ""), new Edge("e2", "approve", "end", "")));
    }
}
