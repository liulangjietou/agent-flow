package io.agentflow.approval.history;

import io.agentflow.approval.model.SubmissionRound;

import java.util.List;
import java.util.UUID;

/**
 * 仅追加操作审计的查询端口，不把快照或引擎结束状态转成操作记录。
 * @author owlzhangfq@gmail.com
 */
public interface AuditHistoryPort {
    /** 只读指定租户申请及已核实任务的审计；不扫描租户全部事件。 */
    List<HistoryEvent> read(String tenantId, UUID applicationId, ProcessHistoryPort.ProcessHistory processHistory,
                            List<SubmissionRound> rounds);
}
