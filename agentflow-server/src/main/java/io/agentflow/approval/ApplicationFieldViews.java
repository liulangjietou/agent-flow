package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FormFieldProjection;
import io.agentflow.form.FormSchema;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已完成申请授权后的字段投影边界；只构造展示副本，不修改业务原文或引擎条件变量。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApplicationFieldViews {
    private final CurrentActor actors;
    private final List<ApplicationParticipantPort> participants;
    private final TaskRecipientDirectory recipients;
    private final SubmissionRoundRepository rounds;

    /** 节点身份取引擎事实，字段规则取提交时已经固定的表单版本。 */
    public ApplicationFieldViews(CurrentActor actors, List<ApplicationParticipantPort> participants,
                                 TaskRecipientDirectory recipients, SubmissionRoundRepository rounds) {
        this.actors = actors; this.participants = List.copyOf(participants); this.recipients = recipients; this.rounds = rounds;
    }

    /** 当前申请只在审批中使用当前节点权限，补正内容不沿用上一轮的节点权限。 */
    public ApplicationResponse application(Application application) {
        var view = attachmentView(application, null);
        return new ApplicationResponse(application.id(), application.tenantId(), application.businessNo(), application.processKey(),
                application.definitionVersion(), application.createdBy(), application.title(), view.payload(), application.status(),
                application.roundNo(), application.version(), view.schema(), application.businessReference());
    }

    /** 文件元数据和下载必须使用与页面相同的当轮字段投影，不能沿用前端显示结果授权。 */
    public FormFieldProjection attachmentView(Application application, Integer roundNo) {
        if (roundNo != null) {
            if (roundNo < 1) throw new DomainException("INVALID_ATTACHMENT_QUERY", "Round number must be positive");
            var round = rounds.findByRound(application.tenantId(), application.id(), roundNo)
                    .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round not found"));
            return project(application, round.formSchema(), round.payload(), round.processInstanceId());
        }
        String process = application.status() == ApplicationStatus.IN_APPROVAL
                ? rounds.findByRound(application.tenantId(), application.id(), application.roundNo()).map(SubmissionRound::processInstanceId).orElse(null) : null;
        return project(application, application.formSchema(), application.payload(), process);
    }

    /** 每轮只使用该轮的节点参与事实；不能从其他轮次继承更宽的权限。 */
    public SubmissionRoundResponse round(Application application, SubmissionRound round) {
        var view = project(application, round.formSchema(), round.payload(), round.processInstanceId());
        return new SubmissionRoundResponse(round.roundNo(), round.processInstanceId(), round.definitionVersion(), round.title(),
                view.payload(), round.submittedBy(), round.submittedAt(), round.status(), round.reason(), round.completedBy(),
                round.completedAt(), view.schema(), round.initiatorContext(), round.risk());
    }

    /** 自由文本模型结果无法可靠逐字段脱敏；看不到完整输入的身份不能读取这次完整运行记录。 */
    public void requireFullAssistInput(Application application, int roundNo) {
        var round = rounds.findByRound(application.tenantId(), application.id(), roundNo).orElse(null);
        var view = round == null ? project(application, application.formSchema(), application.payload(), null)
                : project(application, round.formSchema(), round.payload(), round.processInstanceId());
        if (view.restricted()) throw new DomainException("FORBIDDEN", "Field permissions do not allow reading the complete assist result");
    }

    private FormFieldProjection project(Application application, FormSchema schema, Map<String, Object> payload, String process) {
        var actor = actors.actor();
        if (actor.userId().equals(application.createdBy()) || schema == null) return new FormFieldProjection(schema, payload, false);
        Set<String> nodes = process != null && actor.hasRole("APPROVER") && recipients.eligible(actor.tenantId(), actor.userId())
                ? participants.stream().flatMap(port -> port.readableNodes(application.tenantId(), process, actor).stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of();
        return FormFieldProjection.forNodes(schema, payload, nodes);
    }
}
