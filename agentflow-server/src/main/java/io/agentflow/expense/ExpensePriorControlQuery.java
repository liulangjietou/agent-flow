package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原轮次额度依据沿用完整费用明细授权，管理员不获得额外敏感读取权限。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePriorControlQuery {
    private final ExpenseDraftService expenses;
    private final JdbcExpensePriorControlRepository controls;
    private final CurrentActor actors;

    /** 复用既有参与关系和节点字段投影，不另建角色豁免。 */
    public ExpensePriorControlQuery(ExpenseDraftService expenses, JdbcExpensePriorControlRepository controls, CurrentActor actors) {
        this.expenses = expenses; this.controls = controls; this.actors = actors;
    }

    /** 没有记录的历史轮次明确返回未知，不能把没有检查等同于未超额。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID reportId, int roundNo) {
        var expense = expenses.read(reportId, roundNo);
        var snapshot = controls.find(actors.actor().tenantId(), reportId, roundNo).orElse(null);
        if (snapshot != null && !snapshot.applicationId().equals(expense.applicationId())) throw new IllegalStateException("Prior control application binding is inconsistent");
        return new View(reportId, expense.applicationId(), roundNo, snapshot == null ? Status.NOT_RECORDED : Status.RECORDED,
                snapshot == null ? null : snapshot.requiresApproval(), snapshot);
    }

    /**
     * 原提交是否保存本轮额度控制，历史不补造通过结论。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_RECORDED, RECORDED }

    /**
     * 明确序列化缺少的原依据；仅在费用明细完整授权后返回正文。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, Status status, Boolean requiresApproval, ExpensePriorControlSnapshot details) { }
}
