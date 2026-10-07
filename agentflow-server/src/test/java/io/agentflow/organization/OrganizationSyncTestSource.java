package io.agentflow.organization;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 真实回环只读来源夹具，同时用于传输边界和完整管理员用例，不代表企业来源验收。
 * @author owlzhangfq@gmail.com
 */
final class OrganizationSyncTestSource implements AutoCloseable {
    final java.util.List<String> traceIds = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile Mode mode = Mode.NORMAL;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    OrganizationSyncTestSource(JsonUtil json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
        server.createContext("/changes", exchange -> {
            try {
                var query = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&")).map(part -> part.split("=", 2))
                        .collect(Collectors.toMap(pair -> decoded(pair[0]), pair -> decoded(pair[1])));
                traceIds.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
                calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query,
                        exchange.getRequestHeaders().getFirst("Authorization"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Mode selected = mode; entered.countDown();
                if (selected == Mode.HOLD) release.await(10, TimeUnit.SECONDS);
                if (selected == Mode.REDIRECT) { exchange.getResponseHeaders().set("Location", endpoint() + "redirected"); exchange.sendResponseHeaders(302, -1); return; }
                if (selected == Mode.HTTP_ERROR) { exchange.sendResponseHeaders(503, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", selected == Mode.WRONG_CONTENT_TYPE ? "text/plain" : "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                if (selected == Mode.TOO_LARGE) {
                    exchange.getResponseBody().write(new byte[OrganizationSyncCodec.MAX_BYTES + 1]); exchange.getResponseBody().flush();
                    // 不结束正文：客户端必须在流式上限处失败，不能等 EOF 或请求超时才发现。
                    release.await(10, TimeUnit.SECONDS); return;
                }
                if (selected == Mode.SLOW_BODY) {
                    exchange.getResponseBody().write("{\"contractVersion\":1,".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                    release.await(10, TimeUnit.SECONDS); return;
                }
                if (selected == Mode.INVALID_UTF8) { exchange.getResponseBody().write(new byte[] {(byte) 0xc3, 0x28}); return; }
                ObjectNode body = json.read("{\"contractVersion\":1,\"tenantId\":\"\",\"sourceKey\":\"\",\"afterRevision\":0,\"revision\":1,\"units\":[],\"people\":[{\"externalId\":\"person\",\"subject\":\"source-subject\",\"displayName\":\"来源人员\",\"active\":true,\"approvalEligible\":true}],\"appointments\":[]}", ObjectNode.class);
                long after = Long.parseLong(query.get("afterRevision")); body.put("tenantId", selected == Mode.WRONG_TENANT ? "foreign" : query.get("tenantId"));
                body.put("sourceKey", selected == Mode.WRONG_SOURCE ? "other" : query.get("sourceKey"));
                body.put("afterRevision", selected == Mode.WRONG_CURSOR ? after + 1 : after); body.put("revision", after + 1);
                if (selected == Mode.ROLE_FIELD) ((ObjectNode) body.at("/people/0")).putArray("roles").add("ADMIN");
                String text = json.write(body);
                if (selected == Mode.DUPLICATE) text = text.replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1");
                if (selected == Mode.TRAILING) text += " {}";
                exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException disconnected) { /* 取消、超时和超量用例主动关闭连接。 */ }
            finally { exchange.close(); }
        });
        server.createContext("/redirected", exchange -> { calls.add(new Call("REDIRECTED", "/redirected", Map.of(), null, "")); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    OrganizationSyncConfiguration.Target target() {
        var target = new OrganizationSyncConfiguration.Target(); target.setSourceKey("hr"); target.setEndpoint(endpoint()); target.setToken("synthetic-source-token"); target.setTimeoutSeconds(3); return target;
    }
    private static String decoded(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    @Override public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }

    /**
     * 每种故障均通过真实 HTTP 传输返回。
     * @author owlzhangfq@gmail.com
     */
    enum Mode { NORMAL, HOLD, REDIRECT, HTTP_ERROR, TOO_LARGE, SLOW_BODY, INVALID_UTF8, WRONG_CONTENT_TYPE, WRONG_TENANT, WRONG_SOURCE, WRONG_CURSOR, ROLE_FIELD, DUPLICATE, TRAILING }
    /**
     * 夹具只记录合成请求，便于断言没有业务写入或重定向。
     * @author owlzhangfq@gmail.com
     */
    record Call(String method, String path, Map<String, String> query, String authorization, String body) { }
}
