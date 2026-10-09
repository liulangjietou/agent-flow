package io.agentflow.approval.workspace;


import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.approval.workspace.mapper.WorkspaceReadAdapterMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 通过申请归属与真实审计操作人查询个人读模型；不扫描引擎内部表。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcWorkspaceReadAdapter implements WorkspaceReadPort, ApplicationParticipantPort {
    private final WorkspaceReadAdapterMapper sqlMapper;
    private final JsonUtil json;

    /** 注入业务存储与统一 JSON 工具。 */
    public JdbcWorkspaceReadAdapter(WorkspaceReadAdapterMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    public List<ApplicationItem> applications(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));

        if (query.drafts() || !query.status().isEmpty()) {
            parameters.add(query.drafts() ? "DRAFT" : query.status());
        }
        appendText(parameters, query.text());
        appendPosition(parameters, query);
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.applicationsQuery(
                        (query.drafts() || !query.status().isEmpty()),
                        ((query.text()).isEmpty()),
                        (query.beforeTime() == null),
                        parameters.toArray()),
                row ->
                        new ApplicationItem(
                                UUID.fromString(row.getString("id")),
                                row.getString("business_no"),
                                row.getString("title"),
                                row.getString("process_key"),
                                row.getLong("definition_version"),
                                row.getString("status"),
                                row.getInt("round_no"),
                                row.getTimestamp("created_at").toInstant(),
                                row.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public List<HandledItem> handled(Actor actor, Query query) {
        var parameters = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));

        if (!query.action().isEmpty()) {
            parameters.add(query.action());
        }
        appendText(parameters, query.text());
        appendPosition(parameters, query);
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.handledQuery(
                        (!query.action().isEmpty()),
                        ((query.text()).isEmpty()),
                        (query.beforeTime() == null),
                        parameters.toArray()),
                row -> {
                    Map<String, Object> payload = json.map(row.getString("payload_json"));
                    Number round = payload.get("roundNo") instanceof Number value ? value : null;
                    return new HandledItem(
                            UUID.fromString(row.getString("id")),
                            row.getString("aggregate_id"),
                            UUID.fromString(row.getString("application_id")),
                            row.getString("business_no"),
                            row.getString("title"),
                            row.getString("process_key"),
                            row.getLong("definition_version"),
                            row.getString("status"),
                            row.getString("action"),
                            row.getTimestamp("occurred_at").toInstant(),
                            round == null ? null : round.intValue(),
                            string(payload, "nodeName"),
                            string(payload, "comment"),
                            string(payload, "targetUser"),
                            string(payload, "currentStatus"));
                });
    }

    @Override
    public boolean isParticipant(String tenantId, UUID applicationId, Actor actor) {
        // 历史办理者不因转交后指派人变化而失去访问；仅真实办理动作建立此关系。
        if (!tenantId.equals(actor.tenantId())) return false;
        return Boolean.TRUE.equals(
                SqlRows.single(
                        sqlMapper.isParticipant(
                                tenantId, applicationId.toString(), actor.userId())));
    }

    /** 评论选人只包含本轮真实办理者，复用建立申请读取关系的动作范围。 */
    public java.util.Set<String> participantsInRound(
            String tenantId, UUID applicationId, int roundNo) {
        var result = new java.util.HashSet<String>();
        sqlMapper
                .participantsInRound(tenantId, applicationId.toString())
                .forEach(
                        row -> {
                            var payload = json.map(row.getString("payload_json"));
                            if (payload.get("roundNo") instanceof Number value
                                    && value.intValue() == roundNo)
                                result.add(row.getString("actor_id"));
                        });
        return java.util.Set.copyOf(result);
    }

    private static void appendText(List<Object> parameters, String text) {
        if (text.isEmpty()) return;
        String pattern =
                "%"
                        + text.toLowerCase(Locale.ROOT)
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%";

        parameters.addAll(List.of(pattern, pattern, pattern));
    }

    private static void appendPosition(List<Object> parameters, Query query) {
        if (query.beforeTime() == null) return;

        parameters.add(Timestamp.from(query.beforeTime()));
        parameters.add(Timestamp.from(query.beforeTime()));
        parameters.add(query.beforeId().toString());
    }

    private static String string(Map<String, Object> values, String key) { return values.get(key) instanceof String value ? value : null; }
}
