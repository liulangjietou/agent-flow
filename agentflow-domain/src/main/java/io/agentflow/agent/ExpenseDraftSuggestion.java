package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseLine;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 模型只提出行程对应的类别、说明和分摊比例，不具备金额、补贴、发票或审批字段。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseDraftSuggestion(String providerId, String modelVersion, String promptVersion, List<Line> lines) {
    public static final int MAX_LINES = 50;
    public static final BigDecimal TOTAL_PERCENT = new BigDecimal("100");

    public ExpenseDraftSuggestion {
        if (!identifier(providerId) || !identifier(modelVersion) || !identifier(promptVersion)
                || lines == null || lines.size() > MAX_LINES || lines.stream().anyMatch(java.util.Objects::isNull)
                || lines.stream().map(Line::id).distinct().count() != lines.size()) throw invalid();
        lines = List.copyOf(lines);
    }

    /** 类别、单位和归属必须来自本次授权目录；每行必须同时引用原行程及目录，摘要不可改写。 */
    public void requireMatches(ExpenseDraftAssistInput input) {
        var legs = input.itinerary().stream().collect(Collectors.toMap(ExpenseDraftAssistInput.Leg::id, Function.identity()));
        var categories = input.options().categories().stream().collect(Collectors.toMap(io.agentflow.finance.FinanceCatalog.Category::code, Function.identity()));
        var centers = input.options().costCenters().stream().map(ExpenseDraftAssistInput.Choice::code).collect(Collectors.toSet());
        var projects = input.options().projects().stream().map(ExpenseDraftAssistInput.Choice::code).collect(Collectors.toSet());
        Map<String, AssistInput.Reference> allowed = input.sources().stream().map(AssistModelPort.Source::reference)
                .collect(Collectors.toMap(AssistInput.Reference::sourceId, Function.identity()));
        for (var line : lines) {
            var leg = legs.get(line.itineraryId()); var category = categories.get(line.categoryCode());
            if (leg == null || category == null || !category.units().contains(line.unit())
                    || !line.evidence().contains(allowed.get(leg.sourceId()))
                    || !line.evidence().contains(allowed.get(ExpenseDraftAssistInput.CATALOG))
                    || line.evidence().stream().anyMatch(reference -> !reference.equals(allowed.get(reference.sourceId())))
                    || line.allocations().stream().anyMatch(share -> !centers.contains(share.costCenter())
                        || share.projectCode() != null && !projects.contains(share.projectCode()))) throw invalid();
        }
    }

    /**
     * 行程编号固定日期与城市的来源，单据行号由人工带入时分配，不覆盖既有费用行。
     * @author owlzhangfq@gmail.com
     */
    public record Line(String id, int itineraryId, String categoryCode, ExpenseLine.Unit unit, String description,
                       List<Allocation> allocations, List<AssistInput.Reference> evidence) {
        public Line {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || itineraryId < 1 || itineraryId > ExpenseDraftAssistInput.MAX_LEGS
                    || StringUtils.isBlank(categoryCode) || categoryCode.length() > 64 || unit == null
                    || StringUtils.isBlank(description) || description.length() > 2000
                    || CollectionUtils.isEmpty(allocations) || allocations.size() > ExpenseLine.MAX_ALLOCATIONS
                    || allocations.stream().anyMatch(java.util.Objects::isNull)
                    || CollectionUtils.isEmpty(evidence) || evidence.size() > AssistInput.MAX_REFERENCES
                    || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size() != evidence.size()) throw invalid();
            var keys = new HashSet<List<String>>(); BigDecimal total = BigDecimal.ZERO;
            for (var share : allocations) {
                if (!keys.add(List.of(share.costCenter(), share.projectCode() == null ? "" : share.projectCode()))) throw invalid();
                total = total.add(share.percent());
            }
            if (total.compareTo(TOTAL_PERCENT) != 0) throw invalid();
            allocations = List.copyOf(allocations); evidence = List.copyOf(evidence);
        }
    }

    /**
     * 比例仅用于申请人分摊参考，不是费用金额，也不代表项目余额或预算授权。
     * @author owlzhangfq@gmail.com
     */
    public record Allocation(String costCenter, String projectCode, BigDecimal percent) {
        public Allocation {
            if (StringUtils.isBlank(costCenter) || costCenter.length() > 128
                    || projectCode != null && (StringUtils.isBlank(projectCode) || projectCode.length() > 128)
                    || percent == null || percent.signum() <= 0 || percent.compareTo(TOTAL_PERCENT) > 0
                    || percent.stripTrailingZeros().scale() > 2) throw invalid();
            percent = percent.stripTrailingZeros();
        }
    }
    private static boolean identifier(String value) { return StringUtils.isNotBlank(value) && value.length() <= AssistSuggestion.MAX_VERSION_LENGTH; }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Expense draft suggestion does not match the authorized itinerary and catalog"); }
}
