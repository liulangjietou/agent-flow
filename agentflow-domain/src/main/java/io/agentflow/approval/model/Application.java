package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批申请聚合，维护申请轮次和审批生命周期不变量。
 * @author owlzhangfq@gmail.com
 */
public final class Application {
    private final UUID id;
    private final String tenantId;
    private final String businessNo;
    private final String processKey;
    private final long definitionVersion;
    private final FormSchema formSchema;
    private final String runtimeDefinitionId;
    private final NotificationTexts notificationTexts;
    private final String createdBy;
    private final BusinessReference businessReference;
    private String title;
    private Map<String, Object> payload;
    private ApplicationStatus status;
    private int roundNo;
    private long version;

    /** 创建草稿申请。 */
    public static Application draft(UUID id, String tenantId, String businessNo, String processKey,
                                    long definitionVersion, String createdBy, String title,
                                    Map<String, Object> payload) {
        return draft(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, null);
    }

    /** 新建申请时冻结已发布表单，类型与字段白名单由聚合自身保证。 */
    public static Application draft(UUID id, String tenantId, String businessNo, String processKey,
                                    long definitionVersion, String createdBy, String title,
                                    Map<String, Object> payload, FormSchema formSchema) {
        return draft(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, formSchema, null);
    }

    /** 冻结创建时解析的实际定义标识，后续同版本的其他来源不能替换它。 */
    public static Application draft(UUID id, String tenantId, String businessNo, String processKey,
                                    long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                    FormSchema formSchema, String runtimeDefinitionId) {
        return draft(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, formSchema,
                runtimeDefinitionId, NotificationTexts.EMPTY);
    }

    /** 在申请创建时冻结发布文案，不接受表单字段或调用方覆盖通知内容。 */
    public static Application draft(UUID id, String tenantId, String businessNo, String processKey,
                                    long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                    FormSchema formSchema, String runtimeDefinitionId, NotificationTexts notificationTexts) {
        if (formSchema != null) formSchema.validateDraft(payload);
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, ApplicationStatus.DRAFT, 1, 1, formSchema, runtimeDefinitionId, notificationTexts);
    }

    /** 业务应用服务创建不可变结构化绑定；普通表单入口不能传入该引用。 */
    public static Application draftBusiness(UUID id, String tenantId, String businessNo, String processKey,
                                            long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                            FormSchema formSchema, String runtimeDefinitionId, NotificationTexts notificationTexts,
                                            BusinessReference businessReference) {
        if (formSchema != null) formSchema.validateDraft(payload);
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload,
                ApplicationStatus.DRAFT, 1, 1, formSchema, runtimeDefinitionId, notificationTexts, Objects.requireNonNull(businessReference));
    }

    /** 从仓储恢复聚合。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title,
                                      Map<String, Object> payload, ApplicationStatus status,
                                      int roundNo, long version) {
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, status, roundNo, version, null, null);
    }

    /** 恢复原记录的表单快照，不使用后来发布的新表单覆盖历史。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title,
                                      Map<String, Object> payload, ApplicationStatus status,
                                      int roundNo, long version, FormSchema formSchema) {
        return restore(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, status, roundNo, version, formSchema, null);
    }

    /** 从存储恢复实际定义标识，旧记录未保存时保持 null。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                      ApplicationStatus status, int roundNo, long version, FormSchema formSchema, String runtimeDefinitionId) {
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, status, roundNo, version, formSchema, runtimeDefinitionId);
    }

    /** 恢复原申请的通知配置，重提继续使用创建时的流程版本。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                      ApplicationStatus status, int roundNo, long version, FormSchema formSchema,
                                      String runtimeDefinitionId, NotificationTexts notificationTexts) {
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, status, roundNo, version, formSchema, runtimeDefinitionId, notificationTexts);
    }

    /** 结构化绑定从独立数据库列恢复，不由表单内容推断。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title, Map<String, Object> payload,
                                      ApplicationStatus status, int roundNo, long version, FormSchema formSchema,
                                      String runtimeDefinitionId, NotificationTexts notificationTexts, BusinessReference businessReference) {
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload,
                status, roundNo, version, formSchema, runtimeDefinitionId, notificationTexts, businessReference);
    }

    private Application(UUID id, String tenantId, String businessNo, String processKey, long definitionVersion,
                        String createdBy, String title, Map<String, Object> payload, ApplicationStatus status,
                        int roundNo, long version, FormSchema formSchema, String runtimeDefinitionId) {
        this(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, status,
                roundNo, version, formSchema, runtimeDefinitionId, NotificationTexts.EMPTY);
    }

    private Application(UUID id, String tenantId, String businessNo, String processKey, long definitionVersion,
                        String createdBy, String title, Map<String, Object> payload, ApplicationStatus status,
                        int roundNo, long version, FormSchema formSchema, String runtimeDefinitionId, NotificationTexts notificationTexts) {
        this(id, tenantId, businessNo, processKey, definitionVersion, createdBy, title, payload, status, roundNo, version,
                formSchema, runtimeDefinitionId, notificationTexts, null);
    }

    private Application(UUID id, String tenantId, String businessNo, String processKey, long definitionVersion,
                        String createdBy, String title, Map<String, Object> payload, ApplicationStatus status,
                        int roundNo, long version, FormSchema formSchema, String runtimeDefinitionId,
                        NotificationTexts notificationTexts, BusinessReference businessReference) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.businessNo = require(businessNo, "businessNo");
        this.processKey = require(processKey, "processKey");
        this.definitionVersion = definitionVersion;
        this.formSchema = formSchema;
        this.runtimeDefinitionId = runtimeDefinitionId;
        this.notificationTexts = notificationTexts == null ? NotificationTexts.EMPTY : notificationTexts;
        this.createdBy = require(createdBy, "createdBy");
        this.businessReference = businessReference;
        this.title = require(title, "title");
        this.payload = copyPayload(payload);
        this.status = Objects.requireNonNull(status);
        this.roundNo = roundNo;
        this.version = version;
    }

    /** 提交草稿并进入审批。 */
    public void submit(long expectedVersion) {
        checkVersion(expectedVersion);
        int submittingRound = nextSubmissionRound();
        if (formSchema != null) formSchema.validateSubmission(payload);
        roundNo = submittingRound;
        status = ApplicationStatus.IN_APPROVAL;
        version++;
    }

    /** 补正可编辑申请；提交时才递增轮次，原业务标识和定义版本保持不变。 */
    public void revise(long expectedVersion, String title, Map<String, Object> payload) {
        requireEditable(expectedVersion);
        String revisedTitle = require(title, "title");
        if (formSchema != null) formSchema.validateDraft(payload);
        Map<String, Object> revisedPayload = copyPayload(payload);
        this.title = revisedTitle;
        this.payload = revisedPayload;
        version++;
    }

    /** 结构化业务在审批中更新派生路由；业务实体负责证明变化合法，原提交轮次不被覆盖。 */
    public void adjustBusinessPayload(long expectedVersion, BusinessReference reference, Map<String, Object> payload) {
        checkVersion(expectedVersion); requireInApproval();
        if (businessReference == null || !businessReference.equals(reference)) {
            throw new DomainException("USE_BUSINESS_ENDPOINT", "A matching structured business reference is required");
        }
        if (formSchema != null) formSchema.validateSubmission(payload);
        this.payload = copyPayload(payload); version++;
    }

    /** 正文与附件共用可编辑状态，上传不能借草稿入口修改审批中的证据。 */
    public void requireEditable(long expectedVersion) {
        checkVersion(expectedVersion);
        if (!editable()) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only a draft, returned or withdrawn application can be revised");
        }
    }

    /** 是否仍允许申请人修改本次内容；历史轮次始终不可变。 */
    public boolean editable() { return status == ApplicationStatus.DRAFT || status == ApplicationStatus.RETURNED || status == ApplicationStatus.WITHDRAWN; }

    /** 草稿已经预编号为第一轮；退回或撤回后才在下次提交时增加轮次。 */
    public int nextSubmissionRound() {
        if (!editable()) throw new DomainException("DOMAIN_RULE_VIOLATION", "Only a draft, returned or withdrawn application can be submitted");
        return status == ApplicationStatus.DRAFT ? roundNo : roundNo + 1;
    }

    public BusinessReference businessReference() { return businessReference; }

    /** 退回申请人并保留原轮次审计。 */
    public void returnToApplicant(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
        status = ApplicationStatus.RETURNED;
        version++;
    }

    /** 驳回申请并进入终态。 */
    public void reject(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
        status = ApplicationStatus.REJECTED;
        version++;
    }

    /** 审批完成。 */
    public void approve(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
        status = ApplicationStatus.APPROVED;
        version++;
    }

    /** 记录任务领取、转办等不改变审批结论的动作，确保动作幂等版本可校验。 */
    public void recordTaskAction(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
        version++;
    }

    /** 记录等待节点推进、失败或重试，不将系统动作伪装成人工审批意见。 */
    public void recordRuntimeAction(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
        version++;
    }

    /** 申请人撤回未结束的申请。 */
    public void withdraw(long expectedVersion) {
        checkVersion(expectedVersion);
        if (status != ApplicationStatus.IN_APPROVAL) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only an active application can be withdrawn");
        }
        status = ApplicationStatus.WITHDRAWN;
        version++;
    }

    /** 作废未在审批中的申请，保留内容与原轮次并进入不可重提的终态。 */
    public void cancel(long expectedVersion) {
        checkVersion(expectedVersion);
        if (status != ApplicationStatus.DRAFT && status != ApplicationStatus.RETURNED
                && status != ApplicationStatus.WITHDRAWN) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only a draft, returned or withdrawn application can be cancelled");
        }
        status = ApplicationStatus.CANCELLED;
        version++;
    }

    private void requireInApproval() {
        if (status != ApplicationStatus.IN_APPROVAL) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Application is not in approval");
        }
    }

    /** 评论绑定用户核对过的审批上下文，追加沟通不递增申请版本或改变审批结论。 */
    public void requireCommentContext(long expectedVersion) {
        checkVersion(expectedVersion);
        requireInApproval();
    }

    private void checkVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Application version has changed");
        }
    }

    private static String require(String value, String field) {
        if (org.apache.commons.lang3.StringUtils.isBlank(value)) {
            throw new DomainException("INVALID_APPLICATION", field + " is required");
        }
        return value.trim();
    }

    /** 可选表单字段显式设为 null 表示清空，复制时必须保留该键和值。 */
    private static Map<String, Object> copyPayload(Map<String, Object> payload) {
        return PayloadSnapshot.copy(payload);
    }

    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public String businessNo() { return businessNo; }
    public String processKey() { return processKey; }
    public long definitionVersion() { return definitionVersion; }
    public FormSchema formSchema() { return formSchema; }
    public String runtimeDefinitionId() { return runtimeDefinitionId; }
    public NotificationTexts notificationTexts() { return notificationTexts; }
    public String createdBy() { return createdBy; }
    public String title() { return title; }
    public Map<String, Object> payload() { return payload; }
    public ApplicationStatus status() { return status; }
    public int roundNo() { return roundNo; }
    public long version() { return version; }
}
