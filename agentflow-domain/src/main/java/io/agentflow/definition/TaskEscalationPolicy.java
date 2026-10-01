package io.agentflow.definition;

import io.agentflow.calendar.BusinessDeadline;
import io.agentflow.common.DomainException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 原审批期限之后沿同一日历等待，再向固定对象发送一次协调提醒。 @author owlzhangfq@gmail.com */
public record TaskEscalationPolicy(int workingMinutes, String recipientRule) {
    public static final String WORKING_MINUTES = "escalationWorkingMinutes";
    public static final String RECIPIENT_RULE = "escalationRecipientRule";
    public static final Set<String> PROPERTY_KEYS = Set.of(WORKING_MINUTES, RECIPIENT_RULE);
    public static final int MAX_RECIPIENTS = 100;

    /** 只接收有界工作分钟与字面量选人规则，不允许表达式或动态最新配置。 */
    public TaskEscalationPolicy {
        if (workingMinutes < 1 || workingMinutes > BusinessDeadline.MAX_WORKING_MINUTES
                || !DefinitionValidator.isLiteralAssigneeRule(recipientRule)) throw invalid();
    }

    /** 任一升级属性存在时都必须形成完整规则，旧节点全部缺失则保持未配置。 */
    public static Optional<TaskEscalationPolicy> fromProperties(Map<String, String> properties) {
        if (PROPERTY_KEYS.stream().noneMatch(properties::containsKey)) return Optional.empty();
        try {
            String minutes = properties.get(WORKING_MINUTES);
            if (minutes == null || !minutes.matches("[1-9][0-9]*")) throw invalid();
            return Optional.of(new TaskEscalationPolicy(Integer.parseInt(minutes), properties.get(RECIPIENT_RULE)));
        } catch (NumberFormatException failure) { throw invalid(); }
    }

    private static DomainException invalid() {
        return new DomainException("ESCALATION_RULE_INVALID", "Escalation requires supported working minutes and a literal recipient rule");
    }
}
