package io.agentflow.notification;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static io.agentflow.notification.NotificationDeliveryConfiguration.Security;
import static io.agentflow.notification.NotificationDeliveryProgress.*;

/** SMTP 只发送最小提醒；网络调用没有业务事务，协议回执不等于最终送达。 @author owlzhangfq@gmail.com */
@Component
public class SmtpNotificationTransport {
    private static final String SUBJECT = "AgentFlow 站内消息提醒";
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int IO_TIMEOUT_MILLIS = 3000;

    /** 原投递始终使用相同 Message-ID；它用于追踪，不能替代 SMTP 服务端去重。 */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome send(NotificationDestinations.Destination destination, NotificationDelivery delivery) {
        Transport transport = null;
        boolean sending = false;
        try {
            var server = destination.server();
            var session = Session.getInstance(properties(server));
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(server.from()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(destination.address()));
            message.setSubject(SUBJECT, "UTF-8");
            message.setText("你有一条新的 AgentFlow 站内消息。请登录后打开消息中心查看。\n\n" + destination.publicUrl() + "\n", "UTF-8");
            message.setSentDate(Date.from(delivery.createdAt()));
            message.saveChanges();
            message.setHeader("Message-ID", "<agentflow-notification-" + delivery.id() + "@"
                    + server.from().substring(server.from().lastIndexOf('@') + 1) + ">");
            transport = session.getTransport("smtp");
            transport.connect(server.host(), server.port(), server.username(), server.password());
            sending = true;
            transport.sendMessage(message, message.getAllRecipients());
            return Outcome.accepted();
        } catch (AuthenticationFailedException rejected) {
            return Outcome.failed(FailureCode.SMTP_AUTH_FAILED);
        } catch (MessagingException failure) {
            if (!sending) return Outcome.retryable(FailureCode.SMTP_CONNECT_FAILED);
            return sendFailure(failure);
        } finally {
            if (transport != null) {
                try { transport.close(); }
                catch (MessagingException ignored) { /* QUIT 失败不能推翻已经收到的 DATA 受理回执。 */ }
            }
        }
    }

    static Properties properties(NotificationDeliveryConfiguration.SmtpServer server) {
        var values = new Properties();
        values.setProperty("mail.smtp.connectiontimeout", Integer.toString(CONNECT_TIMEOUT_MILLIS));
        values.setProperty("mail.smtp.timeout", Integer.toString(IO_TIMEOUT_MILLIS));
        values.setProperty("mail.smtp.writetimeout", Integer.toString(IO_TIMEOUT_MILLIS));
        values.setProperty("mail.smtp.quitwait", "false");
        values.setProperty("mail.smtp.sendpartial", "false");
        values.setProperty("mail.smtp.reportsuccess", "false");
        values.setProperty("mail.smtp.auth", Boolean.toString(server.username() != null && !server.username().isEmpty()));
        values.setProperty("mail.smtp.auth.mechanisms", "PLAIN LOGIN");
        values.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        values.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        values.setProperty("mail.smtp.ssl.enable", Boolean.toString(server.security() == Security.TLS));
        values.setProperty("mail.smtp.starttls.enable", Boolean.toString(server.security() == Security.STARTTLS));
        values.setProperty("mail.smtp.starttls.required", Boolean.toString(server.security() == Security.STARTTLS));
        return values;
    }

    private static Outcome sendFailure(MessagingException failure) {
        // 即使异常链含拒绝，只要已有受理地址，就不能把整体结果归为安全重试。
        if (failure instanceof SendFailedException sent && sent.getValidSentAddresses() != null
                && sent.getValidSentAddresses().length > 0) return Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN);
        Exception current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            int code = current instanceof SMTPSendFailedException e ? e.getReturnCode()
                    : current instanceof SMTPAddressFailedException e ? e.getReturnCode()
                    : current instanceof SMTPSenderFailedException e ? e.getReturnCode() : 0;
            if (code >= 400 && code < 500) return Outcome.retryable(FailureCode.SMTP_TEMPORARY_REJECTION);
            if (code >= 500 && code < 600) return Outcome.failed(FailureCode.SMTP_PERMANENT_REJECTION);
            current = current instanceof MessagingException e ? e.getNextException() : null;
        }
        return Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN);
    }
}
