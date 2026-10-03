package io.agentflow.definition;

import java.util.List;

/**
 * 流程设计可选择的审批规则，成员数由当前租户身份源提供。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionAssigneeDirectory {
    /** 返回具有有效审批账号的指定用户和角色；不可用身份源不得生成假名单。 */
    List<Option> options(String tenantId);

    /** 抄送目录允许无审批资格的有效人员；未实现的身份源不能复用审批角色推断收件权。 */
    default List<Option> copyOptions(String tenantId) { return List.of(); }

    /** 仅设计者配置表单单选项使用的组织来源，不暴露账号主体或完整组织关系。 */
    default List<FormOption> formOptions(String tenantId) { return List.of(); }

    /**
     * 名称随表单选项发布；可用性只描述当前目录，提交还会重新解析并冻结实际成员。
     * @author owlzhangfq@gmail.com
     */
    record FormOption(java.util.UUID id, String label, FormAssigneePolicy.SourceKind kind, int memberCount, boolean headAvailable) {
        /** 关系和来源类型必须一致，部门成员可用不代表部门负责人已配置。 */
        public boolean availableFor(FormAssigneePolicy.Relation relation) {
            return kind == relation.sourceKind() && (relation == FormAssigneePolicy.Relation.DEPARTMENT_HEAD ? headAvailable : memberCount > 0);
        }
    }

    /**
     * 可保存的字面量规则及当前有效成员数；成员变化后发布仍需重新读取。
     * @author owlzhangfq@gmail.com
     */
    record Option(String rule, String label, int memberCount, boolean contextual) {
        /** 静态目录保留已有成员计数契约。 */
        public Option(String rule, String label, int memberCount) { this(rule, label, memberCount, false); }
    }
}
