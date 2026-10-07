package io.agentflow.servicetask;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 明确的本机合成服务：原号只产生一次效果，能模拟效果发生后回执丢失和真实原号查询。
 * @author owlzhangfq@gmail.com
 */
public final class ServiceTaskTestProvider implements AutoCloseable {
    final java.util.List<String> traceIds = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Map<UUID, ServiceTaskCommand> commands = new ConcurrentHashMap<>();
    final Map<UUID, ServiceTaskObservation> observations = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger effects = new AtomicInteger();
    final AtomicBoolean loseNextExecuteResponse = new AtomicBoolean();
    volatile ServiceTaskObservation.Status nextStatus = ServiceTaskObservation.Status.APPLIED;
    volatile Mode mode = Mode.NORMAL;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final JsonUtil json;

    /** 启动独立本机端点，供服务任务和费用审批的真实 HTTP 集成场景复用。 */
    public ServiceTaskTestProvider(JsonUtil json) throws IOException {
        this.json = json;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.setExecutor(executor); server.start();
    }

    /** 生成绑定当前端点的可信操作声明，不向流程图暴露地址和凭据。 */
    public ServiceTaskGatewayConfiguration.Operation declaration(String key) {
        var value = new ServiceTaskGatewayConfiguration.Operation();
        value.setKey(key); value.setVersion(1); value.setName("登记合成凭据");
        value.setParameters(List.of(new ServiceTaskContract.Parameter("memo", ServiceTaskContract.Type.TEXT, true, true)));
        value.setEndpoint(endpoint()); value.setToken("synthetic-service-token"); return value;
    }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

    public int effectCount() { return effects.get(); }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = json.read(body, JsonNode.class); String path = exchange.getRequestURI().getPath();
        traceIds.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
        calls.add(new Call(path, request, exchange.getRequestHeaders().getFirst("Idempotency-Key"), exchange.getRequestHeaders().getFirst("Authorization")));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (mode == Mode.REDIRECT) { exchange.getResponseHeaders().set("Location", endpoint() + "redirect-target"); exchange.sendResponseHeaders(302, -1); exchange.close(); return; }
        if (mode == Mode.TOO_LARGE) { respond(exchange, "x".repeat(16385)); return; }
        if (mode == Mode.SLOW_BODY) {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close(); return;
        }
        ServiceTaskCommand command; UUID id; String digest; String key; long version; String contractDigest; String tenant;
        if (path.equals("/execute")) {
            command = json.read(request.path("command").toString(), ServiceTaskCommand.class);
            id = command.id(); digest = command.digest(); key = command.contract().key(); version = command.contract().version(); contractDigest = command.contract().digest(); tenant = command.tenantId();
            if (!digest.equals(request.path("commandDigest").asText()) || !id.toString().equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))) {
                exchange.sendResponseHeaders(409, -1); exchange.close(); return;
            }
            var previous = commands.putIfAbsent(id, command);
            if (previous != null && !previous.equals(command)) { exchange.sendResponseHeaders(409, -1); exchange.close(); return; }
            if (previous == null) {
                boolean terminal = nextStatus == ServiceTaskObservation.Status.APPLIED || nextStatus == ServiceTaskObservation.Status.REJECTED;
                observations.put(id, new ServiceTaskObservation(id, digest, nextStatus, terminal ? "receipt-" + id : null, terminal ? Instant.now() : null));
                if (nextStatus == ServiceTaskObservation.Status.APPLIED) effects.incrementAndGet();
            }
            if (loseNextExecuteResponse.getAndSet(false)) { exchange.close(); return; }
        } else {
            id = UUID.fromString(request.path("operationId").asText()); digest = request.path("commandDigest").asText(); key = request.path("operationKey").asText();
            version = request.path("operationVersion").asLong(); contractDigest = request.path("contractDigest").asText(); tenant = request.path("tenantId").asText();
        }
        var observation = observations.getOrDefault(id, new ServiceTaskObservation(id, digest, ServiceTaskObservation.Status.NOT_FOUND, null, null));
        String response = json.write(Map.of("protocolVersion", 1, "tenantId", tenant, "operationKey", key, "operationVersion", version, "contractDigest", contractDigest, "observation", observation));
        if (mode == Mode.WRONG_TENANT) response = response.replace("\"tenantId\":\"demo\"", "\"tenantId\":\"foreign\"");
        if (mode == Mode.DUPLICATE_FIELD) response = response.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1");
        respond(exchange, response);
    }
    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    /** @author owlzhangfq@gmail.com */
    enum Mode { NORMAL, REDIRECT, TOO_LARGE, SLOW_BODY, WRONG_TENANT, DUPLICATE_FIELD }
    /** @author owlzhangfq@gmail.com */
    record Call(String path, JsonNode request, String idempotencyKey, String authorization) { }
}
