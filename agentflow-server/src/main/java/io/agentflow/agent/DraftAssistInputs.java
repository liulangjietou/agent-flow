package io.agentflow.agent;

import io.agentflow.approval.model.Application;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 申请人草稿的发送清单，敏感字段与附件不会因本人可读而自动允许外发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DraftAssistInputs {
    private final JsonUtil json;
    /** 沿用平台 JSON 表示生成精确来源指纹。 */
    public DraftAssistInputs(JsonUtil json) { this.json = json; }

    /** 只暴露可生成字段的业务契约，不发送审批权限、路由或其他内部配置。 */
    public FormSchema schema(Application application) {
        if (application.businessReference() != null || application.formSchema() == null) {
            throw new DomainException("AGENT_DRAFT_UNSUPPORTED", "Draft generation requires a versioned ordinary application form");
        }
        return new FormSchema(application.formSchema().schemaVersion(), application.formSchema().fields().stream()
                .filter(DraftAssistInput::generatable).map(DraftAssistInputs::target).toList());
    }

    /** 列出可选择的已保存内容；新输入的生成要求在排队时单独冻结。 */
    public List<AssistModelPort.Source> available(Application application) {
        var result = new ArrayList<AssistModelPort.Source>();
        result.add(source(DraftAssistInput.TITLE, "已保存的申请标题", application.title()));
        for (var field : schema(application).fields()) {
            if (application.payload().containsKey(field.key())) result.add(source(DraftAssistInput.FIELD_PREFIX + field.key(), field.label(), application.payload().get(field.key())));
        }
        return List.copyOf(result);
    }

    /** 服务端只读取明确选择的来源，未知字段或重复选择不能绕过发送清单。 */
    public DraftAssistInput select(Application application, String brief, List<String> sourceIds) {
        if (StringUtils.isBlank(brief) || brief.length() > DraftAssistInput.MAX_BRIEF_LENGTH || sourceIds == null
                || sourceIds.size() >= AssistInput.MAX_REFERENCES || new HashSet<>(sourceIds).size() != sourceIds.size()) throw invalid();
        var choices = available(application).stream().collect(Collectors.toMap(value -> value.reference().sourceId(), Function.identity()));
        var selected = new ArrayList<AssistModelPort.Source>();
        selected.add(source(DraftAssistInput.BRIEF, "本次生成要求", brief));
        for (String id : sourceIds) {
            var source = choices.get(id);
            if (source == null) throw new DomainException("FORBIDDEN", "Selected draft source is not sendable");
            selected.add(source);
        }
        if (json.write(selected).getBytes(StandardCharsets.UTF_8).length > AssistInputService.MAX_INPUT_BYTES) throw invalid();
        return new DraftAssistInput(application.id(), application.version(), schema(application), selected);
    }
    private static FormSchema.Field target(FormSchema.Field field) {
        return new FormSchema.Field(field.key(), field.label(), field.type(), field.required(), field.helpText(), field.maxLength(),
                field.minimum(), field.maximum(), field.options(), field.columns() == null ? null : field.columns().stream().map(DraftAssistInputs::target).toList(),
                field.maxRows(), null, null);
    }
    private AssistModelPort.Source source(String id, String label, Object value) {
        String content = json.write(value);
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), label, content);
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Draft instructions and selected sources exceed allowed bounds"); }
}
