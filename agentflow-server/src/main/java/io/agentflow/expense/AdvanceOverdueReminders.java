package io.agentflow.expense;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 原借款、当前余额、消息和提醒证据在单笔事务内复核与保存，不访问外部渠道。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceOverdueReminders {
    private final AdvanceRequestRepository requests;
    private final EmployeeAdvanceRepository balances;
    private final ApplicationRepository applications;
    private final JdbcAdvanceOverdueRepository overdue;
    private final InboxRepository inbox;

    /** 保持原申请到财务余额的锁顺序，外部通知仍使用现有异步投递机制。 */
    public AdvanceOverdueReminders(AdvanceRequestRepository requests, EmployeeAdvanceRepository balances, ApplicationRepository applications,
                                  JdbcAdvanceOverdueRepository overdue, InboxRepository inbox) {
        this.requests = requests; this.balances = balances; this.applications = applications; this.overdue = overdue; this.inbox = inbox;
    }

    /** 每次领取都重读余额；还清、尚未到期及已提醒的借款不产生新消息。 */
    @Transactional(timeout = 10)
    public boolean remind(JdbcAdvanceOverdueRepository.Candidate candidate, Instant now) {
        requests.lock(candidate.tenantId(), candidate.id()); overdue.lock(candidate);
        if (overdue.recorded(candidate)) return false;
        var advance = balances.find(candidate.tenantId(), candidate.id()).orElseThrow();
        var request = requests.find(candidate.tenantId(), candidate.id()).orElseThrow();
        var round = request.currentRound();
        var application = applications.findById(candidate.tenantId(), request.applicationId()).orElseThrow();
        if (request.approval() == null || round == null || !request.employeeId().equals(advance.employeeId())
                || !application.createdBy().equals(advance.employeeId()) || !round.content().legalEntityId().equals(advance.legalEntityId())
                || !round.content().dueOn().equals(advance.dueOn()) || !advance.dueOn().equals(candidate.dueOn())) {
            throw new IllegalStateException("Advance overdue notification source is inconsistent");
        }
        String zone = round.legalEntity().timeZone(); var date = LocalDate.ofInstant(now, ZoneId.of(zone));
        if (!advance.overdue(date)) return false;
        String eventKey = "advance-overdue:" + advance.id();
        UUID messageId = UUID.nameUUIDFromBytes((advance.tenantId() + ":" + eventKey + ":" + advance.employeeId()).getBytes(StandardCharsets.UTF_8));
        inbox.append(eventKey, new InboxMessage(messageId, advance.tenantId(), advance.employeeId(), application.id(), application.title(),
                application.businessNo(), InboxMessage.Kind.ADVANCE_OVERDUE, "system:advance-overdue", null, null, round.roundNo(), now, null,
                "该借款在 " + date + "（法人当地日期）已逾期且仍有未还余额，请查看借款详情并处理。消息保留生成时的记录，当前余额以详情为准。"));
        overdue.record(advance, messageId, date, zone, now);
        return true;
    }
}
