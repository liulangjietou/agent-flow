package io.agentflow.approval;

import io.agentflow.approval.mapper.SubmissionRoundRepositoryMapper;
import io.agentflow.approval.model.SubmissionRisk;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.organization.InitiatorContext;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 轮次快照 JDBC 适配器；内容仅插入，结论通过实例和未结束状态进行条件更新。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSubmissionRoundRepository implements SubmissionRoundRepository {
    private final SubmissionRoundRepositoryMapper sqlMapper;
    private final JsonUtil jsonUtil;
    private final ApplicationEventPublisher events;

    /** 使用审批事务共用的数据源保存轮次。 */
    public JdbcSubmissionRoundRepository(
            SubmissionRoundRepositoryMapper sqlMapper,
            JsonUtil jsonUtil,
            ApplicationEventPublisher events) {
        this.sqlMapper = sqlMapper;
        this.jsonUtil = jsonUtil;
        this.events = events;
    }

    @Override
    public void append(SubmissionRound round) {
        var initiator = round.initiatorContext();
        try {
            sqlMapper.append(
                    round.tenantId(),
                    round.applicationId().toString(),
                    round.roundNo(),
                    round.processInstanceId(),
                    round.definitionVersion(),
                    round.title(),
                    jsonUtil.write(round.payload()),
                    round.submittedBy(),
                    round.submittedAt().atOffset(ZoneOffset.UTC),
                    round.formSchema() == null ? null : jsonUtil.write(round.formSchema()),
                    initiator == null ? null : jsonUtil.write(initiator),
                    initiator == null ? null : initiator.legalEntityName(),
                    initiator == null ? null : initiator.departmentName(),
                    initiator == null ? null : initiator.positionName(),
                    round.risk().level().name(),
                    jsonUtil.write(round.risk()));
        } catch (DuplicateKeyException exception) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Submission round already exists");
        }
    }

    @Override
    public List<SubmissionRound> findAll(String tenantId, UUID applicationId) {
        return SqlRows.map(sqlMapper.findAll(tenantId, applicationId.toString()), this::map);
    }

    @Override
    public Optional<SubmissionRound> findByRound(String tenantId, UUID applicationId, int roundNo) {
        return SqlRows.map(
                        sqlMapper.findByRound(tenantId, applicationId.toString(), roundNo),
                        this::map)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            String tenantId,
            UUID applicationId,
            int roundNo,
            String processInstanceId,
            SubmissionRound.Status status,
            String reason,
            String completedBy,
            Instant completedAt) {
        status.requireTerminal();
        int updated =
                sqlMapper.complete(
                        status.name(),
                        reason,
                        completedBy,
                        completedAt.atOffset(ZoneOffset.UTC),
                        tenantId,
                        applicationId.toString(),
                        roundNo,
                        processInstanceId);
        if (updated == 0
                && Boolean.TRUE.equals(
                        SqlRows.single(
                                sqlMapper.complete2(
                                        tenantId, applicationId.toString(), roundNo)))) {
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Submission round is already completed or belongs to another instance");
        }
        if (updated == 1)
            events.publishEvent(
                    new SubmissionRoundCompleted(tenantId, applicationId, roundNo, status));
    }

    private SubmissionRound map(SqlRow row) {
        OffsetDateTime completedAt = row.getObject("completed_at", OffsetDateTime.class);
        return new SubmissionRound(row.getString("tenant_id"), UUID.fromString(row.getString("application_id")),
                row.getInt("round_no"), row.getString("process_instance_id"), row.getLong("definition_version"),
                row.getString("title"), jsonUtil.map(row.getString("payload_json")), row.getString("submitted_by"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                SubmissionRound.Status.valueOf(row.getString("status")), row.getString("reason"),
                row.getString("completed_by"), completedAt == null ? null : completedAt.toInstant(),
                row.getString("form_schema_json") == null ? null : jsonUtil.read(row.getString("form_schema_json"), FormSchema.class),
                row.getString("initiator_context_json") == null ? null : jsonUtil.read(row.getString("initiator_context_json"), InitiatorContext.class),
                row.getString("risk_json") == null ? SubmissionRisk.unassessed()
                        : jsonUtil.read(row.getString("risk_json"), SubmissionRisk.class));
    }
}
