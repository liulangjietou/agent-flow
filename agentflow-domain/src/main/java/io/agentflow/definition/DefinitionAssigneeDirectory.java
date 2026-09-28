package io.agentflow.definition;

import java.util.List;

/**
 * 流程设计可选择的审批规则，成员数由当前租户身份源提供。
 * @author owlzhangfq@gmail.com
 */
public interface DefinitionAssigneeDirectory {
    /** 返回具有有效审批账号的指定用户和角色；不可用身份源不得生成假名单。 */
    List<Option> options(String tenantId);

    /**
     * 可保存的字面量规则及当前有效成员数；成员变化后发布仍需重新读取。
     * @author owlzhangfq@gmail.com
     */
    record Option(String rule, String label, int memberCount, boolean contextual) {
        /** 静态目录保留已有成员计数契约。 */
        public Option(String rule, String label, int memberCount) { this(rule, label, memberCount, false); }
    }
}
