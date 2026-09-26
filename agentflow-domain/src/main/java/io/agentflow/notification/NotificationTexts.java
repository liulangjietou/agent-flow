package io.agentflow.notification;

import io.agentflow.common.DomainException;

/**
 * 随流程版本冻结的站内通知纯文本；空文案沿用平台事件提示，不解释表达式。
 * @author owlzhangfq@gmail.com
 */
public record NotificationTexts(String submitted, String returned, String approved) {
    public static final int MAX_LENGTH = 500;
    public static final NotificationTexts EMPTY = new NotificationTexts("", "", "");

    /** 在配置入口统一约束长度及不可显示的控制字符。 */
    public NotificationTexts {
        submitted = normalize(submitted);
        returned = normalize(returned);
        approved = normalize(approved);
    }

    /** 仅申请人三类结果消息读取配置，任务通知保持原有语义。 */
    public String forEvent(InboxMessage.Kind kind) {
        return switch (kind) {
            case APPLICATION_SUBMITTED -> submitted;
            case APPLICATION_RETURNED -> returned;
            case APPLICATION_APPROVED -> approved;
            default -> "";
        };
    }

    private static String normalize(String text) {
        if (text == null) return "";
        if (text.length() > MAX_LENGTH || text.chars().anyMatch(value -> Character.isISOControl(value)
                && value != '\n' && value != '\r' && value != '\t')) {
            throw new DomainException("INVALID_NOTIFICATION_TEXT", "Notification text must contain at most 500 characters without control codes");
        }
        return text.strip();
    }
}
