package io.agentflow.expense;

import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.definition.DefinitionValidator;
import io.agentflow.form.FormSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新费用策略须显式发布，且业务与财务职责互斥不能被个别节点配置关闭。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSelfApprovalPolicyTest {
    @Test
    void absentPolicyKeepsPublishedLegacySemantics() {
        assertThat(ExpenseSelfApprovalPolicy.enabled(graph(Map.of(), Map.of()))).isFalse();
    }

    @Test
    void explicitPolicyRequiresExpenseSchemaAndStartNode() {
        var enabled = graph(Map.of(ExpenseSelfApprovalPolicy.PROPERTY, ExpenseSelfApprovalPolicy.ESCALATE_SUPERVISOR), Map.of());
        assertThat(new DefinitionValidator().validate(enabled, null)).contains("EXPENSE_SELF_APPROVAL_REQUIRES_EXPENSE_FORM");
        assertThat(new DefinitionValidator().validate(enabled, schema())).isEmpty();
        assertThat(new DefinitionValidator().validate(graph(Map.of(), Map.of(ExpenseSelfApprovalPolicy.PROPERTY,
                ExpenseSelfApprovalPolicy.ESCALATE_SUPERVISOR)), schema())).contains("EXPENSE_SELF_APPROVAL_POLICY_INVALID");
    }

    @Test
    void nonCanonicalOrUnknownRulesFailAtPublication() {
        for (String value : List.of("", "true", "ESCALATE_SUPERVISOR ", "escalate_supervisor", "SKIP")) {
            assertThat(new DefinitionValidator().validate(graph(Map.of(ExpenseSelfApprovalPolicy.PROPERTY, value), Map.of()), schema()))
                    .contains("EXPENSE_SELF_APPROVAL_POLICY_INVALID");
        }
    }

    @Test
    void dutyConflictsAreSymmetricAndIncludeReceiptWithoutForbiddingTwoFinanceSteps() {
        var nodes = Map.of("business", selection(ExpenseProcessPolicy.Stage.BUSINESS),
                "receipt", selection(ExpenseProcessPolicy.Stage.RECEIPT), "finance", selection(ExpenseProcessPolicy.Stage.FINANCE_REVIEW),
                "recheck", selection(ExpenseProcessPolicy.Stage.FINANCE_RECHECK), "laterBusiness", selection(ExpenseProcessPolicy.Stage.BUSINESS));
        var snapshot = new ExpenseSelfApprovalSnapshot("tenant", UUID.randomUUID(), 1, UUID.randomUUID(), 2,
                "runtime", 1, null, nodes);
        assertThat(snapshot.conflictingNodes("business")).containsExactly("finance", "receipt", "recheck");
        assertThat(snapshot.conflictingNodes("receipt")).containsExactly("business", "laterBusiness");
        assertThat(snapshot.conflictingNodes("finance")).containsExactly("business", "laterBusiness");
    }

    private ExpenseSelfApprovalSnapshot.Selection selection(ExpenseProcessPolicy.Stage stage) {
        return new ExpenseSelfApprovalSnapshot.Selection("审核", stage, "user:reviewer", 1, List.of("reviewer"), List.of("reviewer"), null);
    }

    private Graph graph(Map<String, String> start, Map<String, String> properties) {
        var task = new java.util.HashMap<>(properties); task.put("assigneeRule", "user:reviewer");
        return new Graph(List.of(new Node("start", "开始", NodeType.START, start), new Node("review", "审核", NodeType.USER_TASK, task),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("first", "start", "review", "", false), new Edge("last", "review", "end", "", false)));
    }

    private FormSchema schema() {
        return new FormSchema(2, List.of(
                new FormSchema.Field("expenseDetails", "费用明细", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("amount", "本币金额", FormSchema.FieldType.NUMBER, true, null, null, null, null, null),
                new FormSchema.Field("currency", "币种", FormSchema.FieldType.TEXT, true, null, null, null, null, null),
                new FormSchema.Field("overPolicy", "超标", FormSchema.FieldType.BOOLEAN, true, null, null, null, null, null)));
    }
}
