package io.agentflow.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 通过真实申请、节点字段权限和文件传输验证附件生命周期。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.attachments.max-file-bytes=1024", "agentflow.attachments.max-application-bytes=2048", "agentflow.attachments.max-application-uploads=3"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AttachmentIntegrationTest {
    static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-attachment-test-" + UUID.randomUUID());
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_TEST_URL", "jdbc:h2:mem:attachment-tests;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_ATTACHMENT_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActor actors;
    @Autowired AttachmentService attachments;
    @SpyBean JdbcAttachmentRepository files;
    @SpyBean AttachmentReferenceService references;

    @Test
    void advertisesActualLocalLimitsWithoutDisclosingStoragePaths() throws Exception {
        var response = read("/attachments/options", "alice", 200);
        assertThat(response.path("enabled").asBoolean()).isTrue();
        assertThat(response.path("maxFileBytes").asLong()).isEqualTo(1024);
        assertThat(response.path("contentScanAvailable").asBoolean()).isFalse();
        assertThat(response.toString()).doesNotContain(DIRECTORY.toString());
    }

    @Test
    void incompleteUploadsBlockSubmissionAndSameIdRecoversAfterDigestFailure() throws Exception {
        String app = create();
        byte[] bytes = "附件原文".getBytes(StandardCharsets.UTF_8);
        String id = reserve(app, "proof", "核对.pdf", bytes, 1);
        revise(app, 1, List.of(id));
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 2), 422);
        assertThat(read("/applications/" + app + "/rounds", "alice", 200)).isEmpty();
        assertThat(read("/applications/" + app, "alice", 200).path("status").asText()).isEqualTo("DRAFT");
        assertThat(tasks.createTaskQuery().processVariableValueEquals("applicationId", app).count()).isZero();
        upload(app, id, 2, "wrong".getBytes(StandardCharsets.UTF_8), 422);
        assertThat(read("/applications/" + app + "/attachments/" + id, "alice", 200).path("status").asText()).isEqualTo("FAILED");
        upload(app, id, 2, bytes, 200);
        upload(app, id, 2, bytes, 200);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 2), 200);
        download(app, id, "manager", null, 200, bytes);
        download(app, id, "admin", null, 403, null);
        download(app, id, "bob", null, 404, null);
        upload(app, id, 3, bytes, 422);
    }

    @Test
    void returnedRevisionAndResubmissionPreserveOriginalRoundReferences() throws Exception {
        String app = create();
        byte[] old = "old evidence".getBytes(StandardCharsets.UTF_8);
        String first = reserve(app, "proof", "旧证明.txt", old, 1);
        upload(app, first, 1, old, 200);
        revise(app, 1, List.of(first));
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 2), 200);
        act(app, "manager", "APPROVE", 3);
        download(app, first, "finance", null, 403, null);
        act(app, "finance", "RETURN", 4);
        revise(app, 5, List.of());
        download(app, first, "manager", null, 403, null);
        download(app, first, "alice", 1, 200, old);
        byte[] next = "new evidence".getBytes(StandardCharsets.UTF_8);
        String second = reserve(app, "proof", "新证明.txt", next, 6);
        upload(app, second, 6, next, 200);
        revise(app, 6, List.of(second));
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 7), 200);
        download(app, first, "alice", 1, 200, old);
        download(app, first, "alice", 2, 404, null);
        download(app, second, "alice", 1, 404, null);
        var rounds = read("/applications/" + app + "/rounds", "alice", 200);
        assertThat(rounds.get(0).at("/payload/proof/0").asText()).isEqualTo(first);
        assertThat(rounds.get(1).at("/payload/proof/0").asText()).isEqualTo(second);
    }

    @Test
    void refusesForeignReferencesUnsafeNamesAndActualOversizedBodies() throws Exception {
        String app = create(), other = create();
        byte[] content = new byte[]{1, 2};
        String id = reserve(app, "proof", "safe.bin", content, 1);
        write(put("/applications/" + other), "alice", Map.of("expectedVersion", 1, "title", "附件", "payload", Map.of("proof", List.of(id))), 422);
        for (String name : List.of("../secret", "evil\r\nheader", "dir\\file")) {
            write(post("/applications/" + app + "/attachments"), "alice", metadata("proof", name, content, 1), 400);
        }
        write(post("/applications/" + app + "/attachments"), "admin", metadata("proof", "safe.bin", content, 1), 403);
        upload(app, id, 1, new byte[1025], 413);
        upload(app, id, 1, content, 200);
        download(other, id, "alice", null, 404, null);
    }

    @Test
    void lostDatabaseConfirmationRecoversExistingContentWithoutReplacingIdentity() throws Exception {
        String app = create();
        byte[] bytes = "persisted first".getBytes(StandardCharsets.UTF_8);
        String id = reserve(app, "proof", "恢复.bin", bytes, 1);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database interrupted"))
                .doCallRealMethod().when(files).transition(any(), eq(Attachment.Status.READY));
        assertThatThrownBy(() -> upload(app, id, 1, bytes, 200))
                .hasRootCauseInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(Files.readAllBytes(DIRECTORY.resolve(id + ".bin"))).isEqualTo(bytes);
        assertThat(read("/applications/" + app + "/attachments/" + id, "alice", 200).path("status").asText()).isEqualTo("UPLOADING");
        upload(app, id, 1, bytes, 200);
        upload(app, id, 1, new byte[]{1}, 422);
        assertThat(read("/applications/" + app + "/attachments/" + id, "alice", 200).path("status").asText()).isEqualTo("READY");
        download(app, id, "alice", null, 200, bytes);
    }

    @Test
    void editDuringTransmissionRejectsPublishingStaleContentAndSameFileCanRetry() throws Exception {
        String app = create();
        byte[] bytes = "concurrent upload".getBytes(StandardCharsets.UTF_8);
        String id = reserve(app, "proof", "并发.bin", bytes, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var upload = executor.submit(() -> {
                actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
                try {
                    return attachments.upload(UUID.fromString(app), UUID.fromString(id), 1, new ByteArrayInputStream(bytes) {
                        @Override public synchronized int read(byte[] buffer, int offset, int length) {
                            started.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test transmission timed out"); }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                            return super.read(buffer, offset, length);
                        }
                    });
                } finally { actors.clear(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            revise(app, 1, List.of(id));
            release.countDown();
            assertThatThrownBy(() -> upload.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(DomainException.class)
                    .cause().hasMessageContaining("version");
            assertThat(Files.exists(DIRECTORY.resolve(id + ".bin"))).isFalse();
            upload(app, id, 2, bytes, 200);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void submissionNeverValidatesOneRevisionAndFreezesAnother() throws Exception {
        String app = create();
        byte[] bytes = {1};
        String ready = reserve(app, "proof", "已完成.bin", bytes, 1);
        upload(app, ready, 1, bytes, 200);
        String pending = reserve(app, "proof", "未完成.bin", bytes, 1);
        revise(app, 1, List.of(ready));
        var executor = Executors.newSingleThreadExecutor();
        try {
            // 在已检查的草稿与后续提交读取之间，另一请求保存尚未上传的文件引用。
            doAnswer(invocation -> {
                invocation.callRealMethod();
                var checked = invocation.getArgument(0, io.agentflow.approval.model.Application.class);
                if (checked.id().toString().equals(app) && checked.editable()) {
                    executor.submit(() -> { revise(app, 2, List.of(pending)); return null; }).get(5, TimeUnit.SECONDS);
                }
                return null;
            }).when(references).validate(any(), eq(true));
            write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 3), 409);
            assertThat(read("/applications/" + app + "/rounds", "alice", 200)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_attachment_round WHERE application_id=?", Integer.class, app)).isZero();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void quotaIncludesPendingAndRemovedReferencesAndMetadataReservationReplays() throws Exception {
        String app = create(), key = UUID.randomUUID().toString();
        var body = metadata("proof", "累计.bin", new byte[1024], 1);
        String path = "/api/v1/applications/" + app + "/attachments";
        String first = mvc.perform(post(path).header("Authorization", token("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(path).header("Authorization", token("alice")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body)))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().json(first));
        reserve(app, "proof", "另一份.bin", new byte[1024], 1);
        write(post("/applications/" + app + "/attachments"), "alice", metadata("proof", "超额.bin", new byte[1], 1), 409);
        reserve(app, "proof", "空文件.bin", new byte[0], 1);
        write(post("/applications/" + app + "/attachments"), "alice", metadata("proof", "第四份.bin", new byte[0], 1), 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_attachment WHERE application_id=?", Integer.class, app)).isEqualTo(3);
    }

    @Test
    void tableReferencesRemainBoundToTheirColumnAndForeignTenantsCannotRead() throws Exception {
        var column = new FormSchema.Field("receipt", "凭证", FormSchema.FieldType.ATTACHMENT, false,
                null, null, null, null, null, null, null, false, Map.of("review", FieldVisibility.HIDDEN));
        var table = new FormSchema.Field("items", "明细", FormSchema.FieldType.TABLE, false,
                null, null, null, null, null, List.of(column), 10);
        var proof = new FormSchema.Field("proof", "另一字段", FormSchema.FieldType.ATTACHMENT, false, null, null, null, null, null);
        String app = create(new FormSchema(2, List.of(proof, table)));
        byte[] bytes = {4, 5};
        String id = reserve(app, "items.receipt", "明细.bin", bytes, 1);
        upload(app, id, 1, bytes, 200);
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", 1, "title", "越字段", "payload", Map.of("proof", List.of(id))), 422);
        var payload = Map.of("items", List.of(Map.of("receipt", List.of(id)), Map.of("receipt", List.of(id))));
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", 1, "title", "明细附件", "payload", payload), 200);
        write(post("/applications/" + app + "/submit"), "alice", Map.of("expectedVersion", 2), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_attachment_round WHERE application_id=?", Integer.class, app)).isEqualTo(1);
        download(app, id, "manager", 1, 403, null);
        read("/applications/" + app + "/attachments/" + id + "?roundNo=1", "manager", 403);
        download(app, id, "alice", 0, 400, null);
        actors.set(new Actor("other-tenant", "alice", Set.of("ADMIN")));
        try { assertThatThrownBy(() -> attachments.metadata(UUID.fromString(app), UUID.fromString(id), 1))
                .isInstanceOf(DomainException.class).hasMessageContaining("not found"); }
        finally { actors.clear(); }
    }

    private String create() throws Exception {
        var field = new FormSchema.Field("proof", "证明文件", FormSchema.FieldType.valueOf("ATTACHMENT"), true,
                null, null, null, null, null, null, null, true, Map.of("review", FieldVisibility.READ_ONLY));
        return create(new FormSchema(1, List.of(field)));
    }
    private String create(FormSchema schema) throws Exception {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "主管", NodeType.USER_TASK, Map.of("assigneeRule", "role:MANAGER")),
                new Node("finance", "财务", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "finance", ""), new Edge("e3", "finance", "end", "")));
        var draft = definitions.create("demo", "attachment-" + UUID.randomUUID(), "附件审批", graph, schema, null);
        var published = definitions.publish(new Actor("demo", "admin", Set.of("ADMIN", "PROCESS_ADMIN")), draft.id(), draft.revision(), "附件测试");
        return write(post("/applications"), "alice", Map.of("businessNo", UUID.randomUUID().toString(), "processKey", published.key(),
                "definitionVersion", 1, "title", "附件申请", "payload", Map.of()), 201).path("id").asText();
    }
    private void revise(String app, long version, List<String> ids) throws Exception {
        write(put("/applications/" + app), "alice", Map.of("expectedVersion", version, "title", "附件申请", "payload", Map.of("proof", ids)), 200);
    }
    private void act(String app, String user, String action, long version) throws Exception {
        String task = tasks.createTaskQuery().processVariableValueEquals("applicationId", app).singleResult().getId();
        write(post("/tasks/" + task + "/actions"), user, Map.of("action", action, "expectedVersion", version, "comment", "核对附件"), 200);
    }
    private String reserve(String app, String path, String name, byte[] bytes, long version) throws Exception {
        return write(post("/applications/" + app + "/attachments"), "alice", metadata(path, name, bytes, version), 201).path("id").asText();
    }
    private Map<String,Object> metadata(String path, String name, byte[] bytes, long version) throws Exception {
        return Map.of("fieldPath", path, "filename", name, "size", bytes.length, "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), "expectedVersion", version);
    }
    private void upload(String app, String id, long version, byte[] bytes, int status) throws Exception {
        mvc.perform(put("/api/v1/applications/" + app + "/attachments/" + id + "/content").header("Authorization", token("alice"))
                .header("X-Application-Version", version).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes))
                .andExpect(status().is(status));
    }
    private void download(String app, String id, String user, Integer round, int status, byte[] bytes) throws Exception {
        var response = mvc.perform(get("/api/v1/applications/" + app + "/attachments/" + id + "/content" + (round == null ? "" : "?roundNo=" + round))
                .header("Authorization", token(user))).andExpect(status().is(status)).andReturn().getResponse();
        if (status == 200) {
            assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
    private JsonNode read(String path, String user, int status) throws Exception {
        return json.read(mvc.perform(get("/api/v1" + path).header("Authorization", token(user))).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode write(MockHttpServletRequestBuilder request, String user, Object body, int status) throws Exception {
        request.with(servlet -> { servlet.setRequestURI("/api/v1" + servlet.getRequestURI()); return servlet; });
        return json.read(mvc.perform(request.header("Authorization", token(user)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
}
