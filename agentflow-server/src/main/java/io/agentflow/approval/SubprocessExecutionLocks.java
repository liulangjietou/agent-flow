package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.common.DomainException;
import io.agentflow.definition.SubprocessPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同一调用树以根申请串行化推进；不可变来源链决定从根到叶的锁顺序。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessExecutionLocks {
    public static final String PARENT_CHANGED = "SUBPROCESS_PARENT_CHANGED";
    private final ApplicationRepository applications;
    private final SubprocessCallRepository calls;
    private final RuntimeService runtime;

    /** 调用来源属于业务事实，不依靠当前引擎执行树推断申请归属。 */
    public SubprocessExecutionLocks(ApplicationRepository applications, SubprocessCallRepository calls, RuntimeService runtime) {
        this.applications = applications;
        this.calls = calls;
        this.runtime = runtime;
    }

    /** 调用方先完成资源授权；锁后返回最新申请，并核对祖先仍属于同一在审轮次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Application lock(Application initial) {
        var path = lockPath(initial);
        path.requireActive();
        return path.application();
    }

    /** 财务收尾同时使用整条锁定路径，在引擎推进前锁定祖先所关联的业务资源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedPath lockPath(Application initial) {
        var ancestry = ancestry(initial.tenantId(), initial.id());
        var locked = new LinkedHashMap<UUID, Application>();
        for (int index = ancestry.size() - 1; index >= 0; index--) {
            UUID id = ancestry.get(index).parentApplicationId();
            locked.put(id, lock(initial.tenantId(), id));
        }
        locked.put(initial.id(), lock(initial.tenantId(), initial.id()));
        var state = AncestorState.ACTIVE;
        for (var call : ancestry) {
            var parent = locked.get(call.parentApplicationId());
            var child = locked.get(call.childApplicationId());
            if (parent.status() != ApplicationStatus.IN_APPROVAL || parent.roundNo() != call.parentRoundNo()
                    || !call.parentRuntimeDefinitionId().equals(parent.runtimeDefinitionId())
                    || child.roundNo() != SubprocessCall.CHILD_ROUND
                    || !call.childRuntimeDefinitionId().equals(child.runtimeDefinitionId())) {
                state = AncestorState.STALE;
                break;
            }
            var instance = runtime.createProcessInstanceQuery().processInstanceId(call.parentProcessInstanceId())
                    .variableValueEquals("tenantId", parent.tenantId()).variableValueEquals("applicationId", parent.id().toString())
                    .variableValueEquals("roundNo", parent.roundNo()).singleResult();
            if (instance == null) { state = AncestorState.STALE; break; }
            if (instance.isSuspended()) state = AncestorState.PAUSED;
        }
        return new LockedPath(List.copyOf(locked.values()), state);
    }

    /** 来源链从当前申请向根排列；关系只追加，因此锁等待不会改变锁顺序。 */
    public List<SubprocessCall> ancestry(String tenant, UUID applicationId) {
        var result = new ArrayList<SubprocessCall>();
        var seen = new HashSet<UUID>();
        UUID current = applicationId;
        while (true) {
            if (!seen.add(current) || result.size() > SubprocessPolicy.MAX_CALL_DEPTH) {
                throw new DomainException("SUBPROCESS_RELATION_INVALID", "The subprocess origin chain is invalid");
            }
            var origin = calls.findByChild(tenant, current);
            if (origin.isEmpty()) return List.copyOf(result);
            result.add(origin.get());
            current = origin.get().parentApplicationId();
        }
    }

    /** 子申请的输入和生命周期跟随父调用，不允许申请人将其作为独立根申请重提或撤回。 */
    public void requireRoot(Application application) {
        if (calls.findByChild(application.tenantId(), application.id()).isPresent()) {
            throw new DomainException("SUBPROCESS_PARENT_CONTROL_REQUIRED", "Subprocess lifecycle must be controlled through its root application");
        }
    }

    private Application lock(String tenant, UUID id) {
        return applications.lockById(tenant, id).orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
    }

    /** 暂停及过期是异步消费的正常结果，不通过事务代理异常把收件事务标为只能回滚。 @author owlzhangfq@gmail.com */
    public enum AncestorState { ACTIVE, PAUSED, STALE }

    /** 锁后事实同时供人工入口的拒绝规则和后台消费的状态分支使用。 @author owlzhangfq@gmail.com */
    public record LockedPath(List<Application> applications, AncestorState ancestors) {
        /** 路径最后一项始终是调用方指定的申请。 */
        public Application application() { return applications.get(applications.size() - 1); }
        /** 人工动作不能越过已暂停或已结束的父轮次。 */
        public void requireActive() {
            if (ancestors != AncestorState.ACTIVE) throw new DomainException(PARENT_CHANGED, "The subprocess no longer belongs to an active parent round");
        }
    }
}
