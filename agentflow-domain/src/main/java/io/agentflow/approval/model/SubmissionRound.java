package io.agentflow.approval.model;

import io.agentflow.common.DomainException;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交时冻结的申请轮次；内容不可变，结论只允许仓储以受控方法补充。
 * @author owlzhangfq@gmail.com
 */
public record SubmissionRound(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                              long definitionVersion, String title, Map<String, Object> payload,
                              String submittedBy, Instant submittedAt, Status status, String reason,
                              String completedBy, Instant completedAt) {
    public SubmissionRound {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (payload != null) payload.forEach((key, value) -> snapshot.put(key, freeze(value)));
        payload = Collections.unmodifiableMap(snapshot);
    }

    /** 使用本轮实际启动的实例构建提交快照，不从后续申请内容恢复旧历史。 */
    public static SubmissionRound submitted(Application application, String processInstanceId,
                                            String submittedBy, Instant submittedAt) {
        return new SubmissionRound(application.tenantId(), application.id(), application.roundNo(),
                processInstanceId, application.definitionVersion(), application.title(), application.payload(),
                submittedBy, submittedAt, Status.IN_APPROVAL, null, null, null);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            values.forEach((key, nested) -> copy.put(key, freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) {
            return values.stream().map(SubmissionRound::freeze).toList();
        }
        return value;
    }

    /**
     * 轮次自身的处理结果，不包含申请草稿或支付状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Status {
        IN_APPROVAL, RETURNED, REJECTED, APPROVED;

        /** 结论更新不能将已提交轮次重新变为审批中。 */
        public void requireTerminal() {
            if (this == IN_APPROVAL) {
                throw new DomainException("DOMAIN_RULE_VIOLATION", "A submission round conclusion must be terminal");
            }
        }
    }
}
