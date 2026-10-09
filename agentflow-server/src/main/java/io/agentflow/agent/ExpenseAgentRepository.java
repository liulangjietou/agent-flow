package io.agentflow.agent;

import io.agentflow.agent.mapper.ExpenseAgentMapper;
import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排聚合持久层；认证引用只在内部读取，不出现在公开运行视图中。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseAgentRepository {
    private final ExpenseAgentMapper mapper;
    private final JsonUtil json;
    /** 原来源、认证和状态分开建模，使用同一受控 JSON 工具。 */
    public ExpenseAgentRepository(ExpenseAgentMapper mapper, JsonUtil json) { this.mapper = mapper; this.json = json; }
    /** 本人授权和初始状态原子保存。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseAgentRun run, DeferredActorAuthentication.LoginReference login) {
        var c = run.context(); mapper.insert(c.tenantId(), c.id().toString(), c.taskId().toString(), c.reportId().toString(),
                json.write(new Authorization(c, login)), json.write(run.state())); append(run);
    }
    /** 读取原授权和完整状态，不自动补跑任何步骤。 */
    public Optional<Stored> find(String tenant, UUID task, boolean lock) {
        return SqlRows.map(mapper.find(tenant, task.toString(), lock), row -> {
            var authorization = json.read(row.getString("context_json"), Authorization.class);
            return new Stored(ExpenseAgentRun.restore(authorization.context(), json.read(row.getString("state_json"), ExpenseAgentRun.State.class)), authorization.login());
        }).stream().findFirst();
    }
    /** 旧版本完成不能覆盖新一轮人工确认。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseAgentRun run, long previous) {
        var c = run.context(); var s = run.state();
        if (mapper.update(c.tenantId(), c.id().toString(), previous, s.version(), s.status().name(), s.leaseUntil(), json.write(s)) != 1) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Expense agent changed");
        }
        append(run);
    }
    /** 每次模型执行的输入在发送前保存，历史观测不会被后续结果覆盖。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void modelCall(ExpenseAgentRun run, String input) {
        var c = run.context(); var step = run.last();
        mapper.modelCall(c.tenantId(), step.id().toString(), c.id().toString(), c.reportId().toString(), input, step.createdAt());
    }
    /** 独立调度器只读取十条候选，不占用其他模型任务线程。 */
    public List<Candidate> candidates(Instant now) { return SqlRows.map(mapper.candidates(now), row -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("handling_id")))); }
    /** 调整轮询位置不产生虚假的业务状态转换。 */
    public void checked(Candidate candidate) { mapper.checked(candidate.tenant(), candidate.taskId().toString(), Instant.now()); }
    private void append(ExpenseAgentRun run) { mapper.append(run.context().tenantId(), run.context().id().toString(), run.state().version(), json.write(run.state())); }
    /**
     * 持久授权不包含访问令牌或客户端可使用的会话编号。
     * @author owlzhangfq@gmail.com
     */
    private record Authorization(ExpenseAgentRun.Context context, DeferredActorAuthentication.LoginReference login) { }
    /**
     * 仅服务端使用的原聚合及原登录引用。
     * @author owlzhangfq@gmail.com
     */
    public record Stored(ExpenseAgentRun run, DeferredActorAuthentication.LoginReference login) { }
    /**
     * 调度仅携带租户和原办理编号，不伪造用户主体。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenant, UUID taskId) { }
}
