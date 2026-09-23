package io.agentflow.approval.comment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.HashSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 评论通过真实认证、幂等事务及审批数据验证访问边界与只追加语义。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:application-comments;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ApplicationCommentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationCommentRepository comments;

    @Test
    void appendRecordsServerContextWithoutChangingApplicationEngineOrAudit() throws Exception {
        String id = application(true);
        var before = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", id);
        var rounds = jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=?", id);
        var audits = jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=? ORDER BY id", id);
        var tasks = jdbc.queryForList("SELECT * FROM ACT_RU_TASK ORDER BY ID_");
        JsonNode comment = add(id, "finance", 2, "  核对发票\n<script>alert(1)</script>  ", UUID.randomUUID().toString(), 201);
        assertThat(comment.path("author").asText()).isEqualTo("finance");
        assertThat(comment.path("content").asText()).isEqualTo("核对发票\n<script>alert(1)</script>");
        assertThat(comment.path("roundNo").asInt()).isEqualTo(1);
        assertThat(comment.path("applicationVersion").asLong()).isEqualTo(2);
        assertThat(comment.path("applicationStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(comment.has("tenantId")).isFalse();
        assertThat(page(id, "alice", Map.of()).path("items").get(0)).isEqualTo(comment);
        assertThat(page(id, "alice", Map.of()).has("nextCursor")).isTrue();
        assertThat(page(id, "alice", Map.of()).get("nextCursor").isNull()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM approval_submission_round WHERE application_id=?", id)).isEqualTo(rounds);
        assertThat(jdbc.queryForList("SELECT * FROM audit_event WHERE application_id=? ORDER BY id", id)).isEqualTo(audits);
        assertThat(jdbc.queryForList("SELECT * FROM ACT_RU_TASK ORDER BY ID_")).isEqualTo(tasks);
    }

    @Test
    void sameKeyReplaysOneCommentEvenAfterWithdrawalButNewWritesAreReadOnly() throws Exception {
        String id = application(true), key = UUID.randomUUID().toString();
        JsonNode original = add(id, "alice", 2, "原评论", key, 201);
        mvc.perform(write("/api/v1/applications/" + id + "/withdraw", "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 2)))
                .andExpect(status().isOk());
        var replay = mvc.perform(write(path(id), "alice", key, Map.of("expectedVersion", 2, "content", "原评论")))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(json.read(replay.getResponse().getContentAsString(), JsonNode.class)).isEqualTo(original);
        add(id, "alice", 3, "未开放的结束后评论", UUID.randomUUID().toString(), 422);
        assertThat(count(id)).isEqualTo(1);
        assertThat(page(id, "alice", Map.of()).path("items")).hasSize(1);
        add(id, "alice", 2, "改写原评论", key, 409);
    }

    @Test
    void lostVisibilityBlocksBothPagingAndIdempotentReplay() throws Exception {
        String id = application(true), key = UUID.randomUUID().toString();
        add(id, "finance", 2, "原审批人评论", key, 201);
        String taskId = jdbc.queryForObject("SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_=(SELECT process_instance_id FROM approval_submission_round WHERE application_id=?)", String.class, id);
        jdbc.update("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_=?", taskId);
        jdbc.update("UPDATE ACT_RU_TASK SET ASSIGNEE_='manager' WHERE ID_=?", taskId);
        mvc.perform(get(path(id)).header("Authorization", token("finance"))).andExpect(status().isNotFound());
        add(id, "finance", 2, "原审批人评论", key, 404);
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void unauthorizedCrossTenantAndAnonymousRequestsCannotReadOrWrite() throws Exception {
        String id = application(true);
        add(id, "bob", 2, "无权限", UUID.randomUUID().toString(), 404);
        mvc.perform(get(path(id)).header("Authorization", token("bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(id)).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized());
        Application foreign = Application.draft(UUID.randomUUID(), "other", "OTHER-" + UUID.randomUUID(), "expense-reimbursement", 1, "alice", "其他租户", Map.of());
        applications.save(foreign);
        mvc.perform(get(path(foreign.id().toString())).header("Authorization", token("admin"))).andExpect(status().isNotFound());
        add(foreign.id().toString(), "admin", 1, "跨租户", UUID.randomUUID().toString(), 404);
        assertThat(count(id)).isZero();
    }

    @Test
    void invalidRequestsAndStaleVersionsLeaveNoCommentAndCanRetryCorrectedBody() throws Exception {
        String draft = application(false);
        add(draft, "alice", 1, "草稿不可评论", UUID.randomUUID().toString(), 422);
        String id = application(true), key = UUID.randomUUID().toString();
        add(id, "alice", 1, "旧上下文", key, 409);
        add(id, "alice", 2, "  \n\t", UUID.randomUUID().toString(), 400);
        add(id, "alice", 0, "版本不合法", UUID.randomUUID().toString(), 400);
        add(id, "alice", 2, "字".repeat(4001), UUID.randomUUID().toString(), 400);
        assertThat(count(id)).isZero();
        add(id, "alice", 2, "最新上下文", key, 201);
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void concurrentContextChangeFailsConditionalAppendWithoutSavingOldMetadata() throws Exception {
        String id = application(true);
        Application snapshot = applications.findById("demo", UUID.fromString(id)).orElseThrow();
        ApplicationComment comment = ApplicationComment.record(snapshot, 2, "alice", "并发测试", Instant.now());
        jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", id);
        assertThatThrownBy(() -> comments.append("demo", comment)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(count(id)).isZero();
    }

    @Test
    void timestampTiesUseUniqueCursorAndBindAccountApplicationAndRound() throws Exception {
        String id = application(true);
        for (int i = 0; i < 5; i++) add(id, "alice", 2, "记录 " + i, UUID.randomUUID().toString(), 201);
        jdbc.update("UPDATE application_comment SET created_at=TIMESTAMP '2026-09-23 12:00:00.123456' WHERE application_id=?", id);
        var ids = new HashSet<String>();
        JsonNode first = page(id, "alice", Map.of("limit", "2")), current = first;
        while (true) {
            current.path("items").forEach(row -> assertThat(ids.add(row.path("id").asText())).isTrue());
            if (!current.hasNonNull("nextCursor")) break;
            current = page(id, "alice", Map.of("limit", "2", "cursor", current.path("nextCursor").asText()));
        }
        assertThat(ids).hasSize(5);
        String cursor = first.path("nextCursor").asText();
        mvc.perform(get(path(id)).param("cursor", cursor).header("Authorization", token("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(id)).param("cursor", cursor).param("roundNo", "1").header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get(path(application(true))).param("cursor", cursor).header("Authorization", token("alice"))).andExpect(status().isBadRequest());
        assertThat(page(id, "alice", Map.of("roundNo", "2")).path("items")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=x", "roundNo=0", "cursor=bad", "tenantId=other"})
    void rejectsMalformedOrUnsupportedFilters(String input) throws Exception {
        String[] parts = input.split("=", 2);
        mvc.perform(get(path(application(true))).param(parts[0], parts[1]).header("Authorization", token("alice")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_COMMENT_QUERY"));
    }

    private String application(boolean submit) throws Exception {
        var result = mvc.perform(write("/api/v1/applications", "alice", UUID.randomUUID().toString(), Map.of(
                "businessNo", "COMMENT-" + UUID.randomUUID(), "processKey", "expense-reimbursement", "definitionVersion", 1,
                "title", "协作评论测试", "payload", Map.of("amount", 6000))))
                .andExpect(status().isCreated()).andReturn();
        String id = json.read(result.getResponse().getContentAsString(), JsonNode.class).path("id").asText();
        if (submit) mvc.perform(write("/api/v1/applications/" + id + "/submit", "alice", UUID.randomUUID().toString(), Map.of("expectedVersion", 1)))
                .andExpect(status().isOk());
        return id;
    }
    private JsonNode add(String id, String user, long version, String content, String key, int expected) throws Exception {
        var result = mvc.perform(write(path(id), user, key, Map.of("expectedVersion", version, "content", content)))
                .andExpect(status().is(expected)).andReturn();
        return json.read(result.getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode page(String id, String user, Map<String, String> params) throws Exception {
        var request = get(path(id)).header("Authorization", token(user)); params.forEach(request::param);
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.read(result.getResponse().getContentAsString(), JsonNode.class);
    }
    private MockHttpServletRequestBuilder write(String path, String user, String key, Object body) {
        return post(path).header("Authorization", token(user)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.write(body));
    }
    private int count(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM application_comment WHERE application_id=?", Integer.class, id); }
    private String path(String id) { return "/api/v1/applications/" + id + "/comments"; }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
