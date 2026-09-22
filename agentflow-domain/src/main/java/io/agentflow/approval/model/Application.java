package io.agentflow.approval.model;

import io.agentflow.common.DomainException;

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
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, ApplicationStatus.DRAFT, 1, 1);
    }

    /** 从仓储恢复聚合。 */
    public static Application restore(UUID id, String tenantId, String businessNo, String processKey,
                                      long definitionVersion, String createdBy, String title,
                                      Map<String, Object> payload, ApplicationStatus status,
                                      int roundNo, long version) {
        return new Application(id, tenantId, businessNo, processKey, definitionVersion, createdBy,
                title, payload, status, roundNo, version);
    }

    private Application(UUID id, String tenantId, String businessNo, String processKey, long definitionVersion,
                        String createdBy, String title, Map<String, Object> payload, ApplicationStatus status,
                        int roundNo, long version) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.businessNo = require(businessNo, "businessNo");
        this.processKey = require(processKey, "processKey");
        this.definitionVersion = definitionVersion;
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
        this.title = require(title, "title");
        this.payload = copyPayload(payload);
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
        if (status != ApplicationStatus.IN_APPROVAL && status != ApplicationStatus.RETURNED) {
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
    public String createdBy() { return createdBy; }
    public String title() { return title; }
    public Map<String, Object> payload() { return payload; }
    public ApplicationStatus status() { return status; }
    public int roundNo() { return roundNo; }
    public long version() { return version; }
}
