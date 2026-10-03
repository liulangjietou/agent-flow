package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpensePrecheckJob;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 本人明确选择的预检来源与原业务版本；标识和期限只用于本地校验，不发给模型。
 * @author owlzhangfq@gmail.com
 */
public record PrecheckExplanationInput(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion,
        UUID precheckId, long attempt, ExpensePrecheckJob.Status result, Instant checkedAt, Instant validUntil,
        List<AssistModelPort.Source> sources) {
    public static final String RESULT_SOURCE = "precheck:result";
    public static final String FINDING_PREFIX = "precheck:finding[";
    public static final int MAX_ISSUES = 20;

    /** 来源身份不可重复，业务失败至少选择一个原检查问题，成功结论只能解释其本身。 */
    public PrecheckExplanationInput {
        if (reportId == null || applicationId == null || applicationVersion < 1 || financialVersion < 1 || precheckId == null
                || attempt < 1 || result == null || result == ExpensePrecheckJob.Status.QUEUED || result == ExpensePrecheckJob.Status.RUNNING
                || checkedAt == null || validUntil == null || !validUntil.isAfter(checkedAt)
                || CollectionUtils.isEmpty(sources) || sources.size() > AssistInput.MAX_REFERENCES) throw invalid();
        var ids = new HashSet<String>(); int issues = 0;
        for (var source : sources) {
            if (source == null || source.reference() == null || StringUtils.isBlank(source.label()) || source.label().length() > 256
                    || source.content() == null || !ids.add(source.reference().sourceId())) throw invalid();
            String id = source.reference().sourceId();
            if (!id.equals(RESULT_SOURCE) && !id.matches("(?:precheck:finding|expense:line)\\[[0-9]{1,3}\\]")) throw invalid();
            if (id.startsWith(FINDING_PREFIX)) issues++;
        }
        if (!ids.contains(RESULT_SOURCE) || issues > MAX_ISSUES
                || result == ExpensePrecheckJob.Status.READY && issues != 0 || result != ExpensePrecheckJob.Status.READY && issues == 0) throw invalid();
        sources = List.copyOf(sources);
    }

    /** 模型必须逐项解释所选问题，不能只返回整体结论而遗漏业务失败。 */
    public List<String> issueIds() {
        return result == ExpensePrecheckJob.Status.READY ? List.of(RESULT_SOURCE)
                : sources.stream().map(source -> source.reference().sourceId()).filter(id -> id.startsWith(FINDING_PREFIX)).toList();
    }

    /** 解释的采纳截止时间由原检查决定，不能随生成或读取延长。 */
    public boolean currentAt(Instant at) { return !at.isBefore(checkedAt) && at.isBefore(validUntil); }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Invalid precheck explanation input"); }
}
