package io.agentflow.system;

import io.agentflow.auth.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过真实 HTTP 验证独立采集凭证、业务权限隔离以及指标标签的数据边界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:metrics-scrape;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.monitoring.enabled=true",
        "management.prometheus.metrics.export.enabled=true"})
class MetricsScrapeIntegrationTest {
    private static final String TOKEN = "metrics-test-only-abcdefghijklmnopqrstuvwxyz-0123456789";
    private static Path tokenFile;
    @LocalServerPort int port;
    @Autowired AuthService auth;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        tokenFile = Files.createTempFile(Path.of("/fyoung/tmp"), "metrics-token-", ".txt");
        Files.writeString(tokenFile, TOKEN + "\n");
        registry.add("agentflow.monitoring.token-file", tokenFile::toString);
    }

    @AfterAll
    static void removeToken() throws Exception { Files.deleteIfExists(tokenFile); }

    @Test
    void scrapeRequiresItsOwnCredentialAndCannotGrantBusinessAccess() throws Exception {
        assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", "wrong").statusCode()).isEqualTo(401);
        String admin = auth.login("demo", "admin", "demo").token();
        assertThat(get("/actuator/prometheus", admin).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/applications", TOKEN).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env", TOKEN).statusCode()).isEqualTo(401);
        var response = get("/actuator/prometheus", TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("jvm_memory_used_bytes", "hikaricp_connections_max")
                .doesNotContain(TOKEN, admin);
        var post = client.send(HttpRequest.newBuilder(uri("/actuator/prometheus"))
                .header("Authorization", "Bearer " + TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
    }

    @Test
    void encodedEndpointCannotFallBackToBusinessAuthentication() throws Exception {
        String admin = auth.login("demo", "admin", "demo").token();
        assertThat(get("/actuator/%70rometheus", admin).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/%70rometheus", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test
    void metricsUseRouteTemplatesWithoutUserIdsQueriesOrTokens() throws Exception {
        String employee = auth.login("demo", "employee", "demo").token();
        get("/api/v1/applications/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee?private=secret-query", employee);
        get("/not-found-private-path?private=secret-query", employee);
        String metrics = get("/actuator/prometheus", TOKEN).body();
        assertThat(metrics).contains("uri=\"/api/v1/applications/{id}\"", "http_server_requests_seconds_bucket")
                .doesNotContain("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "secret-query", "not-found-private-path", employee);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
