package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * 原运行的一次执行观测；未取得用量或最终结果时保留未知，不把未知金额补成零。
 * @author owlzhangfq@gmail.com
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AgentExecutionUsage(UUID runId, Kind kind, UUID subjectId, Instant queuedAt, Instant startedAt,
        Instant completedAt, Long queueMillis, Long executionMillis, String outcome, String providerId,
        String modelVersion, String promptVersion, String usageStatus, Long inputTokens, Long outputTokens,
        Long totalTokens) {
    /**
     * 六种已存在的模型用途，不允许请求指定表名或查询其他人的执行。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { SUMMARY, DRAFT, INVOICE, EXPENSE_DRAFT, PRECHECK_EXPLANATION, EXPENSE_RISK }
}
