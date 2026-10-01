package io.agentflow.notification;

/** 个人设置按租户和稳定接收人身份隔离，读取默认值没有写入副作用。 @author owlzhangfq@gmail.com */
public interface NotificationPreferencesRepository {
    /** 只返回指定身份的设置或全关闭默认值。 */
    NotificationPreferences get(String tenant, String recipient);
    /** 保存版本变化和同事务历史，竞争的旧版本不能覆盖新设置。 */
    void save(NotificationPreferences previous, NotificationPreferences current);
}
