package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.expense.ExpenseRiskEvidence;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 把已授权的费用选择投影为去标识的发送目录；不读取数据库，也不把完整票号或身份写入来源。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExpenseRiskSources {
    private final JsonUtil json;

    /** 使用项目统一序列化生成原文和内容摘要。 */
    public ExpenseRiskSources(JsonUtil json) { this.json = json; }

    /** 调用方先校验全部轮次权限和所选行归属；日历依据及规范票号只参与本地计算。 */
    public Catalog available(List<ExpenseRiskInput.Document> documents, ExpenseRiskInput.CalendarReference calendar,
                             ExpenseRiskEvidence.Input facts) {
        var expectedLines = documents.stream().flatMap(document -> document.lineNos().stream()
                .map(line -> new ExpenseRiskEvidence.LineId(document.ordinal(), line))).collect(Collectors.toSet());
        if (!expectedLines.equals(facts.lines().stream().map(ExpenseRiskEvidence.Line::id).collect(Collectors.toSet()))
                || (calendar == null) != (facts.calendar() == null)
                || calendar != null && !calendar.rulesDigest().equals(calendarDigest(facts.calendar()))) throw invalid();
        var sources = new ArrayList<AssistModelPort.Source>(); var concerns = new ArrayList<ExpenseRiskInput.Concern>();
        var derived = ExpenseRiskEvidence.derive(facts);
        for (var document : documents) {
            var lines = facts.lines().stream().filter(line -> line.id().document() == document.ordinal())
                    .sorted(java.util.Comparator.comparingInt(line -> line.id().lineNo())).toList();
            sources.add(source(document.sourceId(), "单据 " + document.ordinal() + " 的已选费用事实", new DocumentFact(document.ordinal(), lines)));
        }
        sources.add(source(ExpenseRiskInput.COVERAGE_SOURCE, "本次选择范围及票据查验覆盖",
                new Coverage(documents.size(), facts.lines().size(), derived.invoiceCoverage(), calendar != null)));
        for (var sameDay : derived.sameDay()) append(sources, concerns, ExpenseRiskInput.Kind.SAME_DAY, "同日同类费用", sameDay, documents(sameDay.lines()));
        for (var cross : derived.crossDocument()) append(sources, concerns, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "所选对照单中的同类费用", cross, documents(cross.lines()));
        for (var day : derived.workdays()) {
            if (day.status() == ExpenseRiskEvidence.DayStatus.NON_WORKING) {
                append(sources, concerns, ExpenseRiskInput.Kind.NON_WORKING_DAY, "所选工作日历中的非工作日", day, List.of(day.line().document()));
            }
        }
        for (var sequence : derived.invoiceSequences()) {
            var lines = sequence.numbers().stream().flatMap(number -> number.invoices().stream()).map(ExpenseRiskEvidence.InvoiceId::line).toList();
            append(sources, concerns, ExpenseRiskInput.Kind.CONSECUTIVE_INVOICES, "查验票号数值相邻", sequence, documents(lines));
        }
        return new Catalog(documents, calendar, concerns, sources);
    }

    /** 星期固定按枚举顺序编码，不能依赖 Map.copyOf 在不同进程中的遍历顺序。 */
    public String calendarDigest(CalendarRules rules) {
        var week = java.util.Arrays.stream(DayOfWeek.values()).filter(rules.weeklyHours()::containsKey)
                .map(day -> new WeeklyPeriods(day, rules.weeklyHours().get(day))).toList();
        return AssistConfiguration.digest(json.write(new CalendarFingerprint(rules.zoneId(), week, rules.overrides())));
    }

    /** 必须明确选择全部比较范围、覆盖信息和至少一条观察，不能静默补选操作者未确认的正文。 */
    public ExpenseRiskInput select(Catalog catalog, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > AssistInput.MAX_REFERENCES
                || sourceIds.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(sourceIds).size() != sourceIds.size()) throw invalid();
        Set<String> selectedIds = Set.copyOf(sourceIds);
        var selected = catalog.sources().stream().filter(source -> selectedIds.contains(source.reference().sourceId())).toList();
        if (selected.size() != selectedIds.size()) throw new DomainException("FORBIDDEN", "Risk source is not in the authorized selection");
        if (json.write(selected).getBytes(StandardCharsets.UTF_8).length > AssistInputService.MAX_INPUT_BYTES) throw invalid();
        return new ExpenseRiskInput(catalog.documents(), catalog.calendar(),
                catalog.concerns().stream().filter(concern -> selectedIds.contains(concern.sourceId())).toList(), selected);
    }

    private void append(List<AssistModelPort.Source> sources, List<ExpenseRiskInput.Concern> concerns,
                         ExpenseRiskInput.Kind kind, String label, Object facts, List<Integer> documents) {
        String id = "expense:risk[" + (concerns.size() + 1) + "]";
        concerns.add(new ExpenseRiskInput.Concern(id, kind, documents));
        sources.add(source(id, label, new Observation(kind, facts)));
    }
    private AssistModelPort.Source source(String id, String label, Object value) {
        String content = json.write(value);
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), label, content);
    }
    private static List<Integer> documents(List<ExpenseRiskEvidence.LineId> lines) { return lines.stream().map(ExpenseRiskEvidence.LineId::document).distinct().sorted().toList(); }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Explicitly select bounded expense scope and matching risk evidence"); }

    /**
     * 原始文档绑定只供本地应用服务使用，公开目录应只返回 sources 和可选观察。
     * @author owlzhangfq@gmail.com
     */
    public record Catalog(List<ExpenseRiskInput.Document> documents, ExpenseRiskInput.CalendarReference calendar,
                           List<ExpenseRiskInput.Concern> concerns, List<AssistModelPort.Source> sources) {
        /** 固定计算时的目录，客户端只能选择标识，不能替换正文。 */
        public Catalog { documents = List.copyOf(documents); concerns = List.copyOf(concerns); sources = List.copyOf(sources); }
    }

    /**
     * 仅含选择内序号、日期、类别、申报额和票数，不包含人员、账户、附件和自由文本。
     * @author owlzhangfq@gmail.com
     */
    private record DocumentFact(int document, List<ExpenseRiskEvidence.Line> lines) { }

    /**
     * 比较范围始终显式呈现，缺少可信票据事实时不能把没有连号当作全部已查验。
     * @author owlzhangfq@gmail.com
     */
    private record Coverage(int selectedDocuments, int selectedLines, ExpenseRiskEvidence.InvoiceCoverage invoiceCoverage, boolean calendarProvided) { }

    /**
     * 本地派生事实与解释方向绑定，模型不能新增业务事实或修改方向。
     * @author owlzhangfq@gmail.com
     */
    private record Observation(ExpenseRiskInput.Kind kind, Object facts) { }

    /**
     * 规则摘要使用有序结构，仅本地保存，不把日期备注发送给模型。
     * @author owlzhangfq@gmail.com
     */
    private record CalendarFingerprint(String zoneId, List<WeeklyPeriods> weeklyHours, List<CalendarRules.DayOverride> overrides) { }

    /**
     * 一周条目的顺序与 JVM 集合实现无关。
     * @author owlzhangfq@gmail.com
     */
    private record WeeklyPeriods(DayOfWeek day, List<CalendarRules.Period> periods) { }
}
