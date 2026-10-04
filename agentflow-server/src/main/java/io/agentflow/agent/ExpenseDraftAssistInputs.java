package io.agentflow.agent;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseReport;
import io.agentflow.finance.FinanceCatalog;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 从本人授权主数据投影明确选择的模型来源，不发送完整费用正文、金额、账户或其他法人目录。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseDraftAssistInputs {
    private final JsonUtil json;

    /** 序列化和来源摘要统一生成，客户端不提供模型原始请求。 */
    public ExpenseDraftAssistInputs(JsonUtil json) { this.json = json; }

    /** 身份与状态由调用用例授权；此处只绑定同一报销快照、真实目录与所选发送内容。 */
    public ExpenseDraftAssistInput select(Application application, ExpenseReport report, FinanceCatalog catalog,
            String financeTargetDigest, String brief, List<ExpenseDraftAssistInput.Leg> itinerary, CatalogSelection selection) {
        if (!application.id().equals(report.applicationId()) || !application.tenantId().equals(report.tenantId())
                || !application.createdBy().equals(report.employeeId()) || !catalog.employeeId().equals(report.employeeId())
                || !new BusinessReference(BusinessReference.Type.EXPENSE, report.id()).equals(application.businessReference())) {
            throw new DomainException("FORBIDDEN", "Expense draft sources belong to another application or employee");
        }
        if (StringUtils.isBlank(brief) || brief.length() > DraftAssistInput.MAX_BRIEF_LENGTH || selection == null
                || itinerary == null || itinerary.isEmpty() || itinerary.size() > ExpenseDraftAssistInput.MAX_LEGS
                || itinerary.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        var entity = catalog.legalEntity(report.content().legalEntityId());
        var categories = select(catalog.categories(), FinanceCatalog.Category::code, selection.categoryCodes());
        var centers = select(catalog.costCenters().stream().filter(value -> value.legalEntityId().equals(entity.id())).toList(),
                FinanceCatalog.CostCenter::code, selection.costCenterCodes()).stream()
                .map(value -> new ExpenseDraftAssistInput.Choice(value.code(), value.name())).toList();
        var projects = select(catalog.projects().stream().filter(value -> value.legalEntityId().equals(entity.id())).toList(),
                FinanceCatalog.Project::code, selection.projectCodes()).stream()
                .map(value -> new ExpenseDraftAssistInput.Choice(value.code(), value.name())).toList();
        var cities = select(catalog.cities(), FinanceCatalog.City::code, itinerary.stream().map(ExpenseDraftAssistInput.Leg::cityCode).distinct().toList())
                .stream().map(value -> new ExpenseDraftAssistInput.Choice(value.code(), value.name())).toList();
        var options = new ExpenseDraftAssistInput.Options(categories, centers, projects, cities);
        var sources = new ArrayList<AssistModelPort.Source>();
        sources.add(source(ExpenseDraftAssistInput.BRIEF, "本次填报要求", brief));
        sources.add(source(ExpenseDraftAssistInput.CATALOG, "本次选择的财务目录",
                Map.of("reportType", report.content().type(), "catalogVersion", catalog.sourceVersion(), "options", options)));
        itinerary.forEach(leg -> sources.add(source(leg.sourceId(), "行程 " + leg.id(), leg)));
        if (json.write(sources).getBytes(StandardCharsets.UTF_8).length > ExpenseDraftAssistInput.MAX_INPUT_BYTES) throw invalid();
        return new ExpenseDraftAssistInput(report.id(), application.id(), application.version(), report.version(), entity.id(),
                report.content().type(), catalog.sourceVersion(), catalog.validUntil(), financeTargetDigest, itinerary, options, sources);
    }

    private AssistModelPort.Source source(String id, String label, Object value) {
        String content = json.write(value);
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), label, content);
    }
    private static <T> List<T> select(List<T> allowed, Function<T, String> key, List<String> selected) {
        var available = allowed.stream().collect(Collectors.toMap(key, Function.identity()));
        return selected.stream().map(id -> {
            var value = available.get(id);
            if (value == null) throw new DomainException("FORBIDDEN", "Selected expense draft catalog entry is unavailable");
            return value;
        }).toList();
    }
    private static List<String> identifiers(List<String> values, boolean emptyAllowed) {
        if (values == null || !emptyAllowed && values.isEmpty() || values.size() > ExpenseDraftAssistInput.MAX_OPTIONS
                || values.stream().anyMatch(value -> StringUtils.isBlank(value) || value.length() > 128)
                || new HashSet<>(values).size() != values.size()) throw invalid();
        return List.copyOf(values);
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Expense draft sources exceed allowed bounds"); }

    /**
     * 用户只选择本人当前目录中的稳定代码，名称和权限始终由服务端取得。
     * @author owlzhangfq@gmail.com
     */
    public record CatalogSelection(List<String> categoryCodes, List<String> costCenterCodes, List<String> projectCodes) {
        public CatalogSelection {
            categoryCodes = identifiers(categoryCodes, false);
            costCenterCodes = identifiers(costCenterCodes, false);
            projectCodes = identifiers(projectCodes, true);
        }
    }
}
