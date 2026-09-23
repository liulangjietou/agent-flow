package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final String createdBy;
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
        if (formSchema != null) formSchema.validateDraft(payload);
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, ApplicationStatus.DRAFT, 1, 1, formSchema, runtimeDefinitionId);
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

    private Application(UUID id, String tenantId, String businessNo, String processKey, long definitionVersion,
                        String createdBy, String title, Map<String, Object> payload, ApplicationStatus status,
                        int roundNo, long version, FormSchema formSchema, String runtimeDefinitionId) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.businessNo = require(businessNo, "businessNo");
        this.processKey = require(processKey, "processKey");
        this.definitionVersion = definitionVersion;
        this.formSchema = formSchema;
        this.runtimeDefinitionId = runtimeDefinitionId;
        this.createdBy = require(createdBy, "createdBy");
        this.title = require(title, "title");
        this.payload = copyPayload(payload);
        this.status = Objects.requireNonNull(status);
        this.roundNo = roundNo;
        this.version = version;
    }

    /** 提交草稿并进入审批。 */
    public void submit(long expectedVersion) {
        checkVersion(expectedVersion);
        if (status != ApplicationStatus.DRAFT && status != ApplicationStatus.RETURNED
                && status != ApplicationStatus.WITHDRAWN) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only a draft, returned or withdrawn application can be submitted");
        }
        if (formSchema != null) formSchema.validateSubmission(payload);
        if (status != ApplicationStatus.DRAFT) {
            roundNo++;
        }
        status = ApplicationStatus.IN_APPROVAL;
        version++;
    }

    /** 补正可编辑申请；提交时才递增轮次，原业务标识和定义版本保持不变。 */
    public void revise(long expectedVersion, String title, Map<String, Object> payload) {
        checkVersion(expectedVersion);
        if (status != ApplicationStatus.DRAFT && status != ApplicationStatus.RETURNED
                && status != ApplicationStatus.WITHDRAWN) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only a draft, returned or withdrawn application can be revised");
        }
        String revisedTitle = require(title, "title");
        if (formSchema != null) formSchema.validateDraft(payload);
        Map<String, Object> revisedPayload = copyPayload(payload);
        this.title = revisedTitle;
        this.payload = revisedPayload;
        version++;
    }

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

    /** 申请人撤回未结束的申请。 */
    public void withdraw(long expectedVersion) {
        checkVersion(expectedVersion);
        if (status != ApplicationStatus.IN_APPROVAL) {
            throw new DomainException("DOMAIN_RULE_VIOLATION", "Only an active application can be withdrawn");
        }
        status = ApplicationStatus.WITHDRAWN;
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
        return payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public String businessNo() { return businessNo; }
    public String processKey() { return processKey; }
    public long definitionVersion() { return definitionVersion; }
    public FormSchema formSchema() { return formSchema; }
    public String runtimeDefinitionId() { return runtimeDefinitionId; }
    public String createdBy() { return createdBy; }
    public String title() { return title; }
    public Map<String, Object> payload() { return payload; }
    public ApplicationStatus status() { return status; }
    public int roundNo() { return roundNo; }
    public long version() { return version; }
}
