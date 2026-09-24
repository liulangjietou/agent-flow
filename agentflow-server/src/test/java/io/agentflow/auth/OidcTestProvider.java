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
    private final String publicIssuer;
    private final JsonUtil json = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    volatile String mode = "valid";
    volatile int exchanges;
    volatile String subject = "employee-42";
    volatile String tenant = "external";
    volatile int lifetimeSeconds = 300;
    volatile String sessionId;
    volatile int issuedSecondsAgo = 5;
    volatile boolean supportsLogout;
    volatile Object logoutEndpointOverride;
    volatile int logoutRequests;
    volatile String lastLogoutSubject;
    private final java.util.Set<String> logoutReturns = ConcurrentHashMap.newKeySet();

    OidcTestProvider() {
        this(null);
    }

    OidcTestProvider(String publicIssuer) {
        if (publicIssuer != null && !publicIssuer.startsWith("https://")) {
            throw new IllegalArgumentException("An external fixture issuer must use HTTPS");
        }
        this.publicIssuer = publicIssuer;
        try {
            key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException | JOSEException exception) { throw new IllegalStateException(exception); }
    }

    String issuer() { return publicIssuer == null ? listener() : publicIssuer; }

    private String listener() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestURI().getPath()) {
                case "/.well-known/openid-configuration" -> {
                    var metadata = new LinkedHashMap<String, Object>(Map.of(
                            "issuer", issuer(), "authorization_endpoint", issuer() + "/authorize", "token_endpoint", issuer() + "/token",
                            "jwks_uri", issuer() + "/jwks", "response_types_supported", List.of("code"),
                            "subject_types_supported", List.of("public"), "id_token_signing_alg_values_supported", List.of("RS256")));
                    if (supportsLogout) metadata.put("end_session_endpoint", logoutEndpointOverride == null ? issuer() + "/end-session" : logoutEndpointOverride);
                    respond(exchange, 200, metadata);
                }
                case "/end-session" -> endSession(exchange);
                case "/jwks" -> respond(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/authorize" -> authorize(exchange);
                case "/token" -> token(exchange);
                case "/logout-token" -> {
                    if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Map.of("error", "method")); break; }
                    var values = parameters(exchange.getRequestURI());
                    Instant now = Instant.now();
                    var claims = new LinkedHashMap<String, Object>(Map.of("iss", issuer(), "aud", List.of("platform"),
                            "iat", now.getEpochSecond(), "exp", now.plusSeconds(120).getEpochSecond(),
                            "jti", UUID.randomUUID().toString(), "events", Map.of(OidcLogoutTokenValidator.EVENT, Map.of())));
                    if (values.containsKey("sub")) claims.put("sub", values.get("sub"));
                    if (values.containsKey("sid")) claims.put("sid", values.get("sid"));
                    respond(exchange, 200, Map.of("logout_token", logoutToken(claims, false, "logout+jwt")));
                }
                case "/scenario" -> {
                    if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Map.of("error", "method")); break; }
                    Map<String, String> values = parameters(exchange.getRequestURI());
                    mode = values.getOrDefault("mode", "valid");
                    subject = values.getOrDefault("subject", "employee-42");
                    tenant = values.getOrDefault("tenant", "external");
                    lifetimeSeconds = Integer.parseInt(values.getOrDefault("seconds", "300"));
                    sessionId = values.get("sid");
                    issuedSecondsAgo = Integer.parseInt(values.getOrDefault("issuedSecondsAgo", "5"));
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
        URI callback = URI.create(values.get("redirect_uri"));
        logoutReturns.add(callback.getScheme() + "://" + callback.getRawAuthority() + "/");
        String code = UUID.randomUUID().toString();
        codes.put(code, values);
        exchange.getResponseHeaders().set("Location", values.get("redirect_uri") + "?code=" + code
                + "&state=" + URLEncoder.encode(values.get("state"), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(302, -1);
    }

    private void endSession(HttpExchange exchange) throws Exception {
        if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Map.of("error", "method")); return; }
        var values = parameters(URI.create("http://fixture/?" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        SignedJWT token = SignedJWT.parse(values.get("id_token_hint"));
        var claims = token.getJWTClaimsSet();
        String home = values.get("post_logout_redirect_uri");
        if (!token.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(key.toRSAPublicKey()))
                || !issuer().equals(claims.getIssuer()) || !claims.getAudience().contains("platform") || !logoutReturns.contains(home)) {
            respond(exchange, 400, Map.of("error", "invalid_logout")); return;
        }
        logoutRequests++;
        lastLogoutSubject = claims.getSubject();
        byte[] html = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>测试身份服务</title>"
                + "<h1>测试企业会话已退出</h1><p>已校验平台提交的身份令牌和返回地址。</p><a href=\""
                + org.springframework.web.util.HtmlUtils.htmlEscape(home) + "\">返回审批平台</a></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html;charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, html.length);
        exchange.getResponseBody().write(html);
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
                .issueTime(Date.from(now.minusSeconds(issuedSecondsAgo)))
                .expirationTime(Date.from(now.plusSeconds(mode.equals("expired") ? -120 : lifetimeSeconds)))
                .claim("nonce", mode.equals("nonce") ? "wrong-nonce" : authorization.get("nonce"))
                .claim("tenant", mode.equals("unmappedTenant") ? "unknown" : tenant)
                .claim("roles", mode.equals("unmappedRole") ? List.of("unmapped-admin") : List.of("staff"));
        if (sessionId != null) claims.claim("sid", sessionId);
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

    /** 用夹具密钥签发注销通知；异常声明由测试显式提供，绝不输出原始 JWT。 */
    String logoutToken(Map<String, Object> claims, boolean invalidSignature, String type) throws Exception {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
        if (type != null) header.type(new com.nimbusds.jose.JOSEObjectType(type));
        // 原样签名 JSON，避免 JWTClaimsSet 在发出前就替服务端拒绝故意构造的错误类型。
        var token = new com.nimbusds.jose.JWSObject(header.build(), new com.nimbusds.jose.Payload(json.write(claims)));
        token.sign(new RSASSASigner(invalidSignature ? wrongKey : key));
        return token.serialize();
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
        if ((args.length != 1 && args.length != 2) || !args[0].startsWith("/fyoung/tmp/")) throw new IllegalArgumentException("Expected a temporary evidence path");
        var provider = new OidcTestProvider(System.getenv("AGENTFLOW_TEST_PROVIDER_ISSUER"));
        provider.supportsLogout = args.length == 2 && "--logout".equals(args[1]);
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), provider.listener());
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
