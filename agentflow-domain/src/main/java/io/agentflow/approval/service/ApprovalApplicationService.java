package io.agentflow.approval.service;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;

import java.util.Map;
import java.util.UUID;

/**
 * 审批申请用例编排服务，负责聚合、运行时和仓储之间的事务协作。
 * @author owlzhangfq@gmail.com
 */
public class ApprovalApplicationService {
    private final ApplicationRepository repository;
    private final ProcessRuntimePort processRuntime;

    /** 创建应用服务。 */
    public ApprovalApplicationService(ApplicationRepository repository, ProcessRuntimePort processRuntime) {
        this.repository = repository;
        this.processRuntime = processRuntime;
    }

    /** 创建草稿并拒绝租户外重复业务单号。 */
    public Application create(String tenantId, String businessNo, String processKey, long definitionVersion,
                              String userId, String title, Map<String, Object> payload) {
        if (repository.findByBusinessNo(tenantId, businessNo).isPresent()) {
            throw new DomainException("BUSINESS_NO_EXISTS", "Business number already exists");
        }
        Application application = Application.draft(UUID.randomUUID(), tenantId, businessNo, processKey,
                definitionVersion, userId, title, payload);
        return repository.save(application);
    }

    /** 提交申请；流程启动失败时由上层事务回滚本地状态。 */
    public Application submit(String tenantId, UUID id, long expectedVersion) {
        Application application = repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        application.submit(expectedVersion);
        processRuntime.start(new ProcessRuntimePort.StartProcessCommand(tenantId, id, application.processKey(),
                application.definitionVersion(), application.roundNo(), application.businessNo(), application.payload()));
        return repository.update(application, expectedVersion);
    }

    /** 获取当前租户申请。 */
    public Application get(String tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
    }

    /** 按租户读取申请，访问主体规则由接口适配层根据参与关系判断。 */
    public Application find(String tenantId, UUID id) {
        return repository.findById(tenantId, id).orElse(null);
    }
}
