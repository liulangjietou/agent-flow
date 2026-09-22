package io.agentflow.approval.service;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 审批申请用例编排服务，负责聚合、运行时和仓储之间的事务协作。
 * @author owlzhangfq@gmail.com
 */
public class ApprovalApplicationService {
    private final ApplicationRepository repository;
    private final ProcessRuntimePort processRuntime;
    private final SubmissionRoundRepository rounds;

    /** 创建应用服务。 */
    public ApprovalApplicationService(ApplicationRepository repository, ProcessRuntimePort processRuntime,
                                      SubmissionRoundRepository rounds) {
        this.repository = repository;
        this.processRuntime = processRuntime;
        this.rounds = rounds;
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
    public Application submit(String tenantId, UUID id, long expectedVersion, String submittedBy) {
        Application application = repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        application.submit(expectedVersion);
        ProcessRuntimePort.StartedProcess started = processRuntime.start(new ProcessRuntimePort.StartProcessCommand(tenantId, id, application.processKey(),
                application.definitionVersion(), application.roundNo(), application.businessNo(), application.payload()));
        repository.update(application, expectedVersion);
        rounds.append(SubmissionRound.submitted(application, started.processInstanceId(), submittedBy, Instant.now()));
        return application;
    }

    /** 补正申请内容并使用显式版本保存，历史轮次快照不受影响。 */
    public Application revise(String tenantId, UUID id, long expectedVersion, String title, Map<String, Object> payload) {
        Application application = get(tenantId, id);
        application.revise(expectedVersion, title, payload);
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
