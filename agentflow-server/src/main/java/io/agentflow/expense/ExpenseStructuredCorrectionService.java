package io.agentflow.expense;

import io.agentflow.agent.PrecheckExplanationService;
import io.agentflow.common.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只从原模型建议选择字段，服务器构造正文并交给原费用补正事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseStructuredCorrectionService {
    private final PrecheckExplanationService explanations;
    private final ExpenseDraftService drafts;
    private final ExpenseAllowancePreparation allowances;
    private final ExpenseCorrectionService corrections;
    /** 差异合并属于跨聚合编排，费用实体继续约束自身字段不变量。 */
    public ExpenseStructuredCorrectionService(PrecheckExplanationService explanations, ExpenseDraftService drafts,
            ExpenseAllowancePreparation allowances, ExpenseCorrectionService corrections) {
        this.explanations = explanations; this.drafts = drafts; this.allowances = allowances; this.corrections = corrections;
    }
    /** 准备只选择原建议；外部补贴读取保持在事务之外。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(UUID reportId, UUID runId, Input input) {
        var detail = explanations.get(reportId, runId); var expense = drafts.read(reportId, null);
        if (!detail.canAdopt() || detail.version() != input.expectedRunVersion() || !expense.editable()
                || detail.applicationVersion() != input.applicationVersion() || expense.applicationVersion() != input.applicationVersion()
                || detail.financialVersion() != input.financialVersion() || expense.financialVersion() != input.financialVersion()) {
            throw new DomainException("AGENT_INPUT_CHANGED", "Structured correction input changed");
        }
        var all = detail.suggestion().items().stream().flatMap(item -> item.patches().stream()).toList();
        var selected = input.selectedPatchIds().stream().map(id -> all.stream().filter(patch -> patch.sourceId().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("INVALID_AGENT_REVIEW", "Selected patch is not in the original suggestion"))).toList();
        var issues = detail.suggestion().items().stream().filter(item -> item.patches().stream().anyMatch(selected::contains))
                .map(io.agentflow.agent.PrecheckExplanationSuggestion.Item::issueSourceId).toList();
        var content = ExpenseFieldPatch.apply(expense.content(), selected);
        return new Prepared(input, issues, allowances.prepare(content));
    }
    /** 原费用版本、原解释复核、保存和新预检继续在原服务中原子执行。 */
    @Transactional
    public ExpenseCorrectionService.Receipt apply(UUID reportId, UUID runId, Prepared prepared) {
        var input = prepared.input();
        return corrections.correct(reportId, runId, input.expectedRunVersion(), input.applicationVersion(), input.financialVersion(),
                prepared.issueIds(), input.comment(), prepared.content());
    }
    /**
     * HTTP 只接受建议身份，不接受自造的建议值或字段路径。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@jakarta.validation.constraints.Positive long expectedRunVersion,
            @jakarta.validation.constraints.Positive long applicationVersion, @jakarta.validation.constraints.Positive long financialVersion,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min = 1, max = 100)
            List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String> selectedPatchIds,
            @jakarta.validation.constraints.Size(max = 2000) String comment) {
        /** 重复选项不能静默合并，以免掩盖错误确认。 */
        public Input {
            if (selectedPatchIds != null && selectedPatchIds.stream().distinct().count() != selectedPatchIds.size()) throw new IllegalArgumentException("Duplicate patch selection");
        }
        /** 白名单外的请求字段立即拒绝。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown structured correction field"); }
    }
    /**
     * 内部准备结果保留同一选择及费用校验结果。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(Input input, List<String> issueIds, ExpenseAllowancePreparation.Prepared content) { }
}
