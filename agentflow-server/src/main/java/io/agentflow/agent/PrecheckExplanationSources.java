package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpensePrecheckJob;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 从本人原检查与已保存费用投影发送目录，不接受客户端传入正文或权威结论。
 * @author owlzhangfq@gmail.com
 */
@Component
public class PrecheckExplanationSources {
    private final JsonUtil json;
    /** 序列化后的原始来源文本与 SHA-256 一同冻结。 */
    public PrecheckExplanationSources(JsonUtil json) { this.json = json; }

    /** 账户、票号、原件、自由文本和资源归属不属于可发送目录。 */
    public List<AssistModelPort.Source> available(ExpenseReport report, ExpensePrecheckJob job) {
        var sources = new ArrayList<AssistModelPort.Source>();
        sources.add(source(PrecheckExplanationInput.RESULT_SOURCE, "本次预检结论", new ResultFact(job.status(), job.completedAt(), job.input().accountingDate())));
        var findings = job.result().findings();
        for (int index = 0; index < findings.size(); index++) {
            var finding = findings.get(index);
            sources.add(source(PrecheckExplanationInput.FINDING_PREFIX + index + "]",
                    (finding.lineNo() == null ? "整单" : "第 " + finding.lineNo() + " 行") + "检查问题 · " + finding.code(), finding));
        }
        for (var line : report.content().lines()) {
            sources.add(source("expense:line[" + line.lineNo() + "]", "第 " + line.lineNo() + " 行费用数据",
                    new LineFact(line.lineNo(), line.categoryCode(), line.incurredOn(), line.endedOn(), line.cityCode(),
                            line.quantity(), line.unit(), line.claimedGross(), line.claimedTax(), line.invoiceIds().size(), line.priorRequest() != null)));
        }
        return List.copyOf(sources);
    }

    /** 只保存明确勾选的来源；至少包含结论，业务失败还须选择具体问题。 */
    public PrecheckExplanationInput select(ExpenseReport report, ExpensePrecheckJob job, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > AssistInput.MAX_REFERENCES
                || new HashSet<>(sourceIds).size() != sourceIds.size()) throw invalid();
        var choices = available(report, job).stream().collect(Collectors.toMap(value -> value.reference().sourceId(), Function.identity()));
        var selected = new ArrayList<AssistModelPort.Source>();
        for (var id : sourceIds) {
            var value = choices.get(id);
            if (value == null) throw new DomainException("FORBIDDEN", "Selected precheck source is not sendable");
            selected.add(value);
        }
        if (json.write(selected).getBytes(StandardCharsets.UTF_8).length > AssistInputService.MAX_INPUT_BYTES) throw invalid();
        var input = job.input();
        return new PrecheckExplanationInput(report.id(), report.applicationId(), input.applicationVersion(), input.financialVersion(),
                input.id(), input.attempt(), job.status(), job.completedAt(), job.result().observation().validUntil(), selected);
    }
    private AssistModelPort.Source source(String id, String label, Object value) {
        String content = json.write(value);
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), label, content);
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Select bounded precheck explanation sources"); }

    /**
     * 结论不含申请、人员或外部系统标识，模型不能更新它。
     * @author owlzhangfq@gmail.com
     */
    private record ResultFact(ExpensePrecheckJob.Status status, Instant checkedAt, LocalDate accountingDate) { }
    /**
     * 金额始终标识为申报值，不冒充核定额、制度上限或预算余额。
     * @author owlzhangfq@gmail.com
     */
    private record LineFact(int lineNo, String categoryCode, LocalDate incurredOn, LocalDate endedOn, String cityCode,
                            BigDecimal quantity, ExpenseLine.Unit unit, Money claimedGross, Money claimedTax,
                            int invoiceCount, boolean priorRequestSelected) { }
}
