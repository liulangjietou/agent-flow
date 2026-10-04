package io.agentflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.approval.workspace.FlowablePendingTaskReadAdapter;
import io.agentflow.approval.workspace.PendingTaskController;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.approval.workspace.PendingTaskReadPort;
import io.agentflow.approval.workspace.TaskQueryParameters;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.support.MutationRequests.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在真实 HTTP、申请仓储与 Flowable 任务上验证筛选、分页和办理权限的一致性。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_QUEUE_TEST_URL:jdbc:h2:mem:pending-task-queue;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_QUEUE_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_QUEUE_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_QUEUE_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true", "agentflow.auth.demo-tenant=demo"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class PendingTaskQueueIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired DefinitionApplicationService definitions;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired PendingTaskReadPort reader;
    @Autowired io.agentflow.approval.process.FlowableApprovalProxyAccess proxies;
    @Autowired io.agentflow.approval.process.FlowableTaskAuthorization taskAuthorization;

    @Test
    void deadlineFilterUsesRealDueDatesAndTheSameAuthorizedSetForRowsAndTotal() throws Exception {
        String key = definition("user:manager");
        String overdue = task(submit(key, "alice", "已超期待办", "10").path("id").asText());
        String pending = task(submit(key, "alice", "未到期待办", "20").path("id").asText());
        String unrecorded = task(submit(key, "alice", "无期限待办", "30").path("id").asText());
        String unrelated = task(submit(key, "alice", "其他人的超期待办", "40").path("id").asText());
        tasks.setDueDate(overdue, java.util.Date.from(java.time.Instant.parse("2000-01-01T00:00:00Z")));
        tasks.setDueDate(pending, java.util.Date.from(java.time.Instant.parse("2100-01-01T00:00:00Z")));
        tasks.setDueDate(unrelated, java.util.Date.from(java.time.Instant.parse("2000-01-01T00:00:00Z")));
        tasks.setAssignee(unrelated, "finance");
        for (var expected : Map.of("overdue", overdue, "pending", pending, "unrecorded", unrecorded).entrySet()) {
            var page = query("manager", Map.of("processKey", key, "deadline", expected.getKey()));
            assertThat(page.path("total").asLong()).isEqualTo(1);
            assertThat(page.path("items").findValuesAsText("taskId")).containsExactly(expected.getValue());
            assertThat(page.toString()).doesNotContain("payload", "private-body");
        }
        assertThat(query("manager", Map.of("processKey", key, "deadline", "all")).path("total").asLong()).isEqualTo(3);
        assertThat(query("manager", Map.of("processKey", key, "deadline", "overdue", "minAmount", "11")).path("total").asLong()).isZero();
        assertThat(query("alice", Map.of("processKey", key, "deadline", "overdue")).path("total").asLong()).isZero();
    }

    @Test
    void deadlineFilteredCursorBindsTheFilterAndKeepsTotalBeforePagination() throws Exception {
        String key = definition("user:manager");
        for (int index = 0; index < 3; index++) {
            String task = task(submit(key, "alice", "超期分页 " + index, "10").path("id").asText());
            tasks.setDueDate(task, java.util.Date.from(java.time.Instant.parse("2000-01-01T00:00:00Z")));
        }
        var first = query("manager", Map.of("processKey", key, "deadline", "overdue", "limit", "2"));
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("total").asLong()).isEqualTo(3);
        String cursor = first.path("nextCursor").asText();
        request("manager", Map.of("processKey", key, "deadline", "pending", "cursor", cursor)).andExpect(status().isBadRequest());
        request("manager", Map.of("processKey", key, "cursor", cursor)).andExpect(status().isBadRequest());
        var second = query("manager", Map.of("processKey", key, "deadline", "overdue", "cursor", cursor, "limit", "2"));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("total").asLong()).isEqualTo(3);
        assertThat(second.path("items").get(0).path("taskId").asText()).isNotIn(first.path("items").findValuesAsText("taskId"));
        act(second.path("items").get(0).path("taskId").asText(), "manager", "APPROVE", null, 2);
        var empty = query("manager", Map.of("processKey", key, "deadline", "overdue", "cursor", cursor, "limit", "2"));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("total").asLong()).isEqualTo(2);
        for (var filters : List.of(Map.of("deadline", "due-soon"), Map.of("deadline", "overdue", "deadlineAt", "2000-01-01T00:00:00Z"))) {
            request("manager", filters).andExpect(status().isBadRequest());
        }
    }

    @Test
    void deadlineBoundaryUsesOneServerInstantAndStillChecksTenantAndCurrentEligibility() throws Exception {
        String key = definition("user:manager");
        String exact = task(submit(key, "alice", "恰好到期", "10").path("id").asText());
        String later = task(submit(key, "alice", "下一毫秒到期", "20").path("id").asText());
        var now = java.time.Instant.parse("2026-09-28T12:00:00Z");
        tasks.setDueDate(exact, java.util.Date.from(now));
        tasks.setDueDate(later, java.util.Date.from(now.plusMillis(1)));
        var actor = new Actor("demo", "manager", Set.of("APPROVER", "MANAGER"));
        var parameters = TaskQueryParameters.parse(actor, Map.of("processKey", key, "deadline", "overdue"), json, now);
        assertThat(reader.read(actor, parameters.query()).items()).extracting(PendingTaskReadPort.Item::taskId).containsExactly(exact);
        var pending = TaskQueryParameters.parse(actor, Map.of("processKey", key, "deadline", "pending"), json, now);
        assertThat(reader.read(actor, pending.query()).items()).extracting(PendingTaskReadPort.Item::taskId).containsExactly(later);
        var next = TaskQueryParameters.parse(actor, Map.of("processKey", key, "deadline", "overdue"), json, now.plusMillis(1));
        assertThat(reader.read(actor, next.query()).total()).isEqualTo(2);
        assertThat(reader.read(new Actor("foreign", "manager", actor.roles()), parameters.query()).total()).isZero();
        assertThat(reader.read(new Actor("demo", "manager", Set.of("ADMIN")), parameters.query()).total()).isZero();
        act(exact, "manager", "APPROVE", null, 2);
        assertThat(reader.read(actor, parameters.query()).total()).isZero();
    }

    @Test
    void filtersActualTaskAndApplicationSummariesWithoutReturningPayload() throws Exception {
        String key = definition("role:MANAGER"), marker = "摘要-" + UUID.randomUUID();
        var first = submit(key, "alice", marker + "%_", "100.01");
        submit(key, "bob", marker + "plain", "200");
        var page = query("manager", Map.of("processKey", key, "applicant", "alice", "minAmount", "100.01", "maxAmount", "100.01"));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        var item = page.path("items").get(0);
        assertThat(item.path("applicationId").asText()).isEqualTo(first.path("id").asText());
        assertThat(item.path("amount").asText()).isEqualTo("100.01");
        assertThat(item.path("applicant").asText()).isEqualTo("alice");
        assertThat(page.toString()).doesNotContain("private-body", "payload", "formSchema", "formData");
        assertThat(query("manager", Map.of("q", marker + "%_")).path("total").asInt()).isEqualTo(1);
        assertThat(query("manager", Map.of("q", first.path("businessNo").asText().toLowerCase())).path("total").asInt()).isEqualTo(1);
        assertThat(query("manager", Map.of("processKey", key, "q", "经理审批")).path("total").asInt()).isEqualTo(2);
        assertThat(query("manager", Map.of("processKey", key, "assignment", "unclaimed")).path("total").asInt()).isEqualTo(2);
        assertThat(query("manager", Map.of("processKey", key, "assignment", "assigned")).path("total").asInt()).isZero();
    }

    @Test
    void claimedTransferredAndDelegatedTasksOnlyFollowTheCurrentHandler() throws Exception {
        String key = definition("role:FINANCE");
        String id = submit(key, "alice", "办理权限", "50").path("id").asText(), task = task(id);
        tasks.addCandidateUser(task, "finance");
        assertThat(query("finance", Map.of("processKey", key)).path("total").asInt()).isEqualTo(1);
        assertThat(query("admin", Map.of("processKey", key)).path("total").asInt()).isEqualTo(1);
        act(task, "finance", "CLAIM", null, 2);
        assertThat(query("admin", Map.of("processKey", key)).path("total").asInt()).isZero();
        act(task, "finance", "DELEGATE", "bob", 3);
        assertThat(query("finance", Map.of("processKey", key)).path("total").asInt()).isZero();
        assertThat(query("bob", Map.of("processKey", key, "assignment", "delegated")).path("total").asInt()).isEqualTo(1);
        var current = readTask(task, "bob");
        assertThat(current.path("allowedActions").get(0).asText()).isEqualTo("RESOLVE");
        assertThat(current.path("version").asInt()).isEqualTo(4);
        mvc.perform(get("/api/v1/tasks/" + task).header("Authorization", token("admin"))).andExpect(status().isForbidden());
        act(task, "bob", "RESOLVE", null, 4);
        assertThat(query("bob", Map.of("processKey", key)).path("total").asInt()).isZero();
        assertThat(query("finance", Map.of("processKey", key, "assignment", "assigned")).path("total").asInt()).isEqualTo(1);
        act(task, "finance", "TRANSFER", "manager", 5);
        assertThat(query("finance", Map.of("processKey", key)).path("items").isEmpty()).isTrue();
        assertThat(query("manager", Map.of("processKey", key)).path("items").size()).isEqualTo(1);
        act(task, "manager", "APPROVE", null, 6);
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isZero();
        mvc.perform(get("/api/v1/tasks/" + task).header("Authorization", token("manager"))).andExpect(status().isNotFound());
    }

    @Test
    void sameTimestampCursorSurvivesCompletedRowsAndBindsFiltersActorAndTenant() throws Exception {
        String key = definition("user:manager");
        var ids = new HashSet<String>();
        for (int index = 0; index < 5; index++) ids.add(task(submit(key, "alice", "分页 " + index, "1").path("id").asText()));
        for (String id : ids) jdbc.update("UPDATE ACT_RU_TASK SET CREATE_TIME_=TIMESTAMP '2026-01-01 00:00:00' WHERE ID_=?", id);
        var first = query("manager", Map.of("processKey", key, "limit", "2"));
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("total").asInt()).isEqualTo(5);
        var seen = new HashSet<String>(first.path("items").findValuesAsText("taskId"));
        String cursor = first.path("nextCursor").asText();
        act(first.path("items").get(0).path("taskId").asText(), "manager", "APPROVE", null, 2);
        for (var filters : List.of(Map.of("processKey", key, "cursor", cursor, "assignment", "assigned"),
                Map.of("processKey", key, "cursor", cursor, "minAmount", "0"))) {
            request("manager", filters).andExpect(status().isBadRequest());
        }
        request("bob", Map.of("processKey", key, "cursor", cursor)).andExpect(status().isBadRequest());
        var second = query("manager", Map.of("processKey", key, "cursor", cursor, "limit", "2"));
        assertThat(second.path("total").asInt()).isEqualTo(4);
        seen.addAll(second.path("items").findValuesAsText("taskId"));
        var last = query("manager", Map.of("processKey", key, "cursor", second.path("nextCursor").asText(), "limit", "2"));
        seen.addAll(last.path("items").findValuesAsText("taskId"));
        assertThat(last.path("nextCursor").isTextual()).isFalse();
        assertThat(seen).isEqualTo(ids);
        var other = new Actor("other", "manager", Set.of("APPROVER", "MANAGER", "ADMIN"));
        var filter = TaskQueryParameters.parse(other, Map.of("processKey", key), json, java.time.Instant.now()).query();
        var otherPage = reader.read(other, filter);
        assertThat(otherPage.items()).isEmpty(); assertThat(otherPage.total()).isZero();
        var nonApprover = reader.read(new Actor("demo", "manager", Set.of("ADMIN")), filter);
        assertThat(nonApprover.items()).isEmpty(); assertThat(nonApprover.total()).isZero();
    }

    @Test
    void excludesSuspendedTasksWrongRuntimeBindingAndNonCurrentRound() throws Exception {
        String key = definition("user:manager");
        String id = submit(key, "alice", "运行绑定", "0").path("id").asText(), task = task(id);
        var instance = tasks.createTaskQuery().taskId(task).singleResult().getProcessInstanceId();
        runtime.suspendProcessInstanceById(instance);
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isZero();
        mvc.perform(get("/api/v1/tasks/" + task).header("Authorization", token("manager"))).andExpect(status().isNotFound());
        runtime.activateProcessInstanceById(instance);
        runtime.setVariable(instance, "tenantId", "another-tenant");
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isZero();
        runtime.setVariable(instance, "tenantId", "demo"); runtime.setVariable(instance, "roundNo", 2);
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isZero();
        runtime.setVariable(instance, "roundNo", 1);
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void unknownAmountsAreNotZeroAndRevisedAmountsBecomeSearchableOnResubmission() throws Exception {
        String key = definition("user:manager");
        String id = submit(key, "alice", "金额补正", "0").path("id").asText();
        submit(key, "alice", "无金额", null); submit(key, "alice", "无效旧金额", "not-number");
        assertThat(query("manager", Map.of("processKey", key)).path("total").asInt()).isEqualTo(3);
        assertThat(query("manager", Map.of("processKey", key, "minAmount", "0", "maxAmount", "0")).path("total").asInt()).isEqualTo(1);
        act(task(id), "manager", "RETURN", null, 2);
        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 3, "title", "金额补正", "payload", Map.of("amount", "999.123456789012345678")))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}")).andExpect(status().isOk());
        var result = query("manager", Map.of("processKey", key, "minAmount", "999.123456789012345678", "maxAmount", "999.123456789012345678"));
        assertThat(result.path("items").get(0).path("roundNo").asInt()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("amount").asText()).isEqualTo("999.123456789012345678");
    }

    @Test
    void rejectsUnknownOrMalformedFiltersAndDoesNotExposeAnUnrelatedQueue() throws Exception {
        mvc.perform(get("/api/v1/workspace/tasks")).andExpect(status().isUnauthorized());
        for (var filters : List.of(Map.of("limit", "0"), Map.of("limit", "101"), Map.of("assignment", "all-users"),
                Map.of("minAmount", "1e6"), Map.of("minAmount", "10", "maxAmount", "9"), Map.of("maxAmount", "NaN"),
                Map.of("tenantId", "other"), Map.of("assignee", "manager"), Map.of("cursor", "broken"), Map.of("q", "a\nb"))) {
            request("manager", filters).andExpect(status().isBadRequest());
        }
        String key = definition("user:manager"); submit(key, "alice", "权限隔离", "5");
        assertThat(query("alice", Map.of("processKey", key)).path("total").asInt()).isZero();
        assertThat(query("admin", Map.of("processKey", key)).path("total").asInt()).isZero();
    }

    @Test
    void declaredTextAmountNeverBecomesANumericQueueProjection() throws Exception {
        var schema = json.read("{\"schemaVersion\":1,\"fields\":[{\"key\":\"amount\",\"label\":\"业务文本\",\"type\":\"TEXT\",\"required\":false},{\"key\":\"secret\",\"label\":\"说明\",\"type\":\"TEXT\",\"required\":false}]}", io.agentflow.form.FormSchema.class);
        String key = definition("user:manager", schema);
        submit(key, "alice", "文本不能推测为金额", "50");
        var page = query("manager", Map.of("processKey", key));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("amount").isTextual()).isFalse();
        assertThat(query("manager", Map.of("processKey", key, "minAmount", "0")).path("total").asInt()).isZero();
    }

    @Test
    void pageAndTotalReadTheAuthorizedTaskSetOnceBeforeCursorAndLimit() throws Exception {
        String key = definition("user:manager");
        for (int index = 0; index < 5; index++) submit(key, "alice", "合并待办 " + index, "1");
        var statements = new ArrayList<String>();
        var actor = mock(CurrentActor.class);
        when(actor.actor()).thenReturn(new Actor("demo", "manager", Set.of("APPROVER", "MANAGER")));
        var controller = new PendingTaskController(actor, new FlowablePendingTaskReadAdapter(observedJdbc(statements), auth, json, proxies, taskAuthorization), json);
        var first = controller.list(Map.of("processKey", key, "limit", "2"));
        assertThat(first.items()).hasSize(2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(statements.stream().filter(sql -> sql.contains("FROM ACT_RU_TASK")).count())
                .as("nonempty pending page and full total share one current task set read").isEqualTo(1);
        statements.clear();
        var second = controller.list(Map.of("processKey", key, "limit", "2", "cursor", first.nextCursor()));
        assertThat(second.items()).hasSize(2);
        assertThat(second.total()).isEqualTo(5); // 总数包含游标之前的待办。
        assertThat(second.items()).doesNotContainAnyElementsOf(first.items());
        assertThat(statements.stream().filter(sql -> sql.contains("FROM ACT_RU_TASK")).count()).isEqualTo(1);
        var last = controller.list(Map.of("processKey", key, "limit", "2", "cursor", second.nextCursor()));
        assertThat(last.items()).hasSize(1);
        assertThat(last.total()).isEqualTo(5);
        assertThat(last.nextCursor()).isNull();
        act(last.items().get(0).taskId(), "manager", "APPROVE", null, 2);
        var removedPage = controller.list(Map.of("processKey", key, "limit", "2", "cursor", second.nextCursor()));
        assertThat(removedPage.items()).isEmpty();
        assertThat(removedPage.total()).isEqualTo(4); // 游标之后全部办结，之前仍有待办。
        assertThat(removedPage.nextCursor()).isNull();
        var empty = controller.list(Map.of("processKey", key, "q", "no-such-task"));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isZero();
    }

    @Test
    void countersignPagesCountOnlyTheCurrentUsersRemainingTasks() throws Exception {
        String key = "queue-all-" + UUID.randomUUID();
        var draft = definitions.create("demo", key, "待办会签统计", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "财务会签", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE", "approvalMode", "ALL")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", ""))));
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "会签待办分页验收");
        String app = submit(key, "alice", "会签待办", "100").path("id").asText();
        var finance = query("finance", Map.of("processKey", key));
        assertThat(finance.path("total").asInt()).isEqualTo(1);
        assertThat(finance.path("items").get(0).path("applicationId").asText()).isEqualTo(app);
        var admin = query("admin", Map.of("processKey", key));
        assertThat(admin.path("total").asInt()).isEqualTo(1);
        assertThat(admin.path("items").get(0).path("taskId").asText())
                .isNotEqualTo(finance.path("items").get(0).path("taskId").asText());
        act(finance.path("items").get(0).path("taskId").asText(), "finance", "APPROVE", null, 2);
        assertThat(query("finance", Map.of("processKey", key)).path("total").asInt()).isZero();
        assertThat(query("admin", Map.of("processKey", key)).path("total").asInt()).isEqualTo(1);
    }

    /** 在真实 JDBC 连接上记录 SQL，避免把宿主机耗时波动当作功能回归。 */
    private JdbcTemplate observedJdbc(List<String> statements) throws SQLException {
        var source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenAnswer(invocation -> {
            Connection connection = jdbc.getDataSource().getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) statements.add((String) arguments[0]);
                        try {
                            return method.invoke(connection, arguments);
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        });
        return new JdbcTemplate(source);
    }

    @Test
    void riskFiltersOnlyCurrentAuthorizedSnapshotsAndDistinguishesMissingEvidence() throws Exception {
        String key = riskDefinition();
        String high = submit(key, "alice", "风险高", "101").path("id").asText();
        String medium = submit(key, "alice", "风险中", "50").path("id").asText();
        String low = submit(key, "alice", "风险低", "0").path("id").asText();
        String unmatched = submit(key, "alice", "规则未命中", "1").path("id").asText();
        String legacy = submit(key, "alice", "旧轮次", "102").path("id").asText();
        jdbc.update("UPDATE approval_submission_round SET risk_level=NULL,risk_json=NULL WHERE application_id=?", legacy);
        String unrelated = submit(key, "alice", "他人高风险", "103").path("id").asText();
        tasks.setAssignee(task(unrelated), "finance");
        for (var expected : Map.of("high", high, "medium", medium, "low", low, "unmatched", unmatched, "unassessed", legacy).entrySet()) {
            var page = query("manager", Map.of("processKey", key, "risk", expected.getKey()));
            assertThat(page.path("total").asInt()).isEqualTo(1);
            assertThat(page.path("items").get(0).path("applicationId").asText()).isEqualTo(expected.getValue());
            assertThat(page.toString()).doesNotContain("condition", "payload", "private-body");
        }
        var actual = query("manager", Map.of("processKey", key, "risk", "high")).path("items").get(0).path("risk");
        assertThat(actual.path("level").asText()).isEqualTo("HIGH");
        assertThat(actual.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(actual.path("matches").findValuesAsText("ruleId")).containsExactly("medium", "high");
        assertThat(query("alice", Map.of("processKey", key, "risk", "high")).path("total").asInt()).isZero();
        assertThat(query("manager", Map.of("processKey", key, "risk", "high", "maxAmount", "100")).path("total").asInt()).isZero();
        var hiddenBinding = tasks.createTaskQuery().taskId(task(high)).singleResult().getProcessInstanceId();
        runtime.setVariable(hiddenBinding, "tenantId", "another-tenant");
        assertThat(query("manager", Map.of("processKey", key, "risk", "high")).path("total").asInt()).isZero();
        runtime.setVariable(hiddenBinding, "tenantId", "demo");
        request("manager", Map.of("risk", "unknown")).andExpect(status().isBadRequest());
        request("manager", Map.of("risk", "HIGH")).andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE approval_submission_round SET risk_level=NULL WHERE application_id=?", high))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void riskCursorBindsFilterAndKeepsTotalWhenLastPageDisappears() throws Exception {
        String key = riskDefinition();
        for (int i = 0; i < 3; i++) submit(key, "alice", "风险分页 " + i, "101");
        var first = query("manager", Map.of("processKey", key, "risk", "high", "limit", "2"));
        assertThat(first.path("total").asInt()).isEqualTo(3);
        String cursor = first.path("nextCursor").asText();
        for (String changed : List.of("all", "medium", "unassessed")) {
            request("manager", Map.of("processKey", key, "risk", changed, "cursor", cursor)).andExpect(status().isBadRequest());
        }
        var second = query("manager", Map.of("processKey", key, "risk", "high", "limit", "2", "cursor", cursor));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("total").asInt()).isEqualTo(3);
        act(second.path("items").get(0).path("taskId").asText(), "manager", "APPROVE", null, 2);
        var empty = query("manager", Map.of("processKey", key, "risk", "high", "cursor", cursor));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("total").asInt()).isEqualTo(2);
    }

    @Test
    void resubmissionFreezesNewAssessmentWhileHistoryAndOriginalVersionRemain() throws Exception {
        String key = riskDefinition();
        String id = submit(key, "alice", "风险重提", "101").path("id").asText();
        String original = jdbc.queryForObject("SELECT risk_json FROM approval_submission_round WHERE application_id=?", String.class, id);
        act(task(id), "manager", "RETURN", null, 2);
        mvc.perform(put("/api/v1/applications/" + id).header("Authorization", token("alice"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("expectedVersion", 3, "title", "风险重提", "payload", Map.of("amount", "0")))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/applications/" + id + "/submit").header("Authorization", token("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT risk_json FROM approval_submission_round WHERE application_id=? AND round_no=1", String.class, id))
                .isEqualTo(original);
        assertThat(query("manager", Map.of("processKey", key, "risk", "high")).path("total").asInt()).isZero();
        var second = query("manager", Map.of("processKey", key, "risk", "low")).path("items").get(0);
        assertThat(second.path("roundNo").asInt()).isEqualTo(2);
        var rounds = json.read(mvc.perform(get("/api/v1/applications/" + id + "/rounds").header("Authorization", token("manager")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(rounds.get(0).path("risk").path("level").asText()).isEqualTo("HIGH");
        assertThat(rounds.get(1).path("risk").path("level").asText()).isEqualTo("LOW");
        assertThat(rounds.get(1).path("risk").path("definitionId")).isEqualTo(rounds.get(0).path("risk").path("definitionId"));
    }

    private String riskDefinition() {
        var schema = new io.agentflow.form.FormSchema(1, List.of(
                new io.agentflow.form.FormSchema.Field("amount", "金额", io.agentflow.form.FormSchema.FieldType.NUMBER, false, null, null, null, null, null),
                new io.agentflow.form.FormSchema.Field("secret", "说明", io.agentflow.form.FormSchema.FieldType.TEXT, false, null, null, null, null, null)));
        var policy = new io.agentflow.definition.ApprovalRiskPolicy(List.of(
                new io.agentflow.definition.ApprovalRiskPolicy.Rule("low", "零金额", io.agentflow.approval.model.SubmissionRisk.Level.LOW, "amount == 0"),
                new io.agentflow.definition.ApprovalRiskPolicy.Rule("medium", "需复核", io.agentflow.approval.model.SubmissionRisk.Level.MEDIUM, "amount >= 50"),
                new io.agentflow.definition.ApprovalRiskPolicy.Rule("high", "重点复核", io.agentflow.approval.model.SubmissionRisk.Level.HIGH, "amount > 100")));
        return definition("user:manager", schema, policy);
    }

    private String definition(String rule) { return definition(rule, null); }
    private String definition(String rule, io.agentflow.form.FormSchema schema) {
        return definition(rule, schema, null);
    }
    private String definition(String rule, io.agentflow.form.FormSchema schema, io.agentflow.definition.ApprovalRiskPolicy riskPolicy) {
        String key = "queue-" + UUID.randomUUID();
        var draft = definitions.create("demo", key, "待办检索流程", new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()), new Node("manager", "经理审批", NodeType.USER_TASK, Map.of("assigneeRule", rule)),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("begin", "start", "manager", ""), new Edge("finish", "manager", "end", "")), 2, riskPolicy), schema);
        definitions.publish(new Actor("demo", "admin", Set.of("ADMIN")), draft.id(), 0, "待办队列验收发布");
        return key;
    }
    private JsonNode submit(String key, String user, String title, String amount) throws Exception {
        var payload = new HashMap<String, Object>(Map.of("secret", "private-body")); if (amount != null) payload.put("amount", amount);
        var draft = json.read(mvc.perform(post("/api/v1/applications").header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("businessNo", "TQ-" + UUID.randomUUID(), "processKey", key, "definitionVersion", 1, "title", title, "payload", payload))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), JsonNode.class);
        mvc.perform(post("/api/v1/applications/" + draft.path("id").asText() + "/submit").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        return draft;
    }
    private void act(String task, String user, String action, String target, long version) throws Exception {
        var body = new HashMap<String, Object>(Map.of("action", action, "expectedVersion", version, "comment", "队列办理验证"));
        if (target != null) body.put("targetUser", target);
        mvc.perform(post("/api/v1/tasks/" + task + "/actions").header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andExpect(status().isOk());
    }
    private org.springframework.test.web.servlet.ResultActions request(String user, Map<String, String> filters) throws Exception {
        var request = get("/api/v1/workspace/tasks").header("Authorization", token(user)); filters.forEach(request::param); return mvc.perform(request);
    }
    private JsonNode query(String user, Map<String, String> filters) throws Exception {
        return json.read(request(user, filters).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private JsonNode readTask(String task, String user) throws Exception {
        return json.read(mvc.perform(get("/api/v1/tasks/" + task).header("Authorization", token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), JsonNode.class);
    }
    private String task(String id) { return tasks.createTaskQuery().processVariableValueEquals("applicationId", id).singleResult().getId(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
