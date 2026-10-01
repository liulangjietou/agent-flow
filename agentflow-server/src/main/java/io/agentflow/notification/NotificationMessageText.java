package io.agentflow.notification;

/** 两种渠道共用最小正文，不接收申请、评论或表单。
 * @author owlzhangfq@gmail.com
 */
final class NotificationMessageText {
    private NotificationMessageText() { }
    static String text(String publicUrl) {
        return "你有一条新的 AgentFlow 站内消息。请登录后打开消息中心查看。\n\n" + publicUrl + "\n";
    }
}
