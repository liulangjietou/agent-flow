package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 草稿建议绑定原申请版本、可生成字段和明确授权的文本来源。
 * @author owlzhangfq@gmail.com
 */
public record DraftAssistInput(UUID applicationId, long applicationVersion, FormSchema targetSchema,
                               List<AssistModelPort.Source> sources) {
    public static final String TITLE = "application:title";
    public static final String BRIEF = "application:brief";
    public static final String FIELD_PREFIX = "form:";
    public static final int MAX_BRIEF_LENGTH = 8000;

    public DraftAssistInput {
        if (applicationId == null || applicationVersion < 1 || targetSchema == null || sources == null
                || sources.isEmpty() || sources.size() > AssistInput.MAX_REFERENCES) throw invalid();
        var ids = new HashSet<String>();
        for (var source : sources) {
            if (source == null || source.reference() == null || source.content() == null || !ids.add(source.reference().sourceId())) throw invalid();
        }
        if (!ids.contains(BRIEF) || targetSchema.fields().stream().anyMatch(field -> !generatable(field))) throw invalid();
        sources = List.copyOf(sources);
    }

    /** 不生成敏感内容或附件；含受限列的整张明细保持人工填写，避免替换时丢失原列。 */
    public static boolean generatable(FormSchema.Field field) {
        return !Boolean.TRUE.equals(field.sensitive()) && field.type() != FormSchema.FieldType.ATTACHMENT
                && (field.type() != FormSchema.FieldType.TABLE || field.columns().stream().allMatch(DraftAssistInput::generatable));
    }

    /** 模型与人工确认使用相同目标白名单及现有草稿字段契约。 */
    public void requireValue(String targetId, Object value) {
        if (TITLE.equals(targetId)) {
            if (!(value instanceof String title) || title.isBlank() || title.length() > 256) throw invalidOutput();
            return;
        }
        if (targetId == null || !targetId.startsWith(FIELD_PREFIX) || value == null) throw invalidOutput();
        String key = targetId.substring(FIELD_PREFIX.length());
        try { targetSchema.validateDraft(java.util.Map.of(key, value)); }
        catch (DomainException rejected) { throw invalidOutput(); }
    }

    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Invalid draft assist input"); }
    private static DomainException invalidOutput() { return new DomainException("INVALID_AGENT_OUTPUT", "Draft value does not match an allowed target"); }
}
