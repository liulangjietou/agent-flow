package io.agentflow.notification;

import io.agentflow.common.Actor;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 个人偏好用例不具备代他人修改或关闭站内业务提醒的入口。 @author owlzhangfq@gmail.com */
@Service
public class NotificationPreferencesService {
    private final NotificationPreferencesRepository repository;
    private final NotificationDispatchPlanner dispatches;

    /** 组合本人设置与未发送意向的撤销，不修改审批状态。 */
    public NotificationPreferencesService(NotificationPreferencesRepository repository, NotificationDispatchPlanner dispatches) {
        this.repository = repository; this.dispatches = dispatches;
    }

    /** 默认值读取不创建订阅或历史。 */
    @Transactional(readOnly = true)
    public NotificationPreferences get(Actor actor) { return repository.get(actor.tenantId(), actor.userId()); }

    /** 只有明确版本的本人设置生效，关闭渠道和保存历史同事务提交。 */
    @Transactional
    public NotificationPreferences revise(Actor actor, long expectedVersion, boolean email, boolean enterpriseIm) {
        var previous = get(actor);
        var current = previous.revise(expectedVersion, email, enterpriseIm, Instant.now());
        if (current != previous) {
            repository.save(previous, current);
            dispatches.suppressRevoked(current);
        }
        return current;
    }
}
