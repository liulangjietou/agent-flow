package io.agentflow.agent;

import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 显式来源选择边界：正文由服务端构造，只使用当前身份在该轮可读的非敏感字段。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AssistInputService {
    public static final int MAX_INPUT_BYTES = 64 * 1024;
    private final FlowableTaskFacade tasks;
    private final ApplicationFieldViews fields;
    private final SubmissionRoundRepository rounds;
    private final JsonUtil json;

    /** 待办授权和字段投影复用原审批边界，模型输入不直接读取客户端正文。 */
    public AssistInputService(FlowableTaskFacade tasks, ApplicationFieldViews fields, SubmissionRoundRepository rounds, JsonUtil json) {
        this.tasks = tasks; this.fields = fields; this.rounds = rounds; this.json = json;
    }

    /** 只允许当轮可作出批准决定的人使用摘要，不因管理员或历史参与身份放宽。 */
    public void requireDecision(Application application, String taskId, Actor actor) {
        if (!tasks.canDecide(taskId, actor, application.id())) {
            throw new DomainException("FORBIDDEN", "A current decision task is required for assist operations");
        }
    }

    /** 列出可选择的来源，缺省全部不选；无表单旧流程仅提供标题。 */
    public List<AssistModelPort.Source> available(Application application, int roundNo) {
        var round = rounds.findByRound(application.tenantId(), application.id(), roundNo)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round not found"));
        var result = new ArrayList<AssistModelPort.Source>();
        result.add(source("application:title", "申请标题", round.title()));
        var schema = round.formSchema();
        if (schema == null) return List.copyOf(result);
        var view = fields.attachmentView(application, roundNo);
        var visible = view.schema().fields().stream().collect(Collectors.toMap(FormSchema.Field::key, Function.identity()));
        for (var field : schema.fields()) {
            var projected = visible.get(field.key());
            if (projected == null || Boolean.TRUE.equals(field.sensitive())) continue;
            if (field.type() == FormSchema.FieldType.TABLE) {
                if (projected.type() != FormSchema.FieldType.TABLE) continue;
                var columns = projected.columns().stream().collect(Collectors.toMap(FormSchema.Field::key, Function.identity()));
                Object raw = round.payload().get(field.key());
                if (!(raw instanceof List<?> rows)) continue;
                for (var column : field.columns()) {
                    if (!sendable(column, columns.get(column.key()))) continue;
                    var values = rows.stream().map(row -> ((Map<?, ?>) row).get(column.key())).toList();
                    result.add(source("form:" + field.key() + "." + column.key(), field.label() + " / " + column.label(), values));
                }
            } else if (sendable(field, projected) && round.payload().containsKey(field.key())) {
                result.add(source("form:" + field.key(), field.label(), round.payload().get(field.key())));
            }
        }
        return List.copyOf(result);
    }

    /** 不能通过直接提交隐藏字段标识、重复来源或超大内容绕过选择器。 */
    public List<AssistModelPort.Source> select(Application application, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > AssistInput.MAX_REFERENCES
                || new HashSet<>(sourceIds).size() != sourceIds.size()) throw invalid();
        var available = available(application, application.roundNo()).stream()
                .collect(Collectors.toMap(source -> source.reference().sourceId(), Function.identity()));
        var selected = new ArrayList<AssistModelPort.Source>();
        for (String id : sourceIds) {
            var source = available.get(id);
            if (source == null) throw new DomainException("FORBIDDEN", "Selected assist source is not sendable");
            selected.add(source);
        }
        if (json.write(selected).getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) throw invalid();
        return List.copyOf(selected);
    }

    /** 自由文本不能事后脱敏；所有实际输入仍可读时才开放该运行正文及人工复核。 */
    public void requireReadable(Application application, AssistRun run, List<AssistModelPort.Source> sources) {
        var readable = available(application, run.input().roundNo()).stream().map(AssistModelPort.Source::reference).collect(Collectors.toSet());
        if (!sources.stream().map(AssistModelPort.Source::reference).toList().equals(run.input().references())
                || !readable.containsAll(run.input().references())) {
            throw new DomainException("FORBIDDEN", "Current field permissions do not cover the selected assist input");
        }
    }

    private boolean sendable(FormSchema.Field original, FormSchema.Field projected) {
        return original.equals(projected) && !Boolean.TRUE.equals(original.sensitive())
                && original.type() != FormSchema.FieldType.ATTACHMENT && original.type() != FormSchema.FieldType.TABLE;
    }
    private AssistModelPort.Source source(String id, String label, Object value) {
        String content = json.write(value);
        return new AssistModelPort.Source(new AssistInput.Reference(id, AssistConfiguration.digest(content)), label, content);
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Select one to 64 sources within 64 KiB"); }
}
