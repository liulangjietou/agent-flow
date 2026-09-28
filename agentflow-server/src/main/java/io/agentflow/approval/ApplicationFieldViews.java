package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已完成申请授权后的字段投影边界；只构造展示副本，不修改业务原文或引擎条件变量。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApplicationFieldViews {
    private static final String MASKED_VALUE = "已脱敏";
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
        String process = application.status() == ApplicationStatus.IN_APPROVAL
                ? rounds.findByRound(application.tenantId(), application.id(), application.roundNo()).map(SubmissionRound::processInstanceId).orElse(null) : null;
        var view = project(application, application.formSchema(), application.payload(), process);
        return new ApplicationResponse(application.id(), application.tenantId(), application.businessNo(), application.processKey(),
                application.definitionVersion(), application.createdBy(), application.title(), view.payload(), application.status(),
                application.roundNo(), application.version(), view.schema());
    }

    /** 每轮只使用该轮的节点参与事实；不能从其他轮次继承更宽的权限。 */
    public SubmissionRoundResponse round(Application application, SubmissionRound round) {
        var view = project(application, round.formSchema(), round.payload(), round.processInstanceId());
        return new SubmissionRoundResponse(round.roundNo(), round.processInstanceId(), round.definitionVersion(), round.title(),
                view.payload(), round.submittedBy(), round.submittedAt(), round.status(), round.reason(), round.completedBy(),
                round.completedAt(), view.schema(), round.initiatorContext());
    }

    /** 自由文本模型结果无法可靠逐字段脱敏；看不到完整输入的身份不能读取这次完整运行记录。 */
    public void requireFullAssistInput(Application application, int roundNo) {
        var round = rounds.findByRound(application.tenantId(), application.id(), roundNo).orElse(null);
        var view = round == null ? project(application, application.formSchema(), application.payload(), null)
                : project(application, round.formSchema(), round.payload(), round.processInstanceId());
        if (view.restricted()) throw new DomainException("FORBIDDEN", "Field permissions do not allow reading the complete assist result");
    }

    private Projection project(Application application, FormSchema schema, Map<String, Object> payload, String process) {
        var actor = actors.actor();
        if (actor.userId().equals(application.createdBy()) || schema == null) return new Projection(schema, payload, false);
        Set<String> nodes = process != null && actor.hasRole("APPROVER") && recipients.eligible(actor.tenantId(), actor.userId())
                ? participants.stream().flatMap(port -> port.readableNodes(application.tenantId(), process, actor).stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of();
        var fields = new ArrayList<FormSchema.Field>();
        var values = new LinkedHashMap<String, Object>();
        boolean restricted = false;
        for (var field : schema.fields()) {
            var projected = field(field, nodes);
            restricted |= !field.equals(projected);
            if (projected == null) continue;
            fields.add(projected);
            if (field.visibility(nodes) == FieldVisibility.MASKED) values.put(field.key(), MASKED_VALUE);
            else if (payload.containsKey(field.key())) values.put(field.key(), value(field, payload.get(field.key()), nodes));
        }
        return new Projection(new FormSchema(schema.schemaVersion(), fields), java.util.Collections.unmodifiableMap(values), restricted);
    }

    private FormSchema.Field field(FormSchema.Field field, Set<String> nodes) {
        var access = field.visibility(nodes);
        if (access == FieldVisibility.HIDDEN) return null;
        if (access == FieldVisibility.MASKED) return new FormSchema.Field(field.key(), field.label(), FormSchema.FieldType.TEXT,
                false, "该字段已按权限脱敏", null, null, null, null);
        if (field.type() != FormSchema.FieldType.TABLE) return field;
        var columns = field.columns().stream().map(column -> field(column, nodes)).filter(java.util.Objects::nonNull).toList();
        if (columns.isEmpty()) return null;
        return new FormSchema.Field(field.key(), field.label(), field.type(), field.required(), field.helpText(), field.maxLength(),
                field.minimum(), field.maximum(), field.options(), columns, field.maxRows(), field.sensitive(), field.nodeAccess());
    }

    private Object value(FormSchema.Field field, Object raw, Set<String> nodes) {
        if (field.visibility(nodes) == FieldVisibility.MASKED) return MASKED_VALUE;
        if (field.type() != FormSchema.FieldType.TABLE || !(raw instanceof List<?> rows)) return raw;
        return rows.stream().map(row -> {
            var visible = new LinkedHashMap<String, Object>();
            if (row instanceof Map<?, ?> cells) for (var column : field.columns()) {
                var access = column.visibility(nodes);
                if (access == FieldVisibility.MASKED) visible.put(column.key(), MASKED_VALUE);
                else if (access == FieldVisibility.READ_ONLY && cells.containsKey(column.key())) visible.put(column.key(), cells.get(column.key()));
            }
            return java.util.Collections.unmodifiableMap(visible);
        }).toList();
    }

    /**
     * 已授权的内容副本与是否删减信息。
     * @author owlzhangfq@gmail.com
     */
    private record Projection(FormSchema schema, Map<String, Object> payload, boolean restricted) { }
}
