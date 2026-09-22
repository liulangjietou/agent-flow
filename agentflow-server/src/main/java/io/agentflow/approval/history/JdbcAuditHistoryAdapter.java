package io.agentflow.approval.history;

import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 申请范围内查询真实追加审计；旧任务事件仅通过已核实的历史任务关联。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAuditHistoryAdapter implements AuditHistoryPort {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 注入审计存储与统一 JSON 解码器。 */
    public JdbcAuditHistoryAdapter(JdbcTemplate jdbc, JsonUtil json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<HistoryEvent> read(String tenantId, UUID applicationId, ProcessHistoryPort.ProcessHistory history,
                                   List<SubmissionRound> rounds) {
        List<Object> parameters = new ArrayList<>(List.of(tenantId, applicationId.toString(), applicationId.toString()));
        String legacyTasks = "";
        if (!history.tasks().isEmpty()) {
            legacyTasks = " OR (application_id IS NULL AND aggregate_type='Task' AND aggregate_id IN ("
                    + String.join(",", Collections.nCopies(history.tasks().size(), "?")) + "))";
            parameters.addAll(history.tasks().keySet());
        }
        String sql = """
                SELECT * FROM audit_event WHERE tenant_id=? AND (
                  (application_id=? AND aggregate_type IN ('Application','Task'))
                  OR (application_id IS NULL AND aggregate_type='Application' AND aggregate_id=?)
                """ + legacyTasks + ")";
        return jdbc.query(sql, (row, index) -> event(row, applicationId, history, rounds), parameters.toArray())
                .stream().filter(Objects::nonNull).toList();
    }

    private HistoryEvent event(ResultSet row, UUID applicationId, ProcessHistoryPort.ProcessHistory history,
                               List<SubmissionRound> rounds) throws SQLException {
        Map<String, Object> payload = json.map(row.getString("payload_json"));
        String recordedApplication = string(payload, "applicationId");
        if (recordedApplication != null && !applicationId.toString().equals(recordedApplication)) return null;
        boolean taskEvent = "Task".equals(row.getString("aggregate_type"));
        boolean directlyLinked = applicationId.toString().equals(row.getString("application_id"));
        String aggregateId = row.getString("aggregate_id");
        if (!taskEvent && !applicationId.toString().equals(aggregateId)) return null;
        ProcessHistoryPort.TaskBinding task = taskEvent ? history.tasks().get(aggregateId) : null;
        Integer roundNo = integer(payload, "roundNo");
        String processId = string(payload, "processInstanceId");
        if (task != null) {
            if (roundNo != null && roundNo != task.roundNo()
                    || processId != null && !processId.equals(task.processInstanceId())) return null;
            roundNo = task.roundNo();
            processId = task.processInstanceId();
        }
        ProcessHistoryPort.ProcessBinding process = processId == null ? null : history.processes().get(processId);
        if (process != null) {
            if (roundNo != null && roundNo != process.roundNo()) return null;
            roundNo = process.roundNo();
        }
        SubmissionRound snapshot = null;
        if (roundNo != null) {
            int recordedRound = roundNo;
            snapshot = rounds.stream().filter(round -> round.roundNo() == recordedRound).findFirst().orElse(null);
        }
        if (processId != null && snapshot != null && !snapshot.processInstanceId().equals(processId)) return null;
        if (processId != null && process == null && roundNo != null) {
            int recordedRound = roundNo;
            if (history.processes().values().stream().anyMatch(known -> known.roundNo() == recordedRound)) return null;
        }
        // 直接记录的申请审计独立于引擎历史留存；旧任务没有直接关联时不能靠业务号或内容猜测。
        if (!directlyLinked && (taskEvent && task == null
                || processId != null && snapshot == null && process == null)) return null;
        String action = row.getString("action");
        if (action == null) action = string(payload, "action");
        Long definitionVersion = process != null ? process.definitionVersion()
                : snapshot != null ? snapshot.definitionVersion() : null;
        return new HistoryEvent("audit:" + row.getString("event_id"), 0, row.getTimestamp("occurred_at").toInstant(),
                taskEvent ? HistoryEvent.Source.TASK_AUDIT : HistoryEvent.Source.APPLICATION_AUDIT,
                action == null ? "UNKNOWN" : action, row.getLong("aggregate_version"), roundNo,
                string(payload, "actor"), string(payload, "targetUser"), string(payload, "comment"),
                string(payload, "nodeId") != null ? string(payload, "nodeId") : task == null ? null : task.nodeId(),
                string(payload, "nodeName") != null ? string(payload, "nodeName") : task == null ? null : task.nodeName(),
                taskEvent ? "USER_TASK" : null, taskEvent ? aggregateId : null, processId, definitionVersion,
                string(payload, "previousStatus"), string(payload, "currentStatus"));
    }

    private String string(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof String value ? value : null;
    }

    private Integer integer(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof Number value && value.intValue() > 0 && value.doubleValue() == value.intValue()
                ? value.intValue() : null;
    }
}
