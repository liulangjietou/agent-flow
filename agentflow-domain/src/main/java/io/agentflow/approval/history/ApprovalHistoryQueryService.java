package io.agentflow.approval.history;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读历史编排，独立于申请聚合的状态变更服务。
 * @author owlzhangfq@gmail.com
 */
public final class ApprovalHistoryQueryService {
    private final SubmissionRoundRepository rounds;
    private final ProcessHistoryPort processHistory;
    private final AuditHistoryPort auditHistory;

    /** 组合轮次事实、引擎历史和实际操作审计。 */
    public ApprovalHistoryQueryService(SubmissionRoundRepository rounds, ProcessHistoryPort processHistory,
                                       AuditHistoryPort auditHistory) {
        this.rounds = rounds;
        this.processHistory = processHistory;
        this.auditHistory = auditHistory;
    }

    /** 上层先按申请详情权限授权；仅对该申请合并历史。 */
    public List<HistoryEvent> read(Application application, boolean timeline) {
        List<SubmissionRound> snapshots = rounds.findAll(application.tenantId(), application.id());
        ProcessHistoryPort.ProcessHistory engine = processHistory.read(application.tenantId(), application.id(), snapshots);
        List<HistoryEvent> audits = auditHistory.read(application.tenantId(), application.id(), engine, snapshots);
        List<HistoryEvent> events = new ArrayList<>(audits);
        if (timeline) {
            events.addAll(engine.events());
            for (SubmissionRound round : snapshots) {
                boolean submittedAuditExists = audits.stream().anyMatch(event -> "SUBMIT".equals(event.action())
                        && Integer.valueOf(round.roundNo()).equals(event.roundNo())
                        && round.processInstanceId().equals(event.processInstanceId()));
                if (!submittedAuditExists) {
                    events.add(new HistoryEvent("snapshot:" + round.roundNo() + ":submitted", 0, round.submittedAt(),
                            HistoryEvent.Source.SUBMISSION_SNAPSHOT, "SUBMIT", null, round.roundNo(), round.submittedBy(),
                            null, null, null, null, null, null, round.processInstanceId(), round.definitionVersion(), null, null));
                }
                if (round.completedAt() != null) {
                    events.add(new HistoryEvent("snapshot:" + round.roundNo() + ":completed", 0, round.completedAt(),
                            HistoryEvent.Source.SUBMISSION_SNAPSHOT, "ROUND_" + round.status().name(), null,
                            round.roundNo(), round.completedBy(), null, round.reason(), null, null, null, null,
                            round.processInstanceId(), round.definitionVersion(), null, null));
                }
            }
        }
        Map<String, HistoryEvent> distinct = new LinkedHashMap<>();
        events.forEach(event -> distinct.putIfAbsent(event.source().name() + ":" + event.id(), event));
        return List.copyOf(distinct.values());
    }
}
