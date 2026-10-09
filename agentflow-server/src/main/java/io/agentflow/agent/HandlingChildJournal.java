package io.agentflow.agent;

import io.agentflow.agent.mapper.ExpenseAgentMapper;
import io.agentflow.mybatis.SqlRows;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抽取执行和人工复核通过原绑定更新办理日志，不依赖浏览器仍然打开。
 * @author owlzhangfq@gmail.com
 */
@Service
public class HandlingChildJournal {
    private final ExpenseAgentMapper mapper;
    private final ExpenseHandlingJournal journal;
    /** 只依赖关联持久层和日志，避免反向调用票据用例。 */
    public HandlingChildJournal(ExpenseAgentMapper mapper, ExpenseHandlingJournal journal) { this.mapper = mapper; this.journal = journal; }
    /** 原抽取事务和办理结果共同提交，失败及放弃也保留原任务号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invoice(InvoiceExtractionRun run) {
        var c = run.context(); var s = run.state();
        SqlRows.map(mapper.childLinks(c.tenantId(), c.id().toString(), "INVOICE_EXTRACTION"), row -> {
            journal.recordFor(c.tenantId(), UUID.fromString(row.getString("report_id")), UUID.fromString(row.getString("handling_id")),
                    ExpenseHandlingTask.Tool.INVOICE_EXTRACTION, c.id(), s.version(), s.status().name(),
                    row.getLong("application_version"), row.getLong("financial_version"), c.input(), c.createdAt());
            return Boolean.TRUE;
        });
    }
}
