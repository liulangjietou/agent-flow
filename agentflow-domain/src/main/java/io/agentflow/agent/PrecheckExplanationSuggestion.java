package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 对原检查问题的解释和人工补正建议；不包含金额写入、审批或其他可执行动作。
 * @author owlzhangfq@gmail.com
 */
public record PrecheckExplanationSuggestion(String providerId, String modelVersion, String promptVersion, List<Item> items) {
    public static final int MAX_TEXT_LENGTH = 1000;
    public static final int MAX_CORRECTIONS = 5;
    public static final int MAX_CORRECTION_LENGTH = 500;

    /** 模型和提供者标识来自受控适配器，输出内容保持原样供人工复核。 */
    public PrecheckExplanationSuggestion {
        if (!identifier(providerId) || !identifier(modelVersion) || !identifier(promptVersion)
                || CollectionUtils.isEmpty(items) || items.size() > PrecheckExplanationInput.MAX_ISSUES
                || items.stream().anyMatch(java.util.Objects::isNull)
                || items.stream().map(Item::issueSourceId).distinct().count() != items.size()) throw invalid();
        items = List.copyOf(items);
    }

    /** 引用逐字匹配本次发送的摘要；引用匹配不代表模型解释已获权威验证。 */
    public void requireMatches(PrecheckExplanationInput input) {
        Map<String, AssistInput.Reference> allowed = input.sources().stream().map(AssistModelPort.Source::reference)
                .collect(Collectors.toMap(AssistInput.Reference::sourceId, Function.identity()));
        if (!new HashSet<>(input.issueIds()).equals(items.stream().map(Item::issueSourceId).collect(Collectors.toSet()))) throw invalid();
        for (var item : items) {
            if (!item.evidence().contains(allowed.get(item.issueSourceId()))
                    || item.evidence().stream().anyMatch(reference -> !reference.equals(allowed.get(reference.sourceId())))
                    || input.result() != io.agentflow.expense.ExpensePrecheckJob.Status.READY && item.corrections().isEmpty()) throw invalid();
            for (var patch : item.patches()) {
                var source = input.sources().stream().filter(value -> value.reference().sourceId().equals(patch.sourceId())).findFirst().orElseThrow(PrecheckExplanationSuggestion::invalid);
                if (!source.content().equals(patch.beforeValue()) || !item.evidence().contains(source.reference())) throw invalid();
            }
        }
        var patches = items.stream().flatMap(item -> item.patches().stream()).toList();
        if (patches.stream().map(io.agentflow.expense.ExpenseFieldPatch::sourceId).distinct().count() != patches.size()) throw invalid();
    }

    /**
     * 一条原问题对应解释、文字步骤和允许字段差异，差异必须由本人逐项确认。
     * @author owlzhangfq@gmail.com
     */
    public record Item(String issueSourceId, String explanation, List<String> corrections, List<AssistInput.Reference> evidence,
            List<io.agentflow.expense.ExpenseFieldPatch> patches) {
        /** 旧持久记录只有文字步骤，恢复时没有可执行字段差异。 */
        public Item(String issueSourceId, String explanation, List<String> corrections, List<AssistInput.Reference> evidence) {
            this(issueSourceId, explanation, corrections, evidence, List.of());
        }
        /** 长度、来源及重复项在进入聚合前验证，不能默默截断模型建议。 */
        public Item {
            patches = patches == null ? List.of() : List.copyOf(patches);
            if (patches.size() > MAX_CORRECTIONS || patches.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            if (StringUtils.isBlank(issueSourceId) || issueSourceId.length() > 150 || StringUtils.isBlank(explanation)
                    || explanation.length() > MAX_TEXT_LENGTH || corrections == null || corrections.size() > MAX_CORRECTIONS
                    || corrections.stream().anyMatch(value -> StringUtils.isBlank(value) || value.length() > MAX_CORRECTION_LENGTH)
                    || CollectionUtils.isEmpty(evidence) || evidence.size() > AssistInput.MAX_REFERENCES
                    || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size() != evidence.size()) throw invalid();
            corrections = List.copyOf(corrections); evidence = List.copyOf(evidence);
        }
    }
    private static boolean identifier(String value) { return StringUtils.isNotBlank(value) && value.length() <= AssistSuggestion.MAX_VERSION_LENGTH; }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Precheck explanation does not match the selected evidence"); }
}
