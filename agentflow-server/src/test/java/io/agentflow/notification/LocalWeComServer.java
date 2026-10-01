package io.agentflow.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** 本机真实 HTTP 协议夹具；请求只保留在测试内存，不写凭据日志。 @author owlzhangfq@gmail.com */
final class LocalWeComServer implements AutoCloseable {
    static final String TOKEN = "{\"errcode\":0,\"access_token\":\"fixture-token\",\"expires_in\":7200}";
    static final String ACCEPT = "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"fixture-message\"}";
    final ConcurrentLinkedQueue<Reply> tokenReplies = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Reply> messageReplies = new ConcurrentLinkedQueue<>();
    final List<Request> tokenRequests = new CopyOnWriteArrayList<>();
    final List<Request> messages = new CopyOnWriteArrayList<>();
    final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    LocalWeComServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.createContext("/", this::handle); server.start();
    }
    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath(); Reply response;
            if (path.equals("/cgi-bin/gettoken")) { tokenRequests.add(request); response = tokenReplies.poll(); if (response == null) response = Reply.json(TOKEN); }
            else if (path.equals("/cgi-bin/message/send")) { messages.add(request); response = messageReplies.poll(); if (response == null) response = Reply.json(ACCEPT); }
            else { unexpected.add(path); response = new Reply(404, "{}", 0); }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (response.status == 302) exchange.getResponseHeaders().set("Location", baseUrl() + "/redirected");
            byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status, bytes.length);
            if (response.delayMillis > 0) {
                try { Thread.sleep(response.delayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            exchange.getResponseBody().write(bytes);
        }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    record Reply(int status, String body, long delayMillis) { static Reply json(String body) { return new Reply(200, body, 0); } }
    record Request(String method, String query, String body) {
        @Override public String toString() { return "WeComFixtureRequest[redacted]"; }
    }
}
