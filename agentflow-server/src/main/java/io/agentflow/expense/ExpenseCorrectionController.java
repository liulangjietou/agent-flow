package io.agentflow.expense;

import io.agentflow.agent.AssistRun;
import io.agentflow.agent.PrecheckExplanationInput;
import io.agentflow.agent.PrecheckExplanationService;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将员工确认的补正交给原费用用例，模型生成和单纯采纳都不会调用此入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/precheck-explanations/{runId}/correct")
public class ExpenseCorrectionController {
    private final PrecheckExplanationService explanations;
    private final ExpenseAllowancePreparation allowances;
    private final ExpenseCorrectionService corrections;
    private final IdempotencyExecutor idempotency;

    /** 补贴读取留在事务外，回执恢复不重新准备或保存费用。 */
    public ExpenseCorrectionController(PrecheckExplanationService explanations, ExpenseAllowancePreparation allowances,
            ExpenseCorrectionService corrections, IdempotencyExecutor idempotency) {
        this.explanations = explanations; this.allowances = allowances; this.corrections = corrections; this.idempotency = idempotency;
    }

    /** 原键回放仍检查本人权限；成功表示补正保存并排队，不表示审批通过。 */
    @PostMapping
    public ResponseEntity<String> correct(@PathVariable UUID id, @PathVariable UUID runId,
            @Valid @RequestBody Input body, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new DomainException("INVALID_AGENT_QUERY", "Expense correction does not accept query parameters");
        explanations.authorize(id);
        return idempotency.executePrepared(request, HttpStatus.OK, () -> allowances.prepare(body.content()),
                prepared -> corrections.correct(id, runId, body.expectedRunVersion(), body.applicationVersion(),
                        body.financialVersion(), body.selectedIssueIds(), body.comment(), prepared));
    }

    /**
     * 只接收员工确认的内容，不接受租户、申请人、预检成功标志或审批指令。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long expectedRunVersion, @NotNull @Positive Long applicationVersion,
            @NotNull @Positive Long financialVersion, @NotNull @Size(min = 1, max = PrecheckExplanationInput.MAX_ISSUES)
            List<@NotBlank @Size(max = 150) String> selectedIssueIds,
            @Size(max = AssistRun.MAX_REVIEW_COMMENT_LENGTH) String comment, @NotNull ExpenseContent content) {
        /** 未声明的操作不能在请求中被静默忽略。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense correction request field"); }
    }
}
