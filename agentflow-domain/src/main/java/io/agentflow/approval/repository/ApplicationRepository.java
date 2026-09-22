package io.agentflow.approval.repository;

import io.agentflow.approval.model.Application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 审批申请聚合仓储端口。
 * @author owlzhangfq@gmail.com
 */
public interface ApplicationRepository {
    /** 保存新聚合。 */
    Application save(Application application);
    /** 按租户和标识读取聚合。 */
    Optional<Application> findById(String tenantId, UUID id);
    /** 按租户和业务单号读取聚合。 */
    Optional<Application> findByBusinessNo(String tenantId, String businessNo);
    /** 保存带乐观锁的聚合。 */
    Application update(Application application, long expectedVersion);
    /** 返回当前租户的申请列表。 */
    List<Application> findAll(String tenantId);
}
