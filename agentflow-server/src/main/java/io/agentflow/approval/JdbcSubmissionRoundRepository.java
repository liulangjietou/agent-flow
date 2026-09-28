package io.agentflow.approval;

import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 轮次快照 JDBC 适配器；内容仅插入，结论通过实例和未结束状态进行条件更新。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSubmissionRoundRepository implements SubmissionRoundRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil jsonUtil;

    /** 使用审批事务共用的数据源保存轮次。 */
    public JdbcSubmissionRoundRepository(JdbcTemplate jdbc, JsonUtil jsonUtil) {
        this.jdbc = jdbc;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public void append(SubmissionRound round) {
        var initiator = round.initiatorContext();
        try {
            jdbc.update("""
                    INSERT INTO approval_submission_round
                    (tenant_id, application_id, round_no, process_instance_id, definition_version,
                     title, payload_json, submitted_by, submitted_at, form_schema_json, initiator_context_json,
                     initiator_legal_entity_name, initiator_department_name, initiator_position_name, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_APPROVAL')
                    """, round.tenantId(), round.applicationId().toString(), round.roundNo(), round.processInstanceId(),
                    round.definitionVersion(), round.title(), jsonUtil.write(round.payload()), round.submittedBy(),
                    round.submittedAt().atOffset(ZoneOffset.UTC), round.formSchema() == null ? null : jsonUtil.write(round.formSchema()),
                    initiator == null ? null : jsonUtil.write(initiator), initiator == null ? null : initiator.legalEntityName(),
                    initiator == null ? null : initiator.departmentName(), initiator == null ? null : initiator.positionName());
        } catch (DuplicateKeyException exception) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round already exists");
        }
    }

    @Override
    public List<SubmissionRound> findAll(String tenantId, UUID applicationId) {
        return jdbc.query("""
                SELECT * FROM approval_submission_round WHERE tenant_id=? AND application_id=? ORDER BY round_no
                """, this::map, tenantId, applicationId.toString());
    }

    @Override
    public Optional<SubmissionRound> findByRound(String tenantId, UUID applicationId, int roundNo) {
        return jdbc.query("""
                SELECT * FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?
                """, this::map, tenantId, applicationId.toString(), roundNo).stream().findFirst();
    }

    @Override
    public void complete(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                         SubmissionRound.Status status, String reason, String completedBy, Instant completedAt) {
        status.requireTerminal();
        int updated = jdbc.update("""
                UPDATE approval_submission_round SET status=?, reason=?, completed_by=?, completed_at=?
                WHERE tenant_id=? AND application_id=? AND round_no=? AND process_instance_id=? AND status='IN_APPROVAL'
                """, status.name(), reason, completedBy, completedAt.atOffset(ZoneOffset.UTC), tenantId,
                applicationId.toString(), roundNo, processInstanceId);
        if (updated == 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) > 0 FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?
                """, Boolean.class, tenantId, applicationId.toString(), roundNo))) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round is already completed or belongs to another instance");
        }
    }

    private SubmissionRound map(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime completedAt = row.getObject("completed_at", OffsetDateTime.class);
        return new SubmissionRound(row.getString("tenant_id"), UUID.fromString(row.getString("application_id")),
                row.getInt("round_no"), row.getString("process_instance_id"), row.getLong("definition_version"),
                row.getString("title"), jsonUtil.map(row.getString("payload_json")), row.getString("submitted_by"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                SubmissionRound.Status.valueOf(row.getString("status")), row.getString("reason"),
                row.getString("completed_by"), completedAt == null ? null : completedAt.toInstant(),
                row.getString("form_schema_json") == null ? null : jsonUtil.read(row.getString("form_schema_json"), FormSchema.class),
                row.getString("initiator_context_json") == null ? null : jsonUtil.read(row.getString("initiator_context_json"), InitiatorContext.class));
    }
}
