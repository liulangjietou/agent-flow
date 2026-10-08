package io.agentflow.onboarding;

import io.agentflow.approval.history.JdbcSubmissionHistoryGapQuery;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.onboarding.mapper.FirstWorkflowReadAdapterMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 按版本聚合已有轮次，避免其他租户、旧版本或当前申请状态冒充引导完成证据。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcFirstWorkflowReadAdapter implements FirstWorkflowReadPort {

    private final FirstWorkflowReadAdapterMapper sqlMapper;
    private final JdbcSubmissionHistoryGapQuery historyGaps;

    /** 使用审批数据源查询事实，不修改业务数据或初始化账号。 */
    public JdbcFirstWorkflowReadAdapter(
            FirstWorkflowReadAdapterMapper sqlMapper, JdbcSubmissionHistoryGapQuery historyGaps) {
        this.sqlMapper = sqlMapper;
        this.historyGaps = historyGaps;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report read(String tenantId, UUID definitionId, Instant checkedAt) {

        List<Definition> definitions =
                definitionId == null
                        ? SqlRows.map(
                                sqlMapper.readQuery(new Object[] {tenantId}), this::definition)
                        : SqlRows.map(
                                sqlMapper.readQuery2(
                                        new Object[] {tenantId, definitionId.toString()}),
                                this::definition);
        if (definitions.isEmpty()) {
            if (definitionId != null)
                throw new DomainException("NOT_FOUND", "Process definition was not found");
            return new Report(checkedAt, null, 0, 0, 0, null, null);
        }
        Definition definition = definitions.get(0);
        if (definition.version() == 0)
            return new Report(checkedAt, definition, 0, 0, 0, null, null);
        Object[] parameters = {tenantId, definition.key(), definition.version()};
        long submitted = SqlRows.single(sqlMapper.read(parameters));
        long approved = SqlRows.single(sqlMapper.read2(parameters));
        long unrecorded = historyGaps.count(tenantId, definition.key(), definition.version());
        return new Report(
                checkedAt,
                definition,
                submitted,
                approved,
                unrecorded,
                evidence(parameters, false),
                evidence(parameters, true));
    }

    private Definition definition(SqlRow row) {
        return new Definition(UUID.fromString(row.getString("id")), row.getString("process_key"), row.getString("name"),
                row.getLong("version"), row.getString("status"));
    }

    private Evidence evidence(Object[] parameters, boolean approved) {
        var evidence =
                SqlRows.map(
                        sqlMapper.evidenceQuery(approved, parameters),
                        row -> {
                            var completed = row.getTimestamp("completed_at");
                            return new Evidence(
                                    row.getString("application_id"),
                                    row.getString("business_no"),
                                    row.getString("title"),
                                    row.getInt("round_no"),
                                    row.getString("status"),
                                    row.getTimestamp("submitted_at").toInstant(),
                                    completed == null ? null : completed.toInstant());
                        });
        return evidence.isEmpty() ? null : evidence.get(0);
    }
}
