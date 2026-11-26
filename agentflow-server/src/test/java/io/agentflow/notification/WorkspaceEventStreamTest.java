package io.agentflow.notification;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证工作台 SSE 实时通道需要认证、租户隔离且单用户连接数有界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workspace-events;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true"
})
@AutoConfigureMockMvc
class WorkspaceEventStreamTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;

    @Test
    void requiresAuthenticationAndStreamsConnectedEventWithNoStoreHeaders() throws Exception {
        mvc.perform(get("/api/v1/events/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());

        String token = auth.login("demo", "alice", "demo").token();
        mvc.perform(get("/api/v1/events/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"));
    }

    @Test
    void capsConnectionsPerUserAndIsolatesTenants() {
        var publisher = new WorkspaceEventPublisher(json);
        var aliceDemo = new Actor("demo", "alice", Set.of());
        var aliceOther = new Actor("other", "alice", Set.of());
        for (int i = 0; i < 7; i++) publisher.subscribe(aliceDemo);
        publisher.subscribe(aliceOther);
        assertThat(publisher.activeConnections("demo", "alice")).isEqualTo(5);
        assertThat(publisher.activeConnections("other", "alice")).isEqualTo(1);
    }
}
