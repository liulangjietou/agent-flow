package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.definition.FormAssigneePolicy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本轮提交固定所有表单选人的原始责任，后续节点仅复核资格，不再查询新组织成员替换旧名单。
 * @author owlzhangfq@gmail.com
 */
@Service
public class FormAssigneeBindings {
    private final OrganizationAssigneeResolver resolver;
    private final OrganizationRepository repository;
    private final LocalOrganizationDirectory directory;

    /** 组织关系解析和资格复核共用原目录，不引入第二套人员权威源。 */
    public FormAssigneeBindings(OrganizationAssigneeResolver resolver, OrganizationRepository repository, LocalOrganizationDirectory directory) {
        this.resolver = resolver; this.repository = repository; this.directory = directory;
    }

    /** 根申请与子申请均在本轮创建原生实例前执行，未命中分支也不能潜伏空名单。 */
    @Transactional
    public Snapshot freeze(String tenantId, Graph graph, Map<String, Object> payload) {
        var nodes = new LinkedHashMap<String, OrganizationAssigneeResolver.Selection>();
        var selections = new HashMap<FormAssigneePolicy, OrganizationAssigneeResolver.Selection>();
        for (var node : graph.nodes()) {
            String rule = node.properties().get("assigneeRule");
            if (node.type() != NodeType.USER_TASK || !FormAssigneePolicy.isFieldRule(rule)) continue;
            var policy = FormAssigneePolicy.parse(rule);
            nodes.put(node.id(), selections.computeIfAbsent(policy, key -> resolver.resolveField(tenantId, key, selectedId(payload.get(key.fieldKey())))));
        }
        return new Snapshot(nodes);
    }

    /** 节点激活时全部冻结成员仍须具备本地资格；失效时阻断，不能悄悄减少会签责任。 */
    @Transactional
    public OrganizationAssigneeResolver.Selection requireActive(String tenantId, Snapshot snapshot, String nodeId, String rule) {
        var selected = snapshot.nodes().get(nodeId);
        if (selected == null || !selected.rule().equals(rule) || selected.subjects().isEmpty()) {
            throw new DomainException("FORM_ASSIGNEE_SNAPSHOT_MISSING", "The current round has no matching frozen form assignee selection");
        }
        repository.lock(tenantId);
        if (selected.subjects().stream().anyMatch(subject -> !directory.eligible(tenantId, subject))) {
            throw new DomainException("FORM_ASSIGNEE_UNAVAILABLE", "A frozen form approver is no longer eligible");
        }
        return selected;
    }

    private static UUID selectedId(Object value) {
        if (value instanceof String text) {
            try { return UUID.fromString(text); }
            catch (IllegalArgumentException invalid) { /* 统一返回字段来源缺失，不把非法值传给目录。 */ }
        }
        throw new DomainException("FORM_ASSIGNEE_VALUE_REQUIRED", "Select the declared organization value before submitting");
    }

    /**
     * 只保留当前流程节点的原始责任及目录修订；输入值已经在对应提交轮次中冻结。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(Map<String, OrganizationAssigneeResolver.Selection> nodes) {
        public static final Snapshot EMPTY = new Snapshot(Map.of());
        public Snapshot { nodes = Map.copyOf(nodes); }
    }
}
