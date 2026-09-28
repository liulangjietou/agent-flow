package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 提交时冻结的申请轮次；内容不可变，结论只允许仓储以受控方法补充。
 * @author owlzhangfq@gmail.com
 */
public record SubmissionRound(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                              long definitionVersion, String title, Map<String, Object> payload,
                              String submittedBy, Instant submittedAt, Status status, String reason,
                              String completedBy, Instant completedAt, FormSchema formSchema,
                              InitiatorContext initiatorContext) {
    /** 未记录任职的旧轮次保持缺失事实，不从当前组织推断。 */
    public SubmissionRound(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                           long definitionVersion, String title, Map<String, Object> payload, String submittedBy,
                           Instant submittedAt, Status status, String reason, String completedBy,
                           Instant completedAt, FormSchema formSchema) {
        this(tenantId, applicationId, roundNo, processInstanceId, definitionVersion, title, payload,
                submittedBy, submittedAt, status, reason, completedBy, completedAt, formSchema, null);
    }
    /** 兼容没有表单快照的旧调用和旧数据。 */
    public SubmissionRound(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                           long definitionVersion, String title, Map<String, Object> payload,
                           String submittedBy, Instant submittedAt, Status status, String reason,
                           String completedBy, Instant completedAt) {
        this(tenantId, applicationId, roundNo, processInstanceId, definitionVersion, title, payload,
                submittedBy, submittedAt, status, reason, completedBy, completedAt, null);
    }

    public SubmissionRound {
        payload = PayloadSnapshot.copy(payload);
    }

    /** 使用本轮实际启动的实例构建提交快照，不从后续申请内容恢复旧历史。 */
    public static SubmissionRound submitted(Application application, String processInstanceId,
                                            String submittedBy, Instant submittedAt) {
        return submitted(application, processInstanceId, submittedBy, submittedAt, null);
    }

    /** 本轮任职上下文与提交正文共同冻结，后续重提创建另一份快照。 */
    public static SubmissionRound submitted(Application application, String processInstanceId,
                                            String submittedBy, Instant submittedAt, InitiatorContext initiatorContext) {
        return new SubmissionRound(application.tenantId(), application.id(), application.roundNo(),
                processInstanceId, application.definitionVersion(), application.title(), application.payload(),
                submittedBy, submittedAt, Status.IN_APPROVAL, null, null, null, application.formSchema(), initiatorContext);
    }

    /**
     * 轮次自身的处理结果，不包含申请草稿或支付状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Status {
        IN_APPROVAL, RETURNED, REJECTED, APPROVED, WITHDRAWN;

        /** 结论更新不能将已提交轮次重新变为审批中。 */
        public void requireTerminal() {
            if (this == IN_APPROVAL) {
                throw new DomainException("DOMAIN_RULE_VIOLATION", "A submission round conclusion must be terminal");
            }
        }
    }
}
