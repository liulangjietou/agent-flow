package io.agentflow.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.common.JsonUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回环身份服务夹具：真实授权码、PKCE、RSA 签名和 JWKS，不冒充真实企业身份源验收。
 * @author owlzhangfq@gmail.com
 */
public final class OidcTestProvider implements AutoCloseable {
    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey wrongKey;
    private final JsonUtil json = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    volatile String mode = "valid";
    volatile int exchanges;
    volatile String subject = "employee-42";
    volatile String tenant = "external";
    volatile int lifetimeSeconds = 300;

    OidcTestProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException | JOSEException exception) { throw new IllegalStateException(exception); }
    }

    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestURI().getPath()) {
                case "/.well-known/openid-configuration" -> respond(exchange, 200, Map.of(
                        "issuer", issuer(), "authorization_endpoint", issuer() + "/authorize", "token_endpoint", issuer() + "/token",
                        "jwks_uri", issuer() + "/jwks", "response_types_supported", List.of("code"),
                        "subject_types_supported", List.of("public"), "id_token_signing_alg_values_supported", List.of("RS256")));
                case "/jwks" -> respond(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/authorize" -> authorize(exchange);
                case "/token" -> token(exchange);
                case "/scenario" -> {
                    if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Map.of("error", "method")); break; }
                    Map<String, String> values = parameters(exchange.getRequestURI());
                    mode = values.getOrDefault("mode", "valid");
                    subject = values.getOrDefault("subject", "employee-42");
                    tenant = values.getOrDefault("tenant", "external");
                    lifetimeSeconds = Integer.parseInt(values.getOrDefault("seconds", "300"));
                    respond(exchange, 200, Map.of("status", "fixture_updated"));
                }
                default -> respond(exchange, 404, Map.of("error", "not_found"));
            }
        } catch (Exception exception) {
            respond(exchange, 500, Map.of("error", "fixture_failure"));
        } finally { exchange.close(); }
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> values = parameters(exchange.getRequestURI());
        if (!"code".equals(values.get("response_type")) || !"S256".equals(values.get("code_challenge_method"))
                || values.get("nonce") == null || values.get("state") == null) {
            respond(exchange, 400, Map.of("error", "invalid_request")); return;
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, values);
        exchange.getResponseHeaders().set("Location", values.get("redirect_uri") + "?code=" + code
                + "&state=" + URLEncoder.encode(values.get("state"), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws Exception {
        exchanges++;
        Map<String, String> values = parameters(URI.create("http://fixture/?" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Map<String, String> authorization = codes.remove(values.get("code"));
        String expectedClient = "Basic " + Base64.getEncoder().encodeToString("platform:fixture-secret".getBytes(StandardCharsets.UTF_8));
        String verifier = values.getOrDefault("code_verifier", "");
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        if (authorization == null || !expectedClient.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                || !"authorization_code".equals(values.get("grant_type")) || !challenge.equals(authorization.get("code_challenge"))
                || !authorization.get("redirect_uri").equals(values.get("redirect_uri"))) {
            respond(exchange, 400, Map.of("error", "invalid_grant")); return;
        }
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(mode.equals("issuer") ? "https://wrong.invalid" : issuer())
                .subject(subject).audience(mode.equals("audience") ? "other-client" : "platform")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(mode.equals("expired") ? -120 : lifetimeSeconds)))
                .claim("nonce", mode.equals("nonce") ? "wrong-nonce" : authorization.get("nonce"))
                .claim("tenant", mode.equals("unmappedTenant") ? "unknown" : tenant)
                .claim("roles", mode.equals("unmappedRole") ? List.of("unmapped-admin") : List.of("staff"));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(mode.equals("signature") ? wrongKey : key));
        respond(exchange, 200, Map.of("access_token", "fixture-unused-access", "refresh_token", "fixture-unused-refresh",
                "token_type", "Bearer", "expires_in", 300, "id_token", jwt.serialize()));
    }

    static Map<String, String> parameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private void respond(HttpExchange exchange, int code, Object payload) throws IOException {
        byte[] bytes = json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** 测试完成关闭监听端口。 */
    @Override
    public void close() { server.stop(0); }

    /** 仅从测试 classpath 启动浏览器验收夹具，输出位置必须由调用方明确指定。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].startsWith("/fyoung/tmp/")) throw new IllegalArgumentException("Expected a temporary evidence path");
        var provider = new OidcTestProvider();
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), provider.issuer());
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
