package io.agentflow.agent;

import io.agentflow.agent.mapper.HandlingReadMapper;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.mybatis.SqlRows;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 外呼前提交原工具步骤；失败和恢复复用原身份，业务回执与办理登记共同提交。
 * @author owlzhangfq@gmail.com
 */
@Service
public class HandlingReadService {
    private final ExpenseHandlingService handling;
    private final ExpenseReportRepository reports;
    private final CurrentActor actors;
    private final HandlingReadMapper mapper;
    private final JsonUtil json;
    private final io.agentflow.finance.FinanceGatewayConfiguration finance;
    private final TransactionTemplate transactions;
    /** 显式短事务使外部查询永远不占用数据库事务。 */
    public HandlingReadService(ExpenseHandlingService handling, ExpenseReportRepository reports, CurrentActor actors,
            HandlingReadMapper mapper, JsonUtil json, PlatformTransactionManager manager, io.agentflow.finance.FinanceGatewayConfiguration finance) {
        this.handling = handling; this.reports = reports; this.actors = actors; this.mapper = mapper; this.json = json;
        this.transactions = new TransactionTemplate(manager); this.finance = finance;
    }
    /** 同一请求键始终对应原输入；成功、已读取和失败三种恢复均可辨认。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(UUID reportId, UUID taskId, String requestKey, ExpenseHandlingController.Inspect input) {
        return prepareId(reportId, taskId, identity(taskId, requestKey), input);
    }
    private UUID identity(UUID taskId, String requestKey) {
        var actor = actors.actor();
        return UUID.nameUUIDFromBytes((actor.tenantId() + "\n" + actor.userId() + "\n" + taskId + "\n" + requestKey).getBytes(StandardCharsets.UTF_8));
    }
    private Prepared prepareId(UUID reportId, UUID taskId, UUID id, ExpenseHandlingController.Inspect input) {
        var actor = actors.actor();
        String tenant = actor.tenantId();
        String digest = AssistConfiguration.digest(json.write(input));
        String authorization = AssistConfiguration.digest(tenant + "\n" + actor.userId() + "\n" + actor.roles().stream().sorted().toList());
        var reserved = transactions.execute(status -> {
            handling.authorize(reportId); reports.lock(tenant, reportId);
            var current = find(tenant, id);
            if (current != null) {
                current.requireMatches(digest, authorization);
                var original = input(tenant, id);
                var next = current.retry(Instant.now());
                if (next != current) { requireOriginal(reportId, taskId, original); save(tenant, next, current.version()); }
                return new Reserved(next, original);
            }
            var task = handling.requireReadable(reportId, taskId, input.expectedVersion());
            if (mapper.list(tenant, taskId.toString()).size() >= ExpenseHandlingTask.MAX_STEPS) {
                throw new DomainException("AGENT_HANDLING_CLOSED", "Handling read budget exhausted");
            }
            var created = HandlingReadExecution.start(id, digest, authorization, Instant.now());
            var original = new Original(taskId, input, task.state().applicationVersion(), task.state().financialVersion(), target(input.tool(), tenant));
            mapper.insert(tenant, id.toString(), taskId.toString(), json.write(original), json.write(created)); append(tenant, created);
            return new Reserved(created, original);
        });
        var execution = reserved.execution();
        if (execution.status() == HandlingReadExecution.Status.RECORDED || execution.status() == HandlingReadExecution.Status.PREPARED) return new Prepared(id);
        try {
            // 输入与权限已提交；恢复时只更新办理的并发版本，原业务双版本仍由办理聚合约束。
            var task = requireOriginal(reportId, taskId, reserved.original());
            var value = handling.prepare(reportId, taskId, task.state().version(), input.tool(), input.referenceId(), input.lineNo());
            transactions.executeWithoutResult(status -> save(tenant, execution.prepared(json.write(value), Instant.now()), execution.version()));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> {
                var current = find(tenant, id);
                if (current != null && current.version() == execution.version() && current.leaseUntil().isAfter(Instant.now())) {
                    save(tenant, current.failed(failure instanceof DomainException domain ? domain.code() : "EXTERNAL_READ_FAILED", Instant.now()), current.version());
                }
            });
            throw failure;
        }
        return new Prepared(id);
    }
    /** 由幂等执行器的原写事务调用，成功回执与日志不能部分提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseHandlingService.ToolReceipt record(UUID reportId, Prepared prepared) {
        handling.authorize(reportId); var tenant = actors.actor().tenantId(); reports.lock(tenant, reportId);
        var execution = find(tenant, prepared.id());
        if (execution == null) throw new DomainException("NOT_FOUND", "Handling read not found");
        if (execution.status() == HandlingReadExecution.Status.RECORDED) return json.read(execution.receiptJson(), ExpenseHandlingService.ToolReceipt.class);
        var receipt = handling.record(reportId, json.read(execution.preparedJson(), ExpenseHandlingService.Prepared.class));
        save(tenant, execution.recorded(json.write(receipt)), execution.version()); return receipt;
    }
    /** 自动编排也使用原工具执行身份，不能绕过持久读取。 */
    @Transactional(propagation = Propagation.NEVER)
    public ExpenseHandlingService.ToolReceipt execute(UUID reportId, UUID taskId, String key, ExpenseHandlingController.Inspect input) {
        UUID id = identity(taskId, key);
        var original = transactions.execute(status -> input(actors.actor().tenantId(), id));
        if (original != null && (original.input().tool() != input.tool() || !java.util.Objects.equals(original.input().lineNo(), input.lineNo())
                || !java.util.Objects.equals(original.input().referenceId(), input.referenceId()))) throw new DomainException("IDEMPOTENCY_CONFLICT", "Original agent tool changed");
        var prepared = prepareId(reportId, taskId, id, original == null ? input : original.input());
        return transactions.execute(status -> record(reportId, prepared));
    }
    /** 浏览器刷新后可按原步骤继续，不要求猜测或重建最初的请求键。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared resume(UUID reportId, UUID taskId, UUID readId, long expectedVersion) {
        handling.authorizeTask(reportId, taskId);
        var original = transactions.execute(status -> {
            var state = find(actors.actor().tenantId(), readId);
            var value = input(actors.actor().tenantId(), readId);
            if (state == null || value == null || !value.taskId().equals(taskId)) throw new DomainException("NOT_FOUND", "Handling read not found");
            if (state.version() != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Handling read changed");
            return value;
        });
        return prepareId(reportId, taskId, readId, original.input());
    }
    /** 本人可检查调用前输入摘要和失败位置；不暴露授权摘要或来源正文。 */
    @Transactional(readOnly = true)
    public List<View> list(UUID reportId, UUID taskId) {
        handling.authorizeTask(reportId, taskId);
        return SqlRows.map(mapper.list(actors.actor().tenantId(), taskId.toString()), row -> {
            var state = json.read(row.getString("state_json"), HandlingReadExecution.class);
            var input = json.read(row.getString("input_json"), Original.class);
            return new View(state.id(), input.input(), state.inputDigest(), state.version(), state.status(), state.failureCode());
        });
    }
    private HandlingReadExecution find(String tenant, UUID id) {
        return SqlRows.map(mapper.lock(tenant, id.toString()), row -> json.read(row.getString("state_json"), HandlingReadExecution.class)).stream().findFirst().orElse(null);
    }
    private Original input(String tenant, UUID id) {
        return SqlRows.map(mapper.lock(tenant, id.toString()), row -> json.read(row.getString("input_json"), Original.class)).stream().findFirst().orElse(null);
    }
    private ExpenseHandlingTask requireOriginal(UUID reportId, UUID taskId, Original original) {
        var task = handling.requireReadable(reportId, taskId, null);
        if (!java.util.Objects.equals(original.targetDigest(), target(original.input().tool(), actors.actor().tenantId()))
                || !taskId.equals(original.taskId()) || task.state().applicationVersion() != original.applicationVersion() || task.state().financialVersion() != original.financialVersion()) {
            throw new DomainException("AGENT_INPUT_CHANGED", "Original read versions changed");
        }
        return task;
    }
    private String target(ExpenseHandlingService.ReadTool tool, String tenant) {
        return tool == ExpenseHandlingService.ReadTool.POLICY ? finance.destination(tenant).map(value -> value.digest(tenant)).orElse(null) : null;
    }
    private void save(String tenant, HandlingReadExecution value, long previous) {
        if (mapper.update(tenant, value.id().toString(), previous, value.version(), json.write(value)) != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Handling read changed");
        append(tenant, value);
    }
    private void append(String tenant, HandlingReadExecution value) { mapper.append(tenant, value.id().toString(), value.version(), json.write(value)); }
    /**
     * 内部准备凭据只有持久身份，不能从 HTTP 反序列化。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(UUID id) { }
    /**
     * 原业务双版本不能随办理日志或后来的保存自动更新。
     * @author owlzhangfq@gmail.com
     */
    private record Original(UUID taskId, ExpenseHandlingController.Inspect input, long applicationVersion, long financialVersion, String targetDigest) { }
    /**
     * 领取返回原输入，恢复不读取另一轮单据内容。
     * @author owlzhangfq@gmail.com
     */
    private record Reserved(HandlingReadExecution execution, Original original) { }
    /**
     * 原工具参数用于同一步骤恢复，不能更换输入。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record View(UUID id, ExpenseHandlingController.Inspect input, String inputDigest, long version,
            HandlingReadExecution.Status status, String failureCode) { }
}
