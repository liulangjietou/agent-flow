package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * 模型逐字段建议与证据；只保存数据，不能携带审批、提交或工具调用。
 * @author owlzhangfq@gmail.com
 */
public record DraftSuggestion(String providerId, String modelVersion, String promptVersion, List<Proposal> proposals) {
    public static final int MAX_PROPOSALS = 51;

    public DraftSuggestion {
        if (!identifier(providerId) || !identifier(modelVersion) || !identifier(promptVersion)
                || proposals == null || proposals.isEmpty() || proposals.size() > MAX_PROPOSALS
                || proposals.stream().anyMatch(java.util.Objects::isNull)
                || proposals.stream().map(Proposal::targetId).distinct().count() != proposals.size()) throw invalid();
        proposals = List.copyOf(proposals);
    }

    /** 证据必须来自本次外发清单，目标和值必须符合冻结的版本化字段。 */
    public void requireMatches(DraftAssistInput input) {
        var references = new HashSet<>(input.sources().stream().map(AssistModelPort.Source::reference).toList());
        for (var proposal : proposals) {
            input.requireValue(proposal.targetId(), proposal.value());
            if (!references.containsAll(proposal.evidence())) throw invalid();
        }
    }

    /**
     * 每个字段单独关联来源，保留模型原始值供人工对比。
     * @author owlzhangfq@gmail.com
     */
    public record Proposal(String targetId, Object value, List<AssistInput.Reference> evidence) {
        public Proposal {
            if (StringUtils.isBlank(targetId) || targetId.length() > 150 || value == null
                    || evidence == null || evidence.isEmpty() || evidence.size() > AssistInput.MAX_REFERENCES
                    || evidence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(evidence).size() != evidence.size()) throw invalid();
            value = freeze(value);
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * 人工只选择已有建议的目标，可修订其值；不覆盖未勾选字段。
     * @author owlzhangfq@gmail.com
     */
    public record Selection(String targetId, Object value) {
        public Selection {
            if (StringUtils.isBlank(targetId) || targetId.length() > 150 || value == null) throw invalid();
            value = freeze(value);
        }
    }

    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(); values.forEach(item -> result.add(freeze(item)));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Map<?, ?> values) {
            var result = new LinkedHashMap<String, Object>();
            values.forEach((key, item) -> { if (!(key instanceof String text)) throw invalid(); result.put(text, freeze(item)); });
            return Collections.unmodifiableMap(result);
        }
        throw invalid();
    }
    private static boolean identifier(String value) { return StringUtils.isNotBlank(value) && value.length() <= AssistSuggestion.MAX_VERSION_LENGTH; }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Invalid draft suggestion or evidence"); }
}
