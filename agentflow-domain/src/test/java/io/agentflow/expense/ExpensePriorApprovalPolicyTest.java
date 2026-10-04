package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 额度例外必须为共同路径上的独立人工审核，不能被普通业务、纸件或财务角色替代。
 * @author owlzhangfq@gmail.com
 */
class ExpensePriorApprovalPolicyTest {
    @Test void trueBranchMustReviewWhileFalseBranchMayContinueToFinance() {
        var graph = graph("priorRequestOverTolerance == true", "PRIOR_REQUEST_REVIEW", false);
        assertThatCode(() -> ExpensePriorApprovalPolicy.validate(graph, schema(true, FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        assertThat(ExpenseProcessPolicy.requireSubmittable(graph, schema(true, FieldVisibility.READ_ONLY), false))
                .containsEntry("prior", ExpenseProcessPolicy.Stage.PRIOR_REQUEST_REVIEW);
        assertThatCode(() -> ExpensePriorApprovalPolicy.require(graph, schema(false, FieldVisibility.READ_ONLY), false)).doesNotThrowAnyException();
        fails(() -> ExpensePriorApprovalPolicy.require(graph, schema(false, FieldVisibility.READ_ONLY), true));
    }

    @Test void explanationOrAnotherStageCannotSubstituteForTheDedicatedReview() {
        for (var stage : List.of("BUSINESS", "RECEIPT", "FINANCE_REVIEW", "FINANCE_RECHECK")) {
            fails(() -> ExpensePriorApprovalPolicy.require(graph("priorRequestOverTolerance == true", stage, false), schema(true, FieldVisibility.READ_ONLY), true));
        }
        for (var access : List.of(FieldVisibility.HIDDEN, FieldVisibility.MASKED)) {
            fails(() -> ExpensePriorApprovalPolicy.validate(graph("priorRequestOverTolerance == true", "PRIOR_REQUEST_REVIEW", false), schema(true, access)));
        }
    }

    @Test void unknownConditionsDefaultRoutesParallelBranchesAndLaterReviewsCannotBypassTheControl() {
        for (var condition : List.of("priorRequestOverTolerance == false", "amount > 100", "priorRequestOverTolerance == true && amount > 100")) {
            fails(() -> ExpensePriorApprovalPolicy.validate(graph(condition, "PRIOR_REQUEST_REVIEW", false), schema(true, FieldVisibility.READ_ONLY)));
        }
        fails(() -> ExpensePriorApprovalPolicy.validate(graph("priorRequestOverTolerance == true", "PRIOR_REQUEST_REVIEW", true), schema(true, FieldVisibility.READ_ONLY)));
        var base = graph("priorRequestOverTolerance == true", "PRIOR_REQUEST_REVIEW", false);
        var parallel = new Graph(base.nodes().stream().map(node -> node.id().equals("gate") ? new Node(node.id(), node.name(), NodeType.PARALLEL_GATEWAY, Map.of()) : node).toList(), base.edges());
        fails(() -> ExpensePriorApprovalPolicy.validate(parallel, schema(true, FieldVisibility.READ_ONLY)));
    }

    @Test void compoundTrueConditionsKeepSafeDefaultsAndUnconditionalReviewsWork() {
        for (var condition : List.of("priorRequestOverTolerance == true || amount > 100", "!(priorRequestOverTolerance == false)")) {
            var base = graph(condition, "PRIOR_REQUEST_REVIEW", false);
            var versionTwo = new Graph(base.nodes(), base.edges(), 2);
            assertThatCode(() -> ExpensePriorApprovalPolicy.validate(versionTwo, schema(true, FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
        }
        var base = graph("priorRequestOverTolerance == true", "PRIOR_REQUEST_REVIEW", false);
        var direct = new Graph(base.nodes().stream().filter(node -> !node.id().equals("gate")).toList(), List.of(
                new Edge("a", "start", "prior", ""), new Edge("b", "prior", "finance", ""), new Edge("c", "finance", "end", "")));
        assertThatCode(() -> ExpensePriorApprovalPolicy.validate(direct, schema(true, FieldVisibility.READ_ONLY))).doesNotThrowAnyException();
    }

    private Graph graph(String condition, String stage, boolean afterFinance) {
        var edges = afterFinance ? List.of(new Edge("a", "start", "finance", ""), new Edge("b", "finance", "gate", ""),
                new Edge("c", "gate", "prior", condition), new Edge("d", "gate", "end", "", true), new Edge("e", "prior", "end", ""))
                : List.of(new Edge("a", "start", "gate", ""), new Edge("b", "gate", "prior", condition), new Edge("c", "gate", "finance", "", true),
                    new Edge("d", "prior", "finance", ""), new Edge("e", "finance", "end", ""));
        return new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("gate", "额度条件", NodeType.EXCLUSIVE_GATEWAY, Map.of()),
                new Node("prior", "额度审核", NodeType.USER_TASK, Map.of("expenseStage", stage)),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("expenseStage", "FINANCE_REVIEW")), new Node("end", "结束", NodeType.END, Map.of())), edges, 2);
    }
    private FormSchema schema(boolean controlled, FieldVisibility access) {
        var fields = new ArrayList<>(List.of(new FormSchema.Field("expenseDetails", "明细", FormSchema.FieldType.TEXT, true, null,
                null, null, null, null, null, null, true, Map.of("prior", access, "finance", FieldVisibility.READ_ONLY)),
                field("amount", FormSchema.FieldType.NUMBER), field("currency", FormSchema.FieldType.TEXT), field("overPolicy", FormSchema.FieldType.BOOLEAN)));
        if (controlled) fields.add(field(ExpenseFormContract.PRIOR_OVER_TOLERANCE, FormSchema.FieldType.BOOLEAN));
        return new FormSchema(2, fields);
    }
    private FormSchema.Field field(String name, FormSchema.FieldType type) { return new FormSchema.Field(name, name, type, true, null, null, null, null, null); }
    private void fails(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("EXPENSE_PRIOR_APPROVAL_REQUIRED"));
    }
}
