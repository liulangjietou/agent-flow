package io.agentflow.approval.history;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;

/**
 * 复用引导与运营的历史缺口口径；预留首轮不代表提交，不根据现状补造历史快照。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSubmissionHistoryGapQuery {
    private static final String RECORDED_ROUNDS = """
            (SELECT COUNT(*) FROM approval_submission_round r
             WHERE r.tenant_id=a.tenant_id AND r.application_id=a.id AND r.definition_version=a.definition_version)
            """;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 在调用方只读事务中查询，沿用同一快照核对轮次与作废审计。 */
    public JdbcSubmissionHistoryGapQuery(JdbcTemplate jdbc, JsonUtil json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 参数来自已授权且已校验的读入口；空流程标识或 null 版本表示不限定该项。 */
    public long count(String tenantId, String processKey, Long definitionVersion) {
        var parameters = new ArrayList<Object>(List.of(tenantId));
        var scope = new StringBuilder(" WHERE a.tenant_id=?");
        if (!processKey.isEmpty()) { scope.append(" AND a.process_key=?"); parameters.add(processKey); }
        if (definitionVersion != null) { scope.append(" AND a.definition_version=?"); parameters.add(definitionVersion); }
        long missing = jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(0,a.round_no-" + RECORDED_ROUNDS
                + ")),0) FROM approval_application a" + scope + " AND a.status<>'DRAFT'", Long.class, parameters.toArray());
        if (missing == 0) return 0;

        // CANCELLED 既可能来自草稿也可能来自退回/撤回，仅排除有唯一、准确绑定的草稿作废证据。
        // 只读取可能误报的首轮作废审计，避免逐申请查询或加载全量申请正文。
        long cancelledDrafts = jdbc.query("""
                SELECT a.id,e.payload_json,COUNT(*) OVER (PARTITION BY a.id) AS audit_matches
                FROM approval_application a JOIN audit_event e ON e.tenant_id=a.tenant_id
                  AND e.aggregate_type='Application' AND e.aggregate_id=a.id AND e.application_id=a.id
                  AND e.aggregate_version=a.version AND e.action='CANCEL'
                """ + scope + " AND a.status='CANCELLED' AND a.round_no=1 AND " + RECORDED_ROUNDS + "=0",
                result -> {
                    long count = 0;
                    while (result.next()) {
                        if (result.getLong("audit_matches") == 1
                                && cancelledFromDraft(result.getString("id"), result.getString("payload_json"))) count++;
                    }
                    return count;
                }, parameters.toArray());
        return missing - cancelledDrafts;
    }

    private boolean cancelledFromDraft(String applicationId, String payload) {
        try {
            JsonNode event = json.read(payload, JsonNode.class);
            return event != null && applicationId.equals(event.path("applicationId").asText())
                    && "CANCEL".equals(event.path("action").asText())
                    && "DRAFT".equals(event.path("previousStatus").asText())
                    && "CANCELLED".equals(event.path("currentStatus").asText())
                    && event.path("roundNo").isIntegralNumber() && event.path("roundNo").asLong() == 1;
        } catch (DomainException exception) {
            // 旧审计无法解析时保留缺口提示，不把缺少证据解释为从未提交。
            return false;
        }
    }
}
