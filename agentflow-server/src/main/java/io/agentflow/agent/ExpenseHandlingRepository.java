package io.agentflow.agent;

import io.agentflow.agent.mapper.ExpenseHandlingMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 办理聚合与原业务命令共用事务，活动唯一键保证同单只有一个办理记录。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseHandlingRepository {
    private final ExpenseHandlingMapper mapper;
    private final JsonUtil json;
    /** 原上下文和每次状态转换都保留，不修改旧步骤来源。 */
    public ExpenseHandlingRepository(ExpenseHandlingMapper mapper, JsonUtil json) { this.mapper = mapper; this.json = json; }
    /** 本人显式开始后创建，自动轮询不能偷偷建立办理记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseHandlingTask task) {
        var c = task.context();
        mapper.insert(c.tenantId(), c.id().toString(), c.reportId().toString(), c.applicationId().toString(), c.ownerId(), json.write(c), json.write(task.state()));
        append(task);
    }
    /** 乐观版本和活动标识一起更新；轨迹追加失败使业务命令一起回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseHandlingTask task, long previous) {
        var c = task.context();
        if (mapper.update(c.tenantId(), c.id().toString(), previous, task.state().version(), task.active() ? c.reportId().toString() : null,
                json.write(task.state())) != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Expense handling record changed");
        append(task);
    }
    /** 仅返回当前单据的最近二十次办理。 */
    public List<ExpenseHandlingTask> list(String tenant, UUID reportId) { return decode(mapper.list(tenant, reportId.toString())); }
    /** 历史读取仍由上层核对原申请人。 */
    public Optional<ExpenseHandlingTask> find(String tenant, UUID id) { return decode(mapper.find(tenant, id.toString())).stream().findFirst(); }
    /** 获取活动记录的锁；业务锁先于办理锁，办理服务不反向领取子任务锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ExpenseHandlingTask> active(String tenant, UUID reportId) { return decode(mapper.active(tenant, reportId.toString())).stream().findFirst(); }
    private List<ExpenseHandlingTask> decode(List<io.agentflow.mybatis.SqlRow> rows) {
        return SqlRows.map(rows, row -> ExpenseHandlingTask.restore(json.read(row.getString("context_json"), ExpenseHandlingTask.Context.class),
                json.read(row.getString("state_json"), ExpenseHandlingTask.State.class)));
    }
    private void append(ExpenseHandlingTask task) { mapper.append(task.context().tenantId(), task.context().id().toString(), task.state().version(), json.write(task.state())); }
}
