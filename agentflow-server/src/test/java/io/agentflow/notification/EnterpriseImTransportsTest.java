package io.agentflow.notification;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.agentflow.notification.NotificationDeliveryConfiguration.Binding;
import static io.agentflow.notification.NotificationDeliveryConfiguration.DingTalkApp;
import static io.agentflow.notification.NotificationDeliveryConfiguration.FeishuApp;
import static io.agentflow.notification.NotificationDeliveryProgress.FailureCode;
import static io.agentflow.notification.NotificationDeliveryProgress.Result;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议级验证飞书与钉钉企业 IM 通知发送器的令牌缓存、过期自动刷新、成功受理与错误分类。
 * @author owlzhangfq@gmail.com
 */
class EnterpriseImTransportsTest {
    private HttpServer server;
    private String baseUrl;
    private AtomicInteger feishuTokenCalls;
    private AtomicInteger feishuSendCalls;
    private AtomicInteger dingtalkTokenCalls;
    private AtomicInteger dingtalkSendCalls;
    private final JsonUtil json = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setup() throws IOException {
        feishuTokenCalls = new AtomicInteger();
        feishuSendCalls = new AtomicInteger();
        dingtalkTokenCalls = new AtomicInteger();
        dingtalkSendCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", ex -> {
            int n = feishuTokenCalls.incrementAndGet();
            reply(ex, 200, "{\"code\":0,\"tenant_access_token\":\"fs_tok_" + n + "\",\"expire\":7200}");
        });
        server.createContext("/open-apis/im/v1/messages", ex -> {
            int n = feishuSendCalls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("bad_user")) {
                reply(ex, 200, "{\"code\":230001,\"msg\":\"invalid receive_id\"}");
                return;
            }
            if (body.contains("rate_limited")) {
                reply(ex, 429, "{\"code\":99991400,\"msg\":\"rate limited\"}");
                return;
            }
            if (n == 1) {
                reply(ex, 200, "{\"code\":99991663,\"msg\":\"token expired\"}");
                return;
            }
            reply(ex, 200, "{\"code\":0,\"data\":{\"message_id\":\"om_msg_001\"}}");
        });
        server.createContext("/gettoken", ex -> {
            int n = dingtalkTokenCalls.incrementAndGet();
            reply(ex, 200, "{\"errcode\":0,\"access_token\":\"dt_tok_" + n + "\",\"expires_in\":7200}");
        });
        server.createContext("/topapi/message/corpconversation/asyncsend_v2", ex -> {
            int n = dingtalkSendCalls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("bad_dt")) {
                reply(ex, 200, "{\"errcode\":40003,\"errmsg\":\"invalid userid\"}");
                return;
            }
            if (n == 1) {
                reply(ex, 200, "{\"errcode\":42001,\"errmsg\":\"token expired\"}");
                return;
            }
            reply(ex, 200, "{\"errcode\":0,\"task_id\":987654321}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void feishuTransportRefreshesExpiredTokenAndClassifiesFailures() {
        var config = new NotificationDeliveryConfiguration();
        config.setAllowInsecureInDemo(true);
        config.setPublicUrl("http://127.0.0.1:5173");
        config.setFeishuApps(Map.of("feishu-main", new FeishuApp("t1", "cli_app_1", "sec_1", baseUrl, true)));
        var bindings = new LinkedHashMap<String, Binding>();
        bindings.put("b1", new Binding("t1", "alice", NotificationChannel.ENTERPRISE_IM, "feishu-main", "ou_alice_1", true));
        bindings.put("b2", new Binding("t1", "bob", NotificationChannel.ENTERPRISE_IM, "feishu-main", "bad_user", true));
        bindings.put("b3", new Binding("t1", "carol", NotificationChannel.ENTERPRISE_IM, "feishu-main", "rate_limited", true));
        config.setBindings(bindings);

        var destinations = new NotificationDestinations(config, true);
        assertThat(destinations.enabledChannels("t1")).containsExactly(NotificationChannel.ENTERPRISE_IM);
        assertThat(destinations.configuredServers("t1")).extracting(NotificationDestinations.ChannelServer::id)
                .containsExactly("feishu-main");

        var transport = new FeishuNotificationTransport(json);
        var ok = transport.send(destinations.find("t1", "alice", NotificationChannel.ENTERPRISE_IM).orElseThrow());
        assertThat(ok.result()).isEqualTo(Result.ACCEPTED);
        assertThat(feishuTokenCalls.get()).isEqualTo(2);
        assertThat(feishuSendCalls.get()).isEqualTo(2);

        var bad = transport.send(destinations.find("t1", "bob", NotificationChannel.ENTERPRISE_IM).orElseThrow());
        assertThat(bad.result()).isEqualTo(Result.FAILED);
        assertThat(bad.code()).isEqualTo(FailureCode.IM_RECIPIENT_REJECTED);

        var limited = transport.send(destinations.find("t1", "carol", NotificationChannel.ENTERPRISE_IM).orElseThrow());
        assertThat(limited.result()).isEqualTo(Result.RETRYABLE);
        assertThat(limited.code()).isEqualTo(FailureCode.IM_TEMPORARY_REJECTION);
    }

    @Test
    void dingtalkTransportRefreshesExpiredTokenAndRejectsInvalidRecipient() {
        var config = new NotificationDeliveryConfiguration();
        config.setAllowInsecureInDemo(true);
        config.setPublicUrl("http://127.0.0.1:5173");
        config.setDingtalkApps(Map.of("dt-main", new DingTalkApp("t1", "ding_key_1", "sec_1", 10001L, baseUrl, true)));
        config.setBindings(Map.of(
                "d1", new Binding("t1", "alice", NotificationChannel.ENTERPRISE_IM, "dt-main", "userid_alice", true),
                "d2", new Binding("t1", "bob", NotificationChannel.ENTERPRISE_IM, "dt-main", "bad_dt", true)));

        var destinations = new NotificationDestinations(config, true);
        var transport = new DingTalkNotificationTransport(json);

        var ok = transport.send(destinations.find("t1", "alice", NotificationChannel.ENTERPRISE_IM).orElseThrow());
        assertThat(ok.result()).isEqualTo(Result.ACCEPTED);
        assertThat(dingtalkTokenCalls.get()).isEqualTo(2);
        assertThat(dingtalkSendCalls.get()).isEqualTo(2);

        var bad = transport.send(destinations.find("t1", "bob", NotificationChannel.ENTERPRISE_IM).orElseThrow());
        assertThat(bad.result()).isEqualTo(Result.FAILED);
        assertThat(bad.code()).isEqualTo(FailureCode.IM_RECIPIENT_REJECTED);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
