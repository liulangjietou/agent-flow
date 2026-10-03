package io.agentflow.approval.copy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormFieldProjection;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.LocalOrganizationDirectory;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 独立抄送读取边界；不加入普通申请参与者端口，避免开放当前草稿和其他轮次。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CopyReadService {
    private final CurrentActor actors;
    private final LocalOrganizationDirectory directory;
    private final JdbcCopyRecipientRepository recipients;
    private final SubmissionRoundRepository rounds;
    private final ApplicationRepository applications;
    /** 查询只组合已授权轮次的展示数据，不返回申请聚合原文。 */
    public CopyReadService(CurrentActor actors, LocalOrganizationDirectory directory, JdbcCopyRecipientRepository recipients,
                           SubmissionRoundRepository rounds, ApplicationRepository applications) {
        this.actors = actors; this.directory = directory; this.recipients = recipients; this.rounds = rounds; this.applications = applications;
    }

    /** 管理员身份不绕过收件事实；停用人员立即不可读，历史事实继续保留。 */
    public Snapshot get(UUID applicationId, int roundNo) {
        var actor = actors.actor();
        if (roundNo < 1) throw new DomainException("INVALID_COPY_QUERY", "Round number must be positive");
        if (!directory.activeRecipient(actor.tenantId(), actor.userId())) throw notFound();
        var copies = recipients.find(actor.tenantId(), applicationId, roundNo, actor.userId());
        if (copies.isEmpty()) throw notFound();
        var round = rounds.findByRound(actor.tenantId(), applicationId, roundNo).orElseThrow(CopyReadService::notFound);
        var matching = copies.stream().filter(value -> value.processInstanceId().equals(round.processInstanceId())).toList();
        if (matching.isEmpty()) throw notFound();
        var application = applications.findById(actor.tenantId(), applicationId).orElseThrow(CopyReadService::notFound);
        var nodes = matching.stream().map(CopyRecipient::nodeId).collect(Collectors.toUnmodifiableSet());
        var view = round.formSchema() == null ? new FormFieldProjection(null, round.payload(), false)
                : FormFieldProjection.forNodes(round.formSchema(), round.payload(), nodes);
        return new Snapshot(applicationId, application.businessNo(), roundNo, round.definitionVersion(), round.title(),
                round.status(), round.submittedAt(), matching.stream().map(CopyRecipient::nodeName).toList(), view.schema(), view.payload());
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Copied submission round not found"); }

    /**
     * 最小只读视图，不携带当前申请版本、其他轮次、审批意见或原始敏感字段。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(UUID applicationId, String businessNo, int roundNo, long definitionVersion, String title,
                           SubmissionRound.Status status, Instant submittedAt, List<String> nodeNames,
                           @JsonInclude(JsonInclude.Include.ALWAYS) FormSchema formSchema, Map<String, Object> payload) { }
}
