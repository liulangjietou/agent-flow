package io.agentflow.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 用实际部署属性绑定验证单人收件、固定身份和凭据轮换边界。
 * @author owlzhangfq@gmail.com
 */
class WeComNotificationConfigurationTest {
    @Test void freezesEnterpriseApplicationAndRecipientButAllowsSecretRotation() {
        var original = destination(Map.of(), false);
        assertThat(original.enabled()).isTrue();
        assertThat(original.channel()).isEqualTo(NotificationChannel.ENTERPRISE_IM);
        assertThat(original.toString()).doesNotContain("User01", "fixture-secret", "fixture-corp");
        assertThat(destination(Map.of("wecom-apps.app.secret", "rotated-secret"), false).digest()).isEqualTo(original.digest());
        for (var change : Map.of("wecom-apps.app.corp-id", "different-corp", "wecom-apps.app.agent-id", "100002",
                "bindings.user-im.address", "User02", "public-url", "https://another.example.invalid/approval").entrySet()) {
            assertThat(destination(Map.of(change.getKey(), change.getValue()), false).digest()).isNotEqualTo(original.digest());
        }
        assertThat(destination(Map.of("wecom-apps.app.enabled", "false"), false).enabled()).isFalse();
        assertThat(destination(Map.of("bindings.user-im.enabled", "false"), false).enabled()).isFalse();
        assertThat(new NotificationDestinations(configuration(Map.of()), false).find("other", "alice", NotificationChannel.ENTERPRISE_IM)).isEmpty();
    }

    @Test void rejectsBroadcastCrossTenantAndCredentialRedirects() {
        for (String address : java.util.List.of("@all", "@ALL", "User01|User02", "User01,User02", "User01\nUser02", " User01"))
            invalid(Map.of("bindings.user-im.address", address), false);
        invalid(Map.of("wecom-apps.app.tenant-id", "other"), false);
        invalid(Map.of("wecom-apps.app.agent-id", "0"), false);
        invalid(Map.of("wecom-apps.app.secret", ""), false);
        for (String url : java.util.List.of("https://example.invalid", "https://qyapi.weixin.qq.com.evil.invalid", "https://user:secret@qyapi.weixin.qq.com",
                "https://qyapi.weixin.qq.com/other", "https://qyapi.weixin.qq.com?corpsecret=secret", "http://qyapi.weixin.qq.com"))
            invalid(Map.of("wecom-apps.app.base-url", url), false);
    }

    @Test void loopbackRequiresBothExplicitDemoSettingsAndOversizeNoticeIsRejected() {
        invalid(Map.of("wecom-apps.app.base-url", "http://127.0.0.1:18500"), true);
        var local = Map.of("wecom-apps.app.base-url", "http://127.0.0.1:18500", "allow-insecure-in-demo", "true");
        invalid(local, false);
        assertThat(destination(local, true).enabled()).isTrue();
        invalid(Map.of("wecom-apps.app.base-url", "http://localhost:18500", "allow-insecure-in-demo", "true"), true);
        invalid(Map.of("public-url", "https://approval.example.invalid/" + "路径".repeat(400)), false);
    }

    static NotificationDeliveryConfiguration configuration(Map<String, String> changes) {
        var values = new LinkedHashMap<String, String>();
        values.put("public-url", "https://approval.example.invalid/");
        values.put("wecom-apps.app.tenant-id", "demo"); values.put("wecom-apps.app.corp-id", "fixture-corp");
        values.put("wecom-apps.app.agent-id", "100001"); values.put("wecom-apps.app.secret", "fixture-secret");
        values.put("wecom-apps.app.enabled", "true");
        values.put("bindings.user-im.tenant-id", "demo"); values.put("bindings.user-im.recipient", "alice");
        values.put("bindings.user-im.channel", "ENTERPRISE_IM"); values.put("bindings.user-im.server-id", "app");
        values.put("bindings.user-im.address", "User01"); values.put("bindings.user-im.enabled", "true");
        values.putAll(changes);
        var properties = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> properties.put("agentflow.notifications." + key, value));
        return new Binder(new MapConfigurationPropertySource(properties)).bind("agentflow.notifications", Bindable.of(NotificationDeliveryConfiguration.class)).get();
    }
    static NotificationDestinations.Destination destination(Map<String, String> changes, boolean demo) {
        return new NotificationDestinations(configuration(changes), demo).find("demo", "alice", NotificationChannel.ENTERPRISE_IM).orElseThrow();
    }
    private static void invalid(Map<String, String> changes, boolean demo) {
        assertThatThrownBy(() -> destination(changes, demo)).hasMessage("Invalid notification delivery configuration");
    }
}
