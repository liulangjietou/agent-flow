package io.agentflow.approval;

import io.agentflow.approval.mapper.ApplicationUrgeMapper;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.notification.ApprovalNotificationService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 申请人手动催办编排服务：在申请锁内校验冷却期、发送站内催办提醒并留存审计。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApplicationUrgeService {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationUrgeService.class);
    private static final int MAX_NOTE_LENGTH = 500;
    private final CurrentActor currentActor;
    private final ApprovalApplicationFacade applications;
    private final SubprocessExecutionLocks executionLocks;
    private final SubmissionRoundRepository rounds;
    private final ApplicationUrgeMapper urgeMapper;
    private final ApprovalNotificationService notifications;
    private final ApplicationAuditPort audit;
    private final Duration cooldown;

    /** 构造催办服务并读取冷却期配置（默认 30 分钟，最少 1 分钟）。 */
    public ApplicationUrgeService(
            CurrentActor currentActor,
            ApprovalApplicationFacade applications,
            SubprocessExecutionLocks executionLocks,
            SubmissionRoundRepository rounds,
            ApplicationUrgeMapper urgeMapper,
            ApprovalNotificationService notifications,
            ApplicationAuditPort audit,
            @Value("${agentflow.approval.urge-cooldown-minutes:30}") long cooldownMinutes) {
        this.currentActor = currentActor;
        this.applications = applications;
        this.executionLocks = executionLocks;
        this.rounds = rounds;
        this.urgeMapper = urgeMapper;
        this.notifications = notifications;
        this.audit = audit;
        this.cooldown = Duration.ofMinutes(Math.max(1L, cooldownMinutes));
    }

    /** 申请人对审批中的申请发起催办，冷却期内拒绝重复催办；不改变申请版本号。 */
    @Transactional
    public UrgeReceipt urge(UUID applicationId, Long expectedVersion, String note) {
        if (expectedVersion == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "expectedVersion is required");
        }
        String normalizedNote = normalizeNote(note);
        Actor actor = currentActor.actor();
        Application locked = executionLocks.lock(applications.requireApplicant(applicationId));
        locked.checkVersion(expectedVersion);
        if (locked.status() != ApplicationStatus.IN_APPROVAL) {
            throw new DomainException(ErrorCode.DOMAIN_RULE_VIOLATION, "Only an in-approval application can be urged");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var latestRows = urgeMapper.findLatest(actor.tenantId(), locked.id().toString(), locked.roundNo());
        if (!latestRows.isEmpty()) {
            Timestamp latestTs = latestRows.get(0).getTimestamp("urged_at");
            if (latestTs != null && latestTs.toInstant().plus(cooldown).isAfter(now)) {
                throw new DomainException(ErrorCode.APPLICATION_URGE_COOLDOWN,
                        "Application was urged recently; please wait before urging again");
            }
        }
        UUID urgeId = UUID.randomUUID();
        int notifiedCount = notifications.urged(locked, actor.userId(), urgeId, now);
        urgeMapper.insert(
                urgeId.toString(),
                actor.tenantId(),
                locked.id().toString(),
                locked.roundNo(),
                actor.userId(),
                normalizedNote,
                Timestamp.from(now));
        String processInstanceId = rounds.findByRound(actor.tenantId(), locked.id(), locked.roundNo())
                .map(SubmissionRound::processInstanceId)
                .orElse(null);
        audit.record(new ApplicationAuditPort.ApplicationOperation(
                actor.tenantId(),
                locked.businessNo(),
                locked.id(),
                locked.version(),
                locked.roundNo(),
                processInstanceId,
                actor.userId(),
                ApplicationAuditPort.Action.URGE,
                ApplicationStatus.IN_APPROVAL,
                ApplicationStatus.IN_APPROVAL,
                normalizedNote));
        LOG.info("Application urged, applicationId={}, roundNo={}, notifiedCount={}",
                locked.id(), locked.roundNo(), notifiedCount);
        return new UrgeReceipt(urgeId, locked.id(), locked.roundNo(), notifiedCount, now, now.plus(cooldown));
    }

    private static String normalizeNote(String note) {
        if (StringUtils.isBlank(note)) return null;
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "Urge note must not exceed 500 characters");
        }
        return trimmed;
    }

    /**
     * 催办回执，返回本次提醒人数与下次可催办时间。
     * @author owlzhangfq@gmail.com
     */
    public record UrgeReceipt(
            UUID urgeId,
            UUID applicationId,
            int roundNo,
            int notifiedCount,
            Instant urgedAt,
            Instant nextAllowedAt) { }
}
