package io.agentflow.signature;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.*;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.LocalOrganizationDirectory;
import io.agentflow.storage.LocalDocumentStore;

import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 真实表单上传、审批、认证过滤链、幂等写入和回调验签共同验证用户电子签接口。
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:signature-api;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.signatures.worker-enabled=false"})
@AutoConfigureMockMvc
class SignatureApiIntegrationTest {
    private static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-signature-api-" + UUID.randomUUID());
    private static final java.security.KeyPair KEY = keyPair();
    private static final byte[] ORIGINAL = "合成原始合同".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESULT = "合成签署结果".getBytes(StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcSignatureOperationRepository operations;
    @Autowired SignatureOperationService service;
    @Autowired SignatureGatewayConfiguration configuration;
    @Autowired LocalDocumentStore documents;
    @SpyBean LocalOrganizationDirectory directory;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("agentflow.attachments.directory", DIRECTORY::toString);
        properties.add("agentflow.signatures.gateway.enabled", () -> true);
        String prefix = "agentflow.signatures.gateway.tenants.demo[0].";
        properties.add(prefix + "key", () -> "contract-seal"); properties.add(prefix + "version", () -> 2);
        properties.add(prefix + "name", () -> "合同签署资料"); properties.add(prefix + "actors[0]", () -> "alice");
        properties.add(prefix + "actors[1]", () -> "manager"); properties.add(prefix + "actors[2]", () -> "admin");
        properties.add(prefix + "signers[0].key", () -> "company"); properties.add(prefix + "signers[0].provider-subject", () -> "private-provider-account");
        properties.add(prefix + "receipt-public-key", () -> Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()));
        properties.add(prefix + "endpoint", () -> "https://fixture.invalid/signature"); properties.add(prefix + "token", () -> "private-provider-token");
    }

    @Test void approvedApplicationCanCreateQueryReceiveCallbackAndDownloadIndependentResult() throws Exception {
        var source = approved(); var created = create(source, "alice", UUID.randomUUID().toString(), 201); UUID id = UUID.fromString(created.path("id").asText());
        var fields = new ArrayList<String>(); created.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "version", "status");
        assertThat(created.path("version").isTextual()).isTrue(); assertThat(created.path("status").asText()).isEqualTo("QUEUED");
        assertThat(read(path(source) + "?roundNo=1", "alice", 200).path("items")).hasSize(1);
        var claim = service.claim("demo", id, Instant.now()); var receipt = signed(claim, 1);
        callback(claim, receipt, 204);
        var collecting = operations.find("demo", id).orElseThrow();
        assertThat(collecting.status()).isEqualTo(SignatureOperation.Status.COLLECTING);
        getResult(source, id, "alice", 409);
        var fetch = service.claim("demo", id, Instant.now()); var file = fetch.artifacts().get(0);
        var content = new LocalDocumentStore.Content(file.contentId(), file.size(), file.sha256());
        var staged = documents.stage(content, new ByteArrayInputStream(RESULT)); documents.publish(content, staged); documents.discard(staged);
        assertThat(service.confirmFile(fetch, file, Instant.now())).isTrue(); service.finishFiles(fetch, null, Instant.now());
        var response = getResult(source, id, "alice", 200);
        assertThat(response.getResponse().getContentAsByteArray()).isEqualTo(RESULT);
        assertThat(response.getResponse().getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(response.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(documents.read(new LocalDocumentStore.Content(source.document(), ORIGINAL.length, digest(ORIGINAL)))).isEqualTo(ORIGINAL);
        var detail = read(path(source) + "/" + id, "alice", 200);
        assertThat(detail.path("operation").path("status").asText()).isEqualTo("SIGNED");
        assertThat(detail.path("documents").get(0).path("downloadable").asBoolean()).isTrue();
        assertThat(detail.toString()).doesNotContain("private-provider", KEY.getPublic().toString(), file.contentId().toString(), "profileDigest", "receipt", "targetDigest", "login_reference");
        read(path(source) + "/" + id, "admin", 403); getResult(source, id, "admin", 403);
        assertThat(read(path(source) + "?roundNo=1", "admin", 200).path("items")).isEmpty();
    }

    @Test void replayKeepsOriginalReceiptAfterVersionChangeAndStorageLossButRechecksCurrentFieldPermission() throws Exception {
        var source = approved(); String key = UUID.randomUUID().toString(); String body = json.write(input(source));
        var first = write(post(path(source)), "manager", body, key, 201);
        String original = first.getResponse().getContentAsString();
        jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", source.application().toString());
        Path stored = DIRECTORY.resolve(source.document() + ".bin"), moved = DIRECTORY.resolve(source.document() + ".held"); Files.move(stored, moved);
        try {
            var replay = write(post(path(source)), "manager", body, key, 201);
            assertThat(replay.getResponse().getContentAsString()).isEqualTo(original);
            assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
            write(post(path(source)), "manager", body, UUID.randomUUID().toString(), 503);
        } finally { Files.move(moved, stored); }
        doReturn(false).when(directory).eligible("demo", "manager");
        write(post(path(source)), "manager", body, key, 403);
        UUID id = UUID.fromString(json.read(original, JsonNode.class).path("id").asText()); read(path(source) + "/" + id, "manager", 403);
        assertThat(operations.forRound("demo", source.application(), 1, null, 10)).hasSize(1);
    }

    @Test void cancelReplaysWithoutNewAuditAndDifferentActorCannotReuseWriteOrCancel() throws Exception {
        var source = approved(); String createKey = UUID.randomUUID().toString(); var created = create(source, "alice", createKey, 201);
        UUID id = UUID.fromString(created.path("id").asText());
        create(source, "manager", createKey, 409);
        String endpoint = path(source) + "/" + id + "/cancel", body = json.write(Map.of("expectedVersion", "1"));
        write(post(endpoint), "manager", body, UUID.randomUUID().toString(), 403);
        String cancelKey = UUID.randomUUID().toString(); var cancelled = write(post(endpoint), "alice", body, cancelKey, 200);
        var replay = write(post(endpoint), "alice", body, cancelKey, 200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(cancelled.getResponse().getContentAsString());
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE aggregate_type='Signature'"
                                    + " AND aggregate_id=?", Integer.class, id.toString())).isEqualTo(2);
        write(post(endpoint), "alice", json.write(Map.of("expectedVersion", "2")), UUID.randomUUID().toString(), 409);
    }

    @Test void unknownControlFieldsInvalidVersionsAndAmbiguousQueriesAreRejected() throws Exception {
        var source = approved();
        for (String field : List.of("tenantId", "actor", "signers", "endpoint", "contentId")) {
            var body = new java.util.LinkedHashMap<>(input(source)); body.put(field, "injected");
            write(post(path(source)), "alice", json.write(body), UUID.randomUUID().toString(), 400);
        }
        for (String version : List.of("0", "01", "-1", "9223372036854775808", "1.5")) {
            var body = new java.util.LinkedHashMap<>(input(source)); body.put("expectedVersion", version);
            write(post(path(source)), "alice", json.write(body), UUID.randomUUID().toString(), 400);
        }
        for (String query : List.of("", "?roundNo=1&roundNo=1", "?roundNo=01", "?roundNo=1&limit=101", "?roundNo=1&limit=0", "?roundNo=1&extra=true")) read(path(source) + query, "alice", 400);
        assertThat(operations.forRound("demo", source.application(), 1, null, 10)).isEmpty();
    }

    @Test void numericVersionTokensCannotBeCoercedIntoTheStringVersionContract() throws Exception {
        var source = approved();
        for (String field : List.of("expectedVersion", "profileVersion")) {
            var body = new java.util.LinkedHashMap<>(input(source)); body.put(field, field.equals("expectedVersion") ? source.version() : 2);
            write(post(path(source)), "alice", json.write(body), UUID.randomUUID().toString(), 400);
        }
        assertThat(operations.forRound("demo", source.application(), 1, null, 10)).isEmpty();
        UUID id = UUID.fromString(create(source, "alice", UUID.randomUUID().toString(), 201).path("id").asText());
        write(post(path(source) + "/" + id + "/cancel"), "alice", json.write(Map.of("expectedVersion", 1)), UUID.randomUUID().toString(), 400);
        assertThat(operations.find("demo", id).orElseThrow().status()).isEqualTo(SignatureOperation.Status.QUEUED);
    }

    @Test void fractionalOrTextualRoundCannotSelectAnotherApprovedRoundThroughCoercion() throws Exception {
        var source = approved();
        for (Object round : List.of(1.5, "1", true)) {
            var body = new java.util.LinkedHashMap<>(input(source)); body.put("roundNo", round);
            write(post(path(source)), "alice", json.write(body), UUID.randomUUID().toString(), 400);
        }
        assertThat(operations.forRound("demo", source.application(), 1, null, 10)).isEmpty();
    }

    @Test void cursorCannotCrossApplicationsAndPrivatePagesCanBeTraversedWithoutReturningHiddenFileMetadata() throws Exception {
        var source = approved(); var ids = new ArrayList<UUID>();
        for (int index = 0; index < 2; index++) {
            var created = create(source, "alice", UUID.randomUUID().toString(), 201); UUID id = UUID.fromString(created.path("id").asText()); ids.add(id);
            write(post(path(source) + "/" + id + "/cancel"), "alice", json.write(Map.of("expectedVersion", "1")), UUID.randomUUID().toString(), 200);
        }
        var hidden = read(path(source) + "?roundNo=1&limit=1", "admin", 200); assertThat(hidden.path("items")).isEmpty();
        assertThat(hidden.path("nextAfterId").asText()).isEqualTo(ids.get(0).toString());
        var end = read(path(source) + "?roundNo=1&limit=1&afterId=" + hidden.path("nextAfterId").asText(), "admin", 200);
        assertThat(end.path("items")).isEmpty(); assertThat(end.hasNonNull("nextAfterId")).isFalse();
        var other = approved(); read(path(other) + "?roundNo=1&afterId=" + ids.get(0), "alice", 404);
        read(path(other) + "/" + ids.get(0), "alice", 404); read(path(source) + "/" + ids.get(0), "bob", 404);
    }

    @Test void callbackDuplicatesAndOlderRevisionsDoNotReplaceAcceptedSignedFactsOrLateWorkerResult() throws Exception {
        var source = approved(); UUID id = UUID.fromString(create(source, "alice", UUID.randomUUID().toString(), 201).path("id").asText());
        var queued = operations.find("demo", id).orElseThrow(); callback(queued, signed(queued, 1), 409);
        var sending = service.claim("demo", id, Instant.now());
        var pending = new SignatureReceipt(id, sending.input().request().digest(), 1, SignatureReceipt.Status.PENDING, Instant.now(), "private-record", null, List.of());
        callback(sending, pending, 204); var signed = signed(sending, 2); callback(sending, signed, 204);
        var accepted = operations.find("demo", id).orElseThrow(); long auditBefore = count("audit_event", "aggregate_id", id);
        callback(sending, signed, 204); callback(sending, pending, 204);
        assertThat(operations.find("demo", id)).contains(accepted); assertThat(count("audit_event", "aggregate_id", id)).isEqualTo(auditBefore);
        assertThat(count("signature_receipt_evidence", "operation_id", id)).isEqualTo(2);
        Instant changedAt = Instant.now();
        var changed = new SignatureReceipt(id, sending.input().request().digest(), 2, SignatureReceipt.Status.DECLINED, changedAt, "private-record", changedAt, List.of());
        callback(sending, changed, 409);
        service.finish(sending, new SignatureGateway.Unavailable(SignatureOperation.Failure.TIMEOUT), Instant.now());
        assertThat(operations.find("demo", id)).contains(accepted);
        byte[] body = body(configuration.declarations().get(0).profile(), sending.input(), signed);
        mvc.perform(post(SignatureCallbackVerifier.PATH).header(SignatureCallbackVerifier.TENANT_HEADER, "demo")
                .header(SignatureCallbackVerifier.OPERATION_HEADER, id.toString()).header(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(keyPair(), body))
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        assertThat(operations.find("demo", id)).contains(accepted);
    }

    @Test void profileChoicesAndResponsesDoNotExposeInternalSigningAccountsOrSecrets() throws Exception {
        var choices = read("/api/v1/signatures/options", "alice", 200); assertThat(choices.path("enabled").asBoolean()).isTrue();
        assertThat(choices.path("profiles").get(0).path("version").isTextual()).isTrue();
        assertThat(choices.toString()).doesNotContain("private-provider", "receiptPublicKey", DIRECTORY.toString(), "actors", "signers");
        assertThat(read("/api/v1/signatures/options", "bob", 200).path("profiles")).isEmpty();
    }

    private Source approved() throws Exception {
        var field = new FormSchema.Field("contract", "合同", FormSchema.FieldType.ATTACHMENT, true, null, null, null, null, null, null, null, true, Map.of("review", FieldVisibility.READ_ONLY));
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()), new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")), new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "end", "")));
        var draft = definitions.create("demo", "signature-api-" + UUID.randomUUID(), "签署审批", graph, new FormSchema(1, List.of(field)), null);
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN")), draft.id(), draft.revision(), "签署接口验证");
        UUID application = UUID.fromString(writeJson(post("/api/v1/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "processKey", published.key(), "definitionVersion", 1, "title", "合同", "payload", Map.of()), 201).path("id").asText());
        String app = "/api/v1/applications/" + application;
        UUID document = UUID.fromString(writeJson(post(app + "/attachments"), "alice", Map.of("expectedVersion", 1, "fieldPath", "contract", "filename", "合同.pdf", "size", ORIGINAL.length, "sha256", digest(ORIGINAL)), 201).path("id").asText());
        mvc.perform(put(app + "/attachments/" + document + "/content").header("Authorization", token("alice")).header("X-Application-Version", 1).contentType("application/octet-stream").content(ORIGINAL)).andExpect(status().isOk());
        writeJson(put(app), "alice", Map.of("expectedVersion", 1, "title", "合同", "payload", Map.of("contract", List.of(document.toString()))), 200);
        writeJson(post(app + "/submit"), "alice", Map.of("expectedVersion", 2), 200);
        String task = tasks.createTaskQuery().processVariableValueEquals("applicationId", application.toString()).singleResult().getId();
        writeJson(post("/api/v1/tasks/" + task + "/actions"), "manager", Map.of("expectedVersion", 3, "action", "APPROVE", "comment", "同意"), 200);
        var approved = read(app, "alice", 200); assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        return new Source(application, document, approved.path("version").asLong());
    }
    private JsonNode create(Source source, String user, String key, int status) throws Exception {
        return json.read(write(post(path(source)), user, json.write(input(source)), key, status).getResponse().getContentAsString(), JsonNode.class);
    }
    private Map<String, Object> input(Source source) {
        return Map.of("roundNo", 1, "expectedVersion", Long.toString(source.version()), "profileKey", "contract-seal", "profileVersion", "2", "documentIds", List.of(source.document()), "purpose", "明确授权本次合同签署", "validUntil", Instant.now().plusSeconds(600));
    }
    private SignatureReceipt signed(SignatureOperation operation, long revision) {
        Instant now = Instant.now(); var request = operation.input().request();
        return new SignatureReceipt(request.id(), request.digest(), revision, SignatureReceipt.Status.SIGNED, now, "private-record", now,
                request.documents().stream().map(file -> new SignatureReceipt.Artifact(file.attachmentId(), RESULT.length, digest(RESULT), "application/pdf",
                        List.of(new SignatureReceipt.Proof("company", "f".repeat(64), now, null)))).toList());
    }
    private void callback(SignatureOperation operation, SignatureReceipt receipt, int expected) throws Exception {
        byte[] body = body(configuration.declarations().get(0).profile(), operation.input(), receipt);
        mvc.perform(post(SignatureCallbackVerifier.PATH).header(SignatureCallbackVerifier.TENANT_HEADER, "demo")
                .header(SignatureCallbackVerifier.OPERATION_HEADER, operation.input().request().id().toString())
                .header(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(KEY, body)).contentType("application/json").content(body))
                .andExpect(status().is(expected)).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
    private MvcResult getResult(Source source, UUID id, String user, int expected) throws Exception {
        return mvc.perform(get(path(source) + "/" + id + "/documents/" + source.document() + "/content").header("Authorization", token(user)))
                .andExpect(status().is(expected)).andReturn();
    }
    private JsonNode read(String path, String user, int expected) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", token(user))).andExpect(status().is(expected)).andReturn();
        if (expected == 200) assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return json.read(result.getResponse().getContentAsString(), JsonNode.class);
    }
    private MvcResult write(MockHttpServletRequestBuilder request, String user, String body, String key, int expected) throws Exception {
        var result = mvc.perform(request.header("Authorization", token(user)).header("Idempotency-Key", key).contentType("application/json").content(body)).andExpect(status().is(expected)).andReturn();
        if (expected == 200 || expected == 201) assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store"); return result;
    }
    private JsonNode writeJson(MockHttpServletRequestBuilder request, String user, Object body, int expected) throws Exception {
        return json.read(mvc.perform(request.header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(json.write(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private static String path(Source source) { return "/api/v1/applications/" + source.application() + "/signatures"; }
    private static String digest(byte[] body) { return java.util.HexFormat.of().formatHex(SignatureRequest.sha256().digest(body)); }
    private long count(String table, String column, UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, id.toString()); }

    /**
     * 经实际上传和审批取得的当前来源。
     *
     * @author owlzhangfq@gmail.com
     */
    record Source(UUID application, UUID document, long version) { }
}
