package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 实际认证、文件系统与数据库验证个人票夹恢复、配额并发和管理员隔离；原件均为合成夹具。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.attachments.max-file-bytes=1024",
        "agentflow.invoices.max-wallet-bytes=1048576", "agentflow.invoices.max-wallet-uploads=100"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InvoiceWalletIntegrationTest {
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-invoice-wallet-" + UUID.randomUUID());
    private static final byte[] PDF = "%PDF-1.7\nsynthetic-original\n%%EOF".getBytes(StandardCharsets.UTF_8);
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_INVOICE_TEST_URL", "jdbc:h2:mem:invoice-wallet;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_INVOICE_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_INVOICE_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_INVOICE_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired CurrentActor actors;
    @Autowired InvoiceWalletService wallet;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registrationReplaysWithoutAnotherInvoiceOrCapacityChargeAndCreatesNoApplication() throws Exception {
        int applications = count("approval_application"); int originals = count("invoice_original");
        String key = UUID.randomUUID().toString(); var input = input(PDF, InvoiceOriginal.Format.PDF);
        var first = register("alice", key, input, 201); var repeated = register("alice", key, input, 201);
        assertThat(repeated.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(repeated.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("invoice_original")).isEqualTo(originals + 1);
        assertThat(count("approval_application")).isEqualTo(applications);
        var item = getItem(id(first), "alice", 200);
        assertThat(item.path("verification").asText()).isEqualTo("PENDING");
        assertThat(item.at("/original/status").asText()).isEqualTo("UPLOADING");
        assertThat(item.has("facts")).isFalse();
        assertThat(download(id(first), "alice").getStatus()).isEqualTo(422);
    }

    @Test
    void sameIdentityRecoversFromInterruptedOrWrongBytesAndReadyCannotBeDowngraded() throws Exception {
        String invoice = reserve(PDF, InvoiceOriginal.Format.PDF);
        assertThat(upload(invoice, "alice", new byte[]{1}).getStatus()).isEqualTo(422);
        assertThat(getItem(invoice, "alice", 200).at("/original/status").asText()).isEqualTo("FAILED");
        for (int retry = 0; retry < 2; retry++) assertThat(upload(invoice, "alice", PDF).getStatus()).isEqualTo(200);
        assertThat(upload(invoice, "alice", new byte[]{1}).getStatus()).isEqualTo(422);
        var item = getItem(invoice, "alice", 200);
        assertThat(item.at("/original/status").asText()).isEqualTo("READY");
        assertThat(item.path("verification").asText()).isEqualTo("PENDING");
        var downloaded = download(invoice, "alice");
        assertThat(downloaded.getStatus()).isEqualTo(200); assertThat(downloaded.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(downloaded.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(downloaded.getHeader("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(downloaded.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(downloaded.getHeader("Cache-Control")).isEqualTo("no-store");
        try (var paths = Files.list(DIRECTORY)) { assertThat(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue(); }
    }

    @Test
    void ownerAndTenantAreRequiredForMetadataUploadAndDownloadEvenForAdmin() throws Exception {
        String invoice = reserve(PDF, InvoiceOriginal.Format.PDF);
        assertThat(upload(invoice, "alice", PDF).getStatus()).isEqualTo(200);
        for (String other : List.of("bob", "admin")) {
            getItem(invoice, other, 404); assertThat(download(invoice, other).getStatus()).isEqualTo(404);
            assertThat(upload(invoice, other, PDF).getStatus()).isEqualTo(404);
        }
        actors.set(new Actor("different-tenant", "alice", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> wallet.get(UUID.fromString(invoice))).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND")); }
        finally { actors.clear(); }
        assertThat(mvc.perform(get("/api/v1/invoices/" + invoice)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void supportedFormatSignaturesAndOfdContainerAreCheckedBeforePublishing() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        for (var entry : Map.of(InvoiceOriginal.Format.PDF, PDF, InvoiceOriginal.Format.PNG, png,
                InvoiceOriginal.Format.JPEG, jpeg, InvoiceOriginal.Format.OFD, ofd(true)).entrySet()) {
            String invoice = reserve(entry.getValue(), entry.getKey());
            assertThat(upload(invoice, "alice", entry.getValue()).getStatus()).isEqualTo(200);
        }
        for (byte[] wrong : List.of("<html>not a document</html>".getBytes(StandardCharsets.UTF_8), ofd(false))) {
            var format = wrong[0] == '<' ? InvoiceOriginal.Format.PDF : InvoiceOriginal.Format.OFD;
            String invoice = reserve(wrong, format);
            var response = upload(invoice, "alice", wrong);
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(tree(response).path("code").asText()).isEqualTo("INVOICE_ORIGINAL_FORMAT_MISMATCH");
            assertThat(getItem(invoice, "alice", 200).at("/original/status").asText()).isEqualTo("FAILED");
        }
    }

    @Test
    void contentLengthCannotBypassActualStreamLimitAndCorruptionCannotBeDownloaded() throws Exception {
        String invoice = reserve(PDF, InvoiceOriginal.Format.PDF);
        assertThat(upload(invoice, "alice", new byte[2048]).getStatus()).isEqualTo(413);
        assertThat(upload(invoice, "alice", PDF).getStatus()).isEqualTo(200);
        var item = getItem(invoice, "alice", 200);
        Path stored = DIRECTORY.resolve(item.at("/original/id").asText() + ".bin");
        Files.write(stored, new byte[]{1, 2});
        assertThat(download(invoice, "alice").getStatus()).isEqualTo(503);
        assertThat(upload(invoice, "alice", PDF).getStatus()).isEqualTo(503);
        assertThat(Files.readAllBytes(stored)).containsExactly(1, 2);
    }

    @Test
    void listIsBoundedOwnerScopedAndRejectsIdentityOverrides() throws Exception {
        for (int index = 0; index < 4; index++) reserve(PDF, InvoiceOriginal.Format.PDF);
        var first = tree(mvc.perform(get("/api/v1/invoices?limit=2").header("Authorization", token("alice"))).andReturn().getResponse());
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextBeforeId").asText(); assertThat(cursor).isNotBlank();
        var second = tree(mvc.perform(get("/api/v1/invoices?limit=2&beforeId=" + cursor).header("Authorization", token("alice"))).andReturn().getResponse());
        assertThat(second.at("/items/0/id").asText()).isNotIn(first.at("/items/0/id").asText(), first.at("/items/1/id").asText());
        for (String query : List.of("tenantId=other", "ownerId=alice", "limit=0", "limit=101", "beforeId=broken")) {
            var response = mvc.perform(get("/api/v1/invoices?" + query).header("Authorization", token("alice"))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(400); assertThat(tree(response).path("code").asText()).isEqualTo("INVALID_INVOICE_QUERY");
        }
        var admin = tree(mvc.perform(get("/api/v1/invoices").header("Authorization", token("admin"))).andReturn().getResponse());
        assertThat(admin.path("items")).isEmpty();
    }

    @Test
    void concurrentCapacityReservationAllowsOnlyOneLastSlotAndRollsBackLosingInvoice() throws Exception {
        String owner = "quota-" + UUID.randomUUID();
        jdbc.update("INSERT INTO invoice_wallet(tenant_id,owner_id,used_bytes,upload_count) VALUES('demo',?,?,99)", owner, 1048576 - PDF.length);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1); var ready = new CountDownLatch(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 2; index++) futures.add(pool.submit(() -> {
                actors.set(new Actor("demo", owner, Set.of("EMPLOYEE")));
                try {
                    ready.countDown(); start.await(5, TimeUnit.SECONDS); wallet.reserve(input(PDF, InvoiceOriginal.Format.PDF)); return true;
                } catch (DomainException conflict) { assertThat(conflict.code()).isEqualTo("INVOICE_WALLET_QUOTA_EXCEEDED"); return false; }
                finally { actors.clear(); }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_original WHERE tenant_id='demo' AND owner_id=?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource WHERE tenant_id='demo' AND owner_id=?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_bytes FROM invoice_wallet WHERE tenant_id='demo' AND owner_id=?", Long.class, owner)).isEqualTo(1048576);
    }

    @Test
    void unsafeNamesAndUnsupportedMetadataCreateNoPartialResource() throws Exception {
        int before = count("invoice_original");
        for (String name : List.of("../file.pdf", "dir\\file.pdf", "header\r\ninjection.pdf")) {
            register("alice", UUID.randomUUID().toString(), new InvoiceWalletService.UploadInput(name, (long) PDF.length, digest(PDF), InvoiceOriginal.Format.PDF), 400);
        }
        register("alice", UUID.randomUUID().toString(), new InvoiceWalletService.UploadInput("x.pdf", 0L, digest(PDF), InvoiceOriginal.Format.PDF), 400);
        register("alice", UUID.randomUUID().toString(), new InvoiceWalletService.UploadInput("x.pdf", 2048L, digest(PDF), InvoiceOriginal.Format.PDF), 413);
        assertThat(count("invoice_original")).isEqualTo(before);
    }

    private String reserve(byte[] bytes, InvoiceOriginal.Format format) throws Exception { return id(register("alice", UUID.randomUUID().toString(), input(bytes, format), 201)); }
    private InvoiceWalletService.UploadInput input(byte[] bytes, InvoiceOriginal.Format format) throws Exception { return new InvoiceWalletService.UploadInput("合成原件." + format.name().toLowerCase(), (long) bytes.length, digest(bytes), format); }
    private MockHttpServletResponse register(String user, String key, Object body, int expected) throws Exception {
        var response = mvc.perform(post("/api/v1/invoices").header("Authorization", token(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.write(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected); return response;
    }
    private MockHttpServletResponse upload(String id, String user, byte[] content) throws Exception {
        return mvc.perform(put("/api/v1/invoices/" + id + "/content").header("Authorization", token(user)).contentType("application/octet-stream").content(content)).andReturn().getResponse();
    }
    private JsonNode getItem(String id, String user, int expected) throws Exception {
        var response = mvc.perform(get("/api/v1/invoices/" + id).header("Authorization", token(user))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expected); return tree(response);
    }
    private MockHttpServletResponse download(String id, String user) throws Exception { return mvc.perform(get("/api/v1/invoices/" + id + "/content").header("Authorization", token(user))).andReturn().getResponse(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private String id(MockHttpServletResponse response) throws Exception { return tree(response).path("id").asText(); }
    private JsonNode tree(MockHttpServletResponse response) throws Exception { return json.read(response.getContentAsString(StandardCharsets.UTF_8), JsonNode.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] ofd(boolean descriptor) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(descriptor ? "OFD.xml" : "other.xml")); zip.write("<synthetic/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return bytes.toByteArray();
    }
}
