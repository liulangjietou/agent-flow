package io.agentflow.approval.copy;

import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.OrganizationAssigneeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 抄送节点的跨聚合编排，解析、冻结与站内消息在原审批事务中成功或回滚。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CopyDeliveryService {
    private final OrganizationAssigneeResolver directory;
    private final JdbcCopyRecipientRepository recipients;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final InboxRepository inbox;
    /** 领域收件事实不依赖引擎或 HTTP 类型。 */
    public CopyDeliveryService(OrganizationAssigneeResolver directory, JdbcCopyRecipientRepository recipients,
            ApplicationRepository applications, SubmissionRoundRepository rounds, InboxRepository inbox) {
        this.directory = directory; this.recipients = recipients; this.applications = applications; this.rounds = rounds; this.inbox = inbox;
    }

    /** 参数来自已发布的受限节点和可信引擎变量，客户端不能直接调用此用例。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deliver(String tenant, UUID applicationId, int roundNo, String instance, String nodeId, String nodeName,
                        String rule, InitiatorContext context) {
        if (recipients.delivered(tenant, applicationId, roundNo, instance, nodeId)) return;
        var application = applications.findById(tenant, applicationId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Application not found"));
        var round = rounds.findByRound(tenant, applicationId, roundNo).orElse(null);
        String title = round == null ? application.title() : round.title();
        var selection = directory.resolveCopy(tenant, rule, context);
        Instant now = Instant.now();
        for (String recipient : selection.subjects()) {
            var copy = new CopyRecipient(tenant, applicationId, roundNo, instance, nodeId, nodeName, recipient, rule, selection.directoryRevision(), now);
            if (!recipients.append(copy)) continue;
            String event = "copy:" + applicationId + ":" + roundNo + ":" + nodeId;
            UUID id = UUID.nameUUIDFromBytes((tenant + ":" + event + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            inbox.append(event, new InboxMessage(id, tenant, recipient, applicationId, title, application.businessNo(),
                    InboxMessage.Kind.APPLICATION_COPIED, "system:copy", null, nodeName, roundNo, now, null,
                    "你收到一份审批抄送，可查看本轮已提交内容。"));
        }
    }
}
