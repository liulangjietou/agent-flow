package io.agentflow.agent;

import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.CurrentActor;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每次行动恢复原登录，线程退出总是清理；不存在后台伪造的申请人角色。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExpenseAgentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseAgentWorker.class);
    private final ExpenseAgentRepository repository;
    private final DeferredActorAuthentication authentication;
    private final CurrentActor actors;
    private final ExpenseAgentService service;
    /** 独立调度与业务执行分开，测试可明确推进一次队列。 */
    public ExpenseAgentWorker(ExpenseAgentRepository repository, DeferredActorAuthentication authentication, CurrentActor actors, ExpenseAgentService service) {
        this.repository = repository; this.authentication = authentication; this.actors = actors; this.service = service;
    }
    /** 有界轮询，每次持久领取保证跨实例不会重复执行同一模型步骤。 */
    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        for (var candidate : repository.candidates(Instant.now())) {
            try {
                repository.checked(candidate);
                var stored = repository.find(candidate.tenant(), candidate.taskId(), false).orElse(null);
                if (stored == null) continue;
                var c = stored.run().context();
                var actor = authentication.resolve(stored.login(), c.tenantId(), c.ownerId(), Instant.now()).orElse(null);
                if (actor == null) { service.authenticationLost(c.tenantId(), c.taskId()); continue; }
                actors.set(actor); service.advance(candidate.taskId());
            } catch (RuntimeException failure) {
                LOG.error("Expense agent polling failed, errorCode={}, taskId={}, exceptionType={}", "AGENT_POLL_FAILED", candidate.taskId(), failure.getClass().getSimpleName());
            } finally { actors.clear(); }
        }
    }
}
