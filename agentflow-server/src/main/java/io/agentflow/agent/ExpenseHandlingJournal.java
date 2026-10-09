package io.agentflow.agent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原费用用例只报告已提交的步骤事实，办理记录不反向调用费用或模型，避免两套业务状态机。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseHandlingJournal {
    private final ExpenseHandlingRepository repository;
    private final io.agentflow.common.JsonUtil json;
    /** 没有本人创建的办理记录时，旧接口行为保持原样。 */
    public ExpenseHandlingJournal(ExpenseHandlingRepository repository, io.agentflow.common.JsonUtil json) { this.repository = repository; this.json = json; }

    /** 在原事务记录步骤，重复状态不新增步骤或版本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String tenant, UUID reportId, ExpenseHandlingTask.Tool tool, UUID reference, long sourceVersion, String outcome,
            long applicationVersion, long financialVersion, Object input, Instant occurredAt) {
        recordFor(tenant, reportId, null, tool, reference, sourceVersion, outcome, applicationVersion, financialVersion, input, occurredAt);
    }
    /** 显式绑定的子任务只更新原办理，不能落入后来创建的新办理记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFor(String tenant, UUID reportId, UUID taskId, ExpenseHandlingTask.Tool tool, UUID reference, long sourceVersion, String outcome,
            long applicationVersion, long financialVersion, Object input, Instant occurredAt) {
        var task = repository.active(tenant, reportId).orElse(null);
        if (task == null || taskId != null && !task.context().id().equals(taskId)) return;
        long previous = task.state().version();
        if (task.observe(tool, reference, sourceVersion, outcome, applicationVersion, financialVersion, AssistConfiguration.digest(json.write(input)),
                occurredAt, Instant.now().truncatedTo(ChronoUnit.MILLIS))) repository.save(task, previous);
    }
}
