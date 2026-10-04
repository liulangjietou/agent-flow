package io.agentflow.expense.reporting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 报表只输出已授权汇总，不包含原件、账户、申请人或无法读取的数据条数。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseFinancialReport(Instant generatedAt, String timeZone, String scope, ExpenseReportQueryParameters.Query filters,
        ExpenseFinancialMetrics.Metrics totals, List<Group> groups, Activity activity, Resources resources, Backlog backlog) {
    /**
     * 名称与编号取原轮次；缺失组织保留 null，类别间轮次数不能相加。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Group(String dimension, String code, String name, ExpenseFinancialMetrics.Metrics metrics) { }
    /**
     * 无查验结论时失败率为空，未完成和服务不可用均不进入失败率分母。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Verification(long succeeded, long rejected, long unavailable, long pending, BigDecimal failureRate) { }
    /**
     * 已记录拒绝不是旧时期的完整审计；启用时点之前保持历史未知。
     * @author owlzhangfq@gmail.com
     */
    public record DuplicateSubmissions(long recorded, Instant recordingStartedAt, boolean containsUnrecordedHistory) { }
    /**
     * 实际查验任务、预检及回滚后的提交拒绝按各自尝试去重。
     * @author owlzhangfq@gmail.com
     */
    public record Activity(Verification verification, long duplicatePrechecks, DuplicateSubmissions duplicateSubmissions) { }
    /**
     * 当前余额只来自有原业务读取依据的资源；借款不能虚构费用类别。
     * @author owlzhangfq@gmail.com
     */
    public record Resources(Instant asOf, boolean advanceCategoryApplicable, List<ExpenseResourceMetrics.AdvanceTotals> advances,
                            List<ExpenseResourceMetrics.PriorTotals> priorRequests) { }
    /**
     * 非成功操作的当前状态分别累计，不把未知或停止状态并入明确失败。
     * @author owlzhangfq@gmail.com
     */
    public record Backlog(Instant asOf, Map<String, Long> vouchers, Map<String, Long> payments) { }
}
