package io.agentflow.expense;

import io.agentflow.expense.mapper.ExpenseCorrectionMapper;
import io.agentflow.mybatis.SqlRows;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存补正结果关联，业务权限由申请人用例校验。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class ExpenseCorrectionRepository {
    private final ExpenseCorrectionMapper mapper;

    /** 沿用 MyBatis 与调用方事务。 */
    public ExpenseCorrectionRepository(ExpenseCorrectionMapper mapper) { this.mapper = mapper; }

    /** 唯一键阻止同一建议生成两份补正结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String tenant, ExpenseCorrection value) {
        if (mapper.insert(tenant, value.runId().toString(), value.reportId().toString(), value.applicationId().toString(),
                value.applicationVersion(), value.financialVersion(), value.precheckId().toString(), value.appliedBy(),
                Timestamp.from(value.appliedAt())) != 1) throw new IllegalStateException("Expense correction was not recorded");
    }

    /** 历史回执保留原新版本，即使单据后来再次修改也不覆盖。 */
    public Optional<ExpenseCorrection> find(String tenant, UUID runId) {
        return SqlRows.map(mapper.find(tenant, runId.toString()), row -> new ExpenseCorrection(UUID.fromString(row.getString("run_id")),
                UUID.fromString(row.getString("report_id")), UUID.fromString(row.getString("application_id")),
                row.getLong("application_version"), row.getLong("financial_version"), UUID.fromString(row.getString("precheck_id")),
                row.getString("applied_by"), row.getTimestamp("applied_at").toInstant())).stream().findFirst();
    }
}
