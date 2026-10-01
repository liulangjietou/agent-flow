package io.agentflow.definition;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时等待不是审批决定，配置不能注入表达式或产生无人工决策的完成路径。
 * @author owlzhangfq@gmail.com
 */
class TimerDefinitionTest {
    private final DefinitionValidator validator = new DefinitionValidator();

    @Test
    void publishesAnExplicitBoundedWaitBeforeOrAfterHumanApproval() {
        for (String seconds : List.of("1", "60", "31536000")) {
            assertThat(validator.validate(linear(seconds, true))).isEmpty();
            assertThat(validator.validate(linear(seconds, false))).isEmpty();
        }
    }

    @Test
    void rejectsMissingFractionalUnboundedAndExpressionDurations() {
        for (String seconds : List.of("", "0", "-1", "01", "1.5", " 60", "31536001", "999999999999", "${evil}")) {
            assertThat(validator.validate(linear(seconds, true))).contains("TIMER_DELAY_INVALID:wait");
        }
        var graph = linear("60", true);
        var nodes = new ArrayList<>(graph.nodes());
        nodes.replaceAll(n -> n.id().equals("wait") ? new Node(n.id(), n.name(), n.type(), Map.of()) : n);
        assertThat(validator.validate(new Graph(nodes, graph.edges()))).contains("TIMER_DELAY_REQUIRED:wait");
    }

    @Test
    void durationBelongsOnlyToTheWaitingNode() {
        var graph = linear("60", true);
        var nodes = new ArrayList<>(graph.nodes());
        nodes.replaceAll(n -> n.id().equals("review") ? new Node(n.id(), n.name(), n.type(),
                Map.of("assigneeRule", "user:finance", "timerDelaySeconds", "60")) : n);
        assertThat(validator.validate(new Graph(nodes, graph.edges()))).contains("TIMER_REQUIRES_WAIT_NODE:review");
    }

    @Test
    void waitAloneOrAConditionalBypassCannotApproveAnApplication() {
        var graph = linear("60", true);
        var nodes = graph.nodes().stream().filter(n -> !n.id().equals("review")).toList();
        assertThat(validator.validate(new Graph(nodes, edges("start>wait", "wait>end"))))
                .contains("TIMER_REQUIRES_APPROVAL_PATH:end");
        var branch = new ArrayList<>(graph.nodes());
        branch.add(new Node("choose", "条件", NodeType.EXCLUSIVE_GATEWAY, Map.of()));
        var paths = new ArrayList<>(edges("start>wait", "wait>choose", "review>end"));
        paths.add(new Edge("yes", "choose", "review", "amount > 0"));
        paths.add(new Edge("no", "choose", "end", "", true));
        assertThat(validator.validate(new Graph(branch, paths))).contains("TIMER_REQUIRES_APPROVAL_PATH:end");
    }

    @Test
    void aParallelJoinStillRequiresTheRealHumanBranchToComplete() {
        var nodes = new ArrayList<>(linear("60", true).nodes());
        nodes.add(new Node("fork", "同时进行", NodeType.PARALLEL_GATEWAY, Map.of()));
        nodes.add(new Node("join", "等待两边", NodeType.PARALLEL_GATEWAY, Map.of()));
        assertThat(validator.validate(new Graph(nodes,
                edges("start>fork", "fork>wait", "fork>review", "wait>join", "review>join", "join>end")))).isEmpty();
    }

    @Test
    void waitNamesAndOutgoingFlowsStayRestricted() {
        var graph = linear("60", true);
        var nodes = new ArrayList<>(graph.nodes());
        nodes.replaceAll(n -> n.id().equals("wait") ? new Node(n.id(), "${evil}", n.type(), n.properties()) : n);
        var paths = new ArrayList<>(graph.edges()); paths.add(new Edge("bypass", "wait", "end", ""));
        assertThat(validator.validate(new Graph(nodes, paths)))
                .contains("TASK_NAME_EXPRESSION_FORBIDDEN:wait", "SINGLE_OUTGOING_REQUIRED:wait");
    }

    private Graph linear(String seconds, boolean before) {
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("wait", "等待约定时间", NodeType.valueOf("TIMER_WAIT"), Map.of("timerDelaySeconds", seconds)),
                new Node("review", "实际审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:finance")),
                new Node("end", "结束", NodeType.END, Map.of())),
                before ? edges("start>wait", "wait>review", "review>end") : edges("start>review", "review>wait", "wait>end"));
    }

    private List<Edge> edges(String... pairs) {
        var result = new ArrayList<Edge>();
        for (String pair : pairs) {
            String[] ends = pair.split(">"); result.add(new Edge("edge" + result.size(), ends[0], ends[1], ""));
        }
        return result;
    }
}
