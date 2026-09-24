package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

/**
 * 审批摘要建议；每个陈述关联来源，置信度只是模型报告值，不构成审批结论。
 * @author owlzhangfq@gmail.com
 */
public record AssistSuggestion(String providerId, String modelVersion, String promptVersion,
                               List<Claim> claims, BigDecimal confidence) {
    public static final int MAX_CLAIMS = 20;
    public static final int MAX_CLAIM_LENGTH = 1000;
    public static final int MAX_VERSION_LENGTH = 128;

    /** 适配器提供真实模型标识，模型正文只作为不可信的建议和证据引用输入。 */
    public AssistSuggestion {
        providerId = identifier(providerId);
        modelVersion = identifier(modelVersion);
        promptVersion = identifier(promptVersion);
        if (CollectionUtils.isEmpty(claims) || claims.size() > MAX_CLAIMS || claims.stream().anyMatch(java.util.Objects::isNull)
                || confidence == null || confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("Invalid approval summary result");
        }
        claims = List.copyOf(claims);
    }

    /** 逐条核对模型引用，只证明来源绑定正确，不把引用匹配当作陈述真实性验证。 */
    public void requireEvidenceFrom(AssistInput input) {
        var available = new HashSet<>(input.references());
        if (claims.stream().flatMap(claim -> claim.evidence().stream()).anyMatch(reference -> !available.contains(reference))) {
            throw invalid("Summary evidence does not match the authorized input");
        }
    }

    /** 返回可供人工修订的原始建议文本，不解释其中任何指令或标记。 */
    public String text() { return String.join("\n", claims.stream().map(Claim::text).toList()); }

    /**
     * 单条摘要陈述与其引用证据，原文保持不变供人工核对。
     * @author owlzhangfq@gmail.com
     */
    public record Claim(String text, List<AssistInput.Reference> evidence) {
        /** 空陈述、无证据及重复证据直接拒绝，不在下游默默修补模型输出。 */
        public Claim {
            if (StringUtils.isBlank(text) || text.length() > MAX_CLAIM_LENGTH || CollectionUtils.isEmpty(evidence)
                    || evidence.size() > AssistInput.MAX_REFERENCES
                    || evidence.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(evidence).size() != evidence.size()) {
                throw invalid("Invalid summary claim or evidence");
            }
            evidence = List.copyOf(evidence);
        }
    }

    private static String identifier(String value) {
        if (StringUtils.isBlank(value) || value.length() > MAX_VERSION_LENGTH) throw invalid("Invalid model or prompt identifier");
        return value;
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_AGENT_OUTPUT", message); }
}
