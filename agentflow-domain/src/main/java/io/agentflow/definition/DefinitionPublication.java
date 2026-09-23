package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 不可变的发布事实，保留发布当时的操作者、权限依据与实际校验范围。
 * @author owlzhangfq@gmail.com
 */
public record DefinitionPublication(String tenantId, UUID definitionId, String processKey, long definitionVersion,
                                    String publishedBy, String authorizedRole, Instant publishedAt, String changeNote,
                                    ValidationSummary validation) {
    public static final int MAX_CHANGE_NOTE_LENGTH = 2000;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String PROCESS_ADMIN_ROLE = "PROCESS_ADMIN";

    /** 发布说明是发布事实的必填项，历史缺失记录不能用虚构说明补齐。 */
    public DefinitionPublication {
        if (StringUtils.isBlank(changeNote) || changeNote.length() > MAX_CHANGE_NOTE_LENGTH) {
            throw new DomainException("INVALID_PUBLICATION_NOTE", "Publication change note must contain 1 to 2000 characters");
        }
        changeNote = changeNote.strip();
    }

    /** 在图与表单校验通过后生成待持久化事实，版本由应用服务在同一事务中分配。 */
    public static DefinitionPublication prepare(DefinitionModels.DefinitionDraft draft, long version, Actor publisher,
                                                String changeNote, Instant publishedAt) {
        String role;
        if (publisher.hasRole(ADMIN_ROLE)) role = ADMIN_ROLE;
        else if (publisher.hasRole(PROCESS_ADMIN_ROLE)) role = PROCESS_ADMIN_ROLE;
        else throw new DomainException("FORBIDDEN", "Process administrator role is required");
        var checks = draft.formSchema() == null
                ? List.of(Check.GRAPH_STRUCTURE, Check.ASSIGNEE_SYNTAX, Check.RESTRICTED_CONDITIONS)
                : List.of(Check.GRAPH_STRUCTURE, Check.ASSIGNEE_SYNTAX, Check.RESTRICTED_CONDITIONS, Check.FORM_FIELD_TYPES);
        var summary = new ValidationSummary(draft.graph().nodes().size(), draft.graph().edges().size(),
                draft.formSchema() == null ? 0 : draft.formSchema().fields().size(), draft.formSchema() != null, checks);
        return new DefinitionPublication(draft.tenantId(), draft.id(), draft.key(), version, publisher.userId(), role,
                publishedAt, changeNote, summary);
    }

    /**
     * 仅列举当前验证器已实现的检查，不表示组织人员存在性或业务规则已核实。
     * @author owlzhangfq@gmail.com
     */
    public enum Check { GRAPH_STRUCTURE, ASSIGNEE_SYNTAX, RESTRICTED_CONDITIONS, FORM_FIELD_TYPES }

    /**
     * 校验通过时的结构摘要，与发布定义一起保持不可变。
     * @author owlzhangfq@gmail.com
     */
    public record ValidationSummary(int nodeCount, int edgeCount, int fieldCount, boolean formBound, List<Check> checks) {
        /** 防止调用方后续修改校验范围。 */
        public ValidationSummary { checks = List.copyOf(checks); }
    }
}
