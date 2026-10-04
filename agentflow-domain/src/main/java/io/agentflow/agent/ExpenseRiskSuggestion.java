package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 模型对已选观察的解释、局限及人工核对步骤；没有批准、驳回、核减或金额写回字段。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseRiskSuggestion(String providerId, String modelVersion, String promptVersion, List<Item> items) {
    public static final int MAX_TEXT_LENGTH = 1000;
    public static final int MAX_CHECKS = 5;
    public static final int MAX_CHECK_LENGTH = 500;

    /** 受控适配器填写模型身份，解释与局限保持原文供人工核对。 */
    public ExpenseRiskSuggestion {
        if (!identifier(providerId) || !identifier(modelVersion) || !identifier(promptVersion)
                || CollectionUtils.isEmpty(items) || items.size() > ExpenseRiskInput.MAX_CONCERNS
                || items.stream().anyMatch(Objects::isNull)
                || items.stream().map(Item::concernSourceId).distinct().count() != items.size()) throw invalid();
        items = List.copyOf(items);
    }

    /** 摘要匹配只证明引用范围；模型对风险的推断仍须人工复核，不能变成业务事实。 */
    public void requireMatches(ExpenseRiskInput input) {
        Map<String, AssistInput.Reference> sources = input.sources().stream().map(AssistModelPort.Source::reference)
                .collect(Collectors.toMap(AssistInput.Reference::sourceId, Function.identity()));
        var concerns = input.concerns().stream().collect(Collectors.toMap(ExpenseRiskInput.Concern::sourceId, Function.identity()));
        if (!concerns.keySet().equals(items.stream().map(Item::concernSourceId).collect(Collectors.toSet()))) throw invalid();
        for (var item : items) {
            var concern = concerns.get(item.concernSourceId());
            if (concern.kind() != item.kind() || !item.evidence().containsAll(concern.requiredSourceIds().stream().map(sources::get).toList())
                    || item.evidence().stream().anyMatch(reference -> !reference.equals(sources.get(reference.sourceId())))) throw invalid();
        }
    }

    /**
     * 每条解释必须说明现有证据的局限并给出人工核对步骤，不能仅输出风险标签。
     * @author owlzhangfq@gmail.com
     */
    public record Item(String concernSourceId, ExpenseRiskInput.Kind kind, String explanation, String limitations,
                       List<String> checks, List<AssistInput.Reference> evidence) {
        /** 超长、空白、重复及未知引用全部拒绝，不悄悄截断成看似有效的输出。 */
        public Item {
            if (StringUtils.isBlank(concernSourceId) || concernSourceId.length() > 150 || kind == null
                    || !text(explanation, MAX_TEXT_LENGTH) || !text(limitations, MAX_TEXT_LENGTH)
                    || CollectionUtils.isEmpty(checks) || checks.size() > MAX_CHECKS
                    || checks.stream().anyMatch(value -> !text(value, MAX_CHECK_LENGTH))
                    || CollectionUtils.isEmpty(evidence) || evidence.size() > AssistInput.MAX_REFERENCES
                    || evidence.stream().anyMatch(Objects::isNull) || new HashSet<>(evidence).size() != evidence.size()) throw invalid();
            checks = List.copyOf(checks); evidence = List.copyOf(evidence);
        }
    }
    private static boolean identifier(String value) { return text(value, AssistSuggestion.MAX_VERSION_LENGTH); }
    private static boolean text(String value, int limit) { return StringUtils.isNotBlank(value) && value.length() <= limit; }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Risk explanations must match every selected observation and its evidence"); }
}
