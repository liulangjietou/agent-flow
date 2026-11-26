package io.agentflow.notification;

import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证管理员动态维护员工通知收件绑定无需重启即可生效，且遵守角色授权、租户隔离和摘要防篡改约束。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-bindings;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true",
        "agentflow.notifications.public-url=https://approval.example.invalid/",
        "agentflow.notifications.smtp-servers.mail.tenant-id=demo",
        "agentflow.notifications.smtp-servers.mail.host=smtp.example.invalid",
        "agentflow.notifications.smtp-servers.mail.port=587",
        "agentflow.notifications.smtp-servers.mail.security=STARTTLS",
        "agentflow.notifications.smtp-servers.mail.username=sender",
        "agentflow.notifications.smtp-servers.mail.password=secret",
        "agentflow.notifications.smtp-servers.mail.from=notify@example.invalid",
        "agentflow.notifications.smtp-servers.mail.enabled=true"
})
@AutoConfigureMockMvc
class NotificationBindingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired NotificationDestinations destinations;

    @Test
    void adminCanManageDynamicRecipientBindingsWithoutRestartAndNonAdminIsForbidden() throws Exception {
        String adminToken = auth.login("demo", "admin", "demo").token();
        String aliceToken = auth.login("demo", "alice", "demo").token();

        mvc.perform(get("/api/v1/notifications/bindings").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());

        assertThat(destinations.find("demo", "bob", NotificationChannel.EMAIL)).isEmpty();
        var body = Map.of("id", "bob-email", "recipientId", "bob", "channel", "EMAIL",
                "serverId", "mail", "address", "bob@example.invalid", "enabled", true, "expectedVersion", 0);
        mvc.perform(put("/api/v1/notifications/bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("bob-email"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.address").value("bob@example.invalid"));

        var resolved = destinations.find("demo", "bob", NotificationChannel.EMAIL).orElseThrow();
        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.address()).isEqualTo("bob@example.invalid");
        assertThat(destinations.enabledChannels("demo")).contains(NotificationChannel.EMAIL);

        mvc.perform(get("/api/v1/notifications/bindings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].id").value("mail"))
                .andExpect(jsonPath("$.bindings[0].id").value("bob-email"));

        var updated = Map.of("id", "bob-email", "recipientId", "bob", "channel", "EMAIL",
                "serverId", "mail", "address", "bob-new@example.invalid", "enabled", true, "expectedVersion", 1);
        mvc.perform(put("/api/v1/notifications/bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        var updatedResolved = destinations.find("demo", "bob", NotificationChannel.EMAIL).orElseThrow();
        assertThat(updatedResolved.digest()).isNotEqualTo(resolved.digest());

        mvc.perform(delete("/api/v1/notifications/bindings/bob-email")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(destinations.find("demo", "bob", NotificationChannel.EMAIL)).isEmpty();
    }
}
