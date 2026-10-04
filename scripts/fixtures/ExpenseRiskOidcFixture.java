package io.agentflow.auth;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 风险解释安装包验收的回环身份源，只签发固定合成人员的授权码。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseRiskOidcFixture {
    /** 启动真实 PKCE、签名和 JWKS 夹具，原会话可跨应用进程恢复。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !args[0].startsWith("/fyoung/tmp/")
                || args.length == 2 && !"bob-approver".equals(args[1])) {
            throw new IllegalArgumentException("Expected owned temporary metadata path");
        }
        var json = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
        var provider = new OidcTestProvider();
        var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var roles = Map.of("admin", List.of("admins", "designers"), "alice", List.of("staff"),
                "manager", List.of("staff", "reviewers"), "finance", List.of("staff", "reviewers", "accountants"),
                "bob", args.length == 2 ? List.of("staff", "reviewers") : List.of("staff"));
        control.createContext("/actor", exchange -> {
            try {
                String query = exchange.getRequestURI().getRawQuery();
                String subject = query != null && query.startsWith("subject=") ? query.substring(8) : "";
                boolean valid = "POST".equals(exchange.getRequestMethod()) && roles.containsKey(subject);
                if (valid) {
                    provider.subject = subject; provider.tenant = "external"; provider.roles = roles.get(subject);
                    provider.lifetimeSeconds = 3600; provider.sessionId = java.util.UUID.randomUUID().toString();
                }
                byte[] body = json.write(Map.of("configured", valid)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        control.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { control.stop(0); provider.close(); }));
        Files.writeString(Path.of(args[0]), json.write(Map.of("issuer", provider.issuer(),
                "control", "http://127.0.0.1:" + control.getAddress().getPort())));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
