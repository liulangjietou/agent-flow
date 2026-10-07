package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.approval.process.FlowableApprovalProxyNotifications;
import io.agentflow.approval.process.FlowableTaskDeadlineListener;
import io.agentflow.approval.process.FlowableTaskDeadlineReminders;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.notification.InboxRepository;
import io.agentflow.notification.JdbcNotificationDeliveryStore;
import io.agentflow.notification.NotificationChannel;
import io.agentflow.notification.NotificationDelivery;
import io.agentflow.notification.NotificationDeliveryService;
import io.agentflow.notification.NotificationDestinations;
import io.agentflow.notification.NotificationPreferencesService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static io.agentflow.definition.DefinitionModels.*;
import static io.agentflow.notification.NotificationDeliveryProgress.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实申请、任务、代理行锁和外发队列验证提醒；提醒不会模拟认证角色或读取业务正文。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false",
        "agentflow.notifications.proxy-reminders-enabled=false", "agentflow.notifications.delivery-worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApprovalProxyNotificationsIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_NOTIFICATION_URL", "jdbc:h2:mem:approval-proxy-notifications;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
    }
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired OrganizationService organization;
    @Autowired ApprovalProxyService proxies;
    @Autowired DefinitionApplicationService definitions;
    @Autowired FlowableApprovalProxyNotifications notifications;
    @Autowired FlowableTaskDeadlineReminders reminders;
    @Autowired TaskService tasks;
    @Autowired RuntimeService runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationPreferencesService preferences;
    @Autowired NotificationDeliveryService deliveries;
    @Autowired JdbcNotificationDeliveryStore deliveryStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean OrganizationRepository directory;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean InboxRepository inbox;
    @MockitoSpyBean NotificationDestinations destinations;
    @MockitoSpyBean JdbcApprovalProxyRepository proxyRecords;
    private Actor admin;
    private OrganizationPerson principal;
    private OrganizationPerson substitute;
    private DefinitionDraft definition;
    private String applicantToken;
    private String substituteToken;
    private String principalToken;

    @BeforeEach void setup() {
        admin = new Actor("proxy-notice-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        principal = organization.createPerson(admin, "principal", "原审批人", true, true);
        substitute = organization.createPerson(admin, "substitute", "代理人", true, true);
        organization.createPerson(admin, "applicant", "申请人", true, true);
        applicantToken = identity("applicant", Set.of("EMPLOYEE", "APPROVER"));
        principalToken = identity("principal", Set.of("APPROVER"));
        substituteToken = identity("substitute", Set.of("EMPLOYEE", "APPROVER"));
        definition = publish();
    }

    @Test void futureProxyKeepsGrantOriginAfterSubmissionAndRevocation() throws Exception {
        Instant starts = Instant.now().plusSeconds(60); String trace = UUID.randomUUID().toString();
        ApprovalProxy proxy;
        try (var scope = new io.agentflow.observability.DiagnosticContext(trace, admin.tenantId()).open()) {
            proxy = proxies.create(admin, definition.id(), principal.id(), substitute.id(), starts, starts.plusSeconds(3600), "未来代理来源");
        }
        var task = submit();
        assertThat(notifications.candidates(starts, null)).filteredOn(value -> value.proxyId().equals(proxy.id()) && value.taskId().equals(task.getId()))
                .singleElement().extracting("traceId").isEqualTo(trace);
        try (var scope = new io.agentflow.observability.DiagnosticContext(UUID.randomUUID().toString(), admin.tenantId()).open()) {
            proxies.revoke(admin, proxy.id(), 1, "保留原授权来源");
        }
        assertThat(jdbc.queryForObject("SELECT trace_id FROM organization_approval_proxy WHERE tenant_id=? AND id=?", String.class, admin.tenantId(), proxy.id().toString())).isEqualTo(trace);
    }

    @Test void lateGrantCreatesOneMinimalNoticeWithoutClaimingTaskOrCreatingReadingAuthority() throws Exception {
        var task = submit(); var original = current(task); var proxy = grant();
        var candidate = candidate(proxy, task);
        assertThat(notifications.pending(candidate)).isTrue();
        assertThat(notifications.pending(candidate)).isFalse();
        assertThat(notifications.candidates(Instant.now(), null)).noneMatch(value -> value.proxyId().equals(candidate.proxyId()) && value.taskId().equals(candidate.taskId()));
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages).hasSize(1);
        assertThat(messages.toString()).doesNotContain("私密标题", "PRIVATE-", "私密节点", "private-value");
        assertThat(messages.get(0).path("content").asText()).contains("核对当前资格");
        assertThat(current(task).getAssignee()).isEqualTo(original.getAssignee());
        assertThat(current(task).getDueDate()).isEqualTo(original.getDueDate());
        assertThat(version(task)).isEqualTo(2);
        String roleless = identity("substitute", Set.of("EMPLOYEE"));
        read("/notifications", roleless, 200); read("/tasks/" + task.getId(), roleless, 403);
        proxies.revoke(admin, proxy.id(), 1, "原审批人返岗");
        read("/notifications", substituteToken, 200); read("/tasks/" + task.getId(), substituteToken, 403);
    }

    @Test void grantBeforeSubmissionAndFutureStartAreFoundWithoutChangingNativeTasks() throws Exception {
        Instant starts = Instant.now().plusSeconds(3);
        var proxy = proxies.create(admin, definition.id(), principal.id(), substitute.id(), starts, starts.plusSeconds(60), "未来代理");
        var task = submit(); var candidate = candidate(proxy, task);
        assertThat(notifications.candidates(Instant.now(), null)).noneMatch(value -> value.proxyId().equals(candidate.proxyId()) && value.taskId().equals(candidate.taskId()));
        assertThat(notifications.candidates(starts, null)).anyMatch(value -> value.proxyId().equals(candidate.proxyId()) && value.taskId().equals(candidate.taskId()));
        assertThat(notifications.pending(candidate)).isFalse();
        Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), starts).toMillis()) + 30);
        assertThat(notifications.pending(candidate)).isTrue();
        assertThat(current(task).getAssignee()).isNull();
    }

    @Test void revokedCandidateAndRetiredTaskCannotSendAndNewGrantDoesNotReuseOldSource() throws Exception {
        var task = submit(); var old = grant(); var oldCandidate = candidate(old, task);
        proxies.revoke(admin, old.id(), 1, "结束第一份代理");
        assertThat(notifications.pending(oldCandidate)).isFalse();
        var replacement = grant(); var replacementCandidate = candidate(replacement, task);
        assertThat(notifications.pending(replacementCandidate)).isTrue();
        assertThat(notifications.pending(oldCandidate)).isFalse();
        write("/tasks/" + task.getId() + "/actions", principalToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
        assertThat(notifications.pending(replacementCandidate)).isFalse();
        assertThat(sourceCount()).isEqualTo(1);
    }

    @Test void suspensionCurrentQualificationAndResponsibilityAreCheckedAgainAfterScan() throws Exception {
        var task = submit(); var proxy = grant(); var candidate = candidate(proxy, task);
        runtime.suspendProcessInstanceById(task.getProcessInstanceId());
        assertThat(notifications.pending(candidate)).isFalse();
        runtime.activateProcessInstanceById(task.getProcessInstanceId());
        organization.updatePerson(admin, principal.id(), principal.displayName(), false, true, 1);
        assertThat(notifications.pending(candidate)).isFalse();
        organization.updatePerson(admin, principal.id(), principal.displayName(), true, true, 2);
        tasks.setAssignee(task.getId(), "applicant");
        assertThat(notifications.pending(candidate)).isFalse();
        tasks.setAssignee(task.getId(), "principal");
        tasks.setOwner(task.getId(), "principal"); tasks.delegateTask(task.getId(), "applicant");
        assertThat(notifications.pending(candidate)).isFalse();
        tasks.resolveTask(task.getId());
        assertThat(notifications.pending(candidate)).isTrue();
    }

    @Test void concurrentWorkersKeepOneSourceOneMessageAndOneExternalIntent() throws Exception {
        enableEmail(); var task = submit(); var candidate = candidate(grant(), task);
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> send = () -> { start.await(); return notifications.pending(candidate); };
            var first = executor.submit(send); var second = executor.submit(send); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { start.countDown(); executor.shutdownNow(); }
        assertThat(sourceCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch WHERE tenant_id=?", Integer.class, admin.tenantId())).isEqualTo(1);
        assertThat(current(task).getAssignee()).isNull();
    }

    @Test void lateNotificationFailureRollsBackMessageSourceAndDispatchWithoutChangingTask() throws Exception {
        enableEmail(); var task = submit(); var candidate = candidate(grant(), task);
        var target = AopTestUtils.<InboxRepository>getUltimateTargetObject(inbox);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Synthetic proxy notification failure"); }).when(target).append(anyString(), any());
        assertThatThrownBy(() -> notifications.pending(candidate)).hasMessage("Synthetic proxy notification failure");
        assertThat(sourceCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_inbox WHERE tenant_id=? AND recipient_id='substitute'", Integer.class, admin.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch WHERE tenant_id=?", Integer.class, admin.tenantId())).isZero();
        assertThat(version(task)).isEqualTo(2); assertThat(current(task).getAssignee()).isNull();
        doCallRealMethod().when(target).append(anyString(), any());
        assertThat(notifications.pending(candidate)).isTrue();
        assertThat(notifications.pending(candidate)).isFalse();
    }

    @Test void revocationSuppressesUnsentExternalNoticeAndReplacementDoesNotReviveIt() throws Exception {
        enableEmail(); var task = submit(); var proxy = grant(); notifications.pending(candidate(proxy, task));
        var delivery = delivery(); proxies.revoke(admin, proxy.id(), 1, "停止原代理"); grant();
        assertThat(deliveries.claim(delivery.id(), Instant.now())).isNull();
        var stopped = deliveryStore.find(delivery.id()).orElseThrow();
        assertThat(stopped.progress().status()).isEqualTo(Status.SUPPRESSED);
        assertThat(stopped.progress().errorCode()).isEqualTo(FailureCode.MESSAGE_UNAVAILABLE);
        assertThat(stopped.progress().attempts()).isZero();
        assertThat(read("/notifications", substituteToken, 200).path("items")).hasSize(1);
    }

    @Test void expirationSuppressesQueuedExternalNoticeAndCannotResumeUnknownDelivery() throws Exception {
        enableEmail(); var task = submit(); Instant ends = Instant.now().plusSeconds(3);
        var proxy = proxies.create(admin, definition.id(), principal.id(), substitute.id(), Instant.now(), ends, "短期代理");
        notifications.pending(candidate(proxy, task)); var delivery = delivery();
        var claim = deliveries.claim(delivery.id(), Instant.now()); assertThat(claim).isNotNull();
        deliveries.finish(claim.delivery(), Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN), Instant.now());
        Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), ends).toMillis()) + 30);
        var current = deliveryStore.find(delivery.id()).orElseThrow();
        assertThat(deliveries.retryEligibility(current).allowed()).isFalse();
        assertThatThrownBy(() -> deliveries.retry(new Actor(admin.tenantId(), "substitute", Set.of("EMPLOYEE")), current.id(), current.progress().version(), true, "不能恢复到期代理", Instant.now()))
                .hasMessageContaining("no longer valid");
        assertThat(notifications.deliveryAllowed(current, Instant.now())).isFalse();
        assertThat(deliveryStore.find(delivery.id()).orElseThrow().progress().status()).isEqualTo(Status.UNKNOWN);
    }

    @Test void nativeSlaRemindsActiveProxyOnceAndKeepsOriginalDueDateAndHistoricalNotice() throws Exception {
        var task = submit(); grant(); Date dueAt = Date.from(Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MILLIS));
        tasks.setDueDate(task.getId(), dueAt);
        tasks.setVariableLocal(task.getId(), FlowableTaskDeadlineListener.CALENDAR_ID, "synthetic-calendar-binding");
        assertThat(reminders.remind(task.getId(), Instant.now())).isTrue();
        assertThat(reminders.remind(task.getId(), Instant.now())).isFalse();
        assertThat(sourceCount()).isEqualTo(1);
        var message = read("/notifications", substituteToken, 200).path("items").get(0);
        assertThat(message.path("kind").asText()).isEqualTo("TASK_OVERDUE");
        assertThat(message.path("content").asText()).contains("原处理期限");
        assertThat(current(task).getDueDate()).isEqualTo(dueAt); assertThat(current(task).getAssignee()).isNull();
        assertThat(notifications.candidates(Instant.now(), null)).noneMatch(candidate -> candidate.taskId().equals(task.getId()));
        assertThat(version(task)).isEqualTo(2);
    }

    @Test void expiryWhileWaitingForRecipientLockSuppressesAtTheActualObservationTime() throws Exception {
        enableEmail(); var task = submit(); Instant ends = Instant.now().plusSeconds(5);
        var proxy = proxies.create(admin, definition.id(), principal.id(), substitute.id(), Instant.now(), ends, "等待期间到期");
        notifications.pending(candidate(proxy, task)); var delivery = delivery();
        var held = new CountDownLatch(1); var waiting = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                directory.lock(admin.tenantId()); held.countDown();
                try { assertThat(release.await(12, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            doAnswer(call -> { waiting.countDown(); return call.callRealMethod(); })
                    .when(AopTestUtils.<OrganizationRepository>getUltimateTargetObject(directory)).lock(admin.tenantId());
            var claiming = executor.submit(() -> deliveries.claim(delivery.id(), Instant.now()));
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(claiming.isDone()).isFalse(); assertThat(Instant.now()).isBefore(ends);
            Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), ends).toMillis()) + 30);
            release.countDown(); blocker.get(5, TimeUnit.SECONDS);
            assertThat(claiming.get(5, TimeUnit.SECONDS)).isNull();
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        var stopped = deliveryStore.find(delivery.id()).orElseThrow();
        assertThat(stopped.progress().status()).isEqualTo(Status.SUPPRESSED);
        assertThat(stopped.progress().attempts()).isZero();
        assertThat(stopped.progress().changedAt()).isAfterOrEqualTo(ends);
    }

    @Test void candidatePagesReachTasksBeyondFirstBatchWithoutDependingOnDeliverySuccess() throws Exception {
        grant(); var expected = new java.util.HashSet<String>();
        for (int index = 0; index <= FlowableApprovalProxyNotifications.BATCH_SIZE; index++) expected.add(submit().getId());
        var seen = new java.util.HashSet<String>();
        FlowableApprovalProxyNotifications.Candidate after = null;
        int pages = 0;
        do {
            var page = notifications.candidates(Instant.now(), after); assertThat(page).hasSizeLessThanOrEqualTo(FlowableApprovalProxyNotifications.BATCH_SIZE);
            for (var candidate : page) if (candidate.tenantId().equals(admin.tenantId())) assertThat(seen.add(candidate.taskId())).isTrue();
            after = page.size() == FlowableApprovalProxyNotifications.BATCH_SIZE ? page.get(page.size() - 1) : null;
            assertThat(++pages).isLessThan(10);
        } while (after != null);
        assertThat(pages).isGreaterThanOrEqualTo(2); assertThat(seen).isEqualTo(expected);
        assertThat(sourceCount()).isZero();
    }

    @Test void repeatedPauseAndResumeNotifyCurrentProxyWithoutBusinessContentOrDuplicateEvent() throws Exception {
        var task = submit(); var proxy = grant();
        String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        String administrator = identity(admin.userId(), Set.of("ADMIN"));
        String path = "/applications/" + application + "/rounds/1/runtime/";
        for (int cycle = 0; cycle < 2; cycle++) {
            write(path + "pause", administrator, Map.of("expectedVersion", 2 + cycle * 2, "reason", "私密暂停原因"), 200);
            assertThat(current(task).isSuspended()).isTrue();
            write(path + "resume", administrator, Map.of("expectedVersion", 3 + cycle * 2, "reason", "私密恢复原因"), 200);
        }
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages).hasSize(4);
        assertThat(messages.toString()).doesNotContain("私密标题", "PRIVATE-", "私密节点", "private-value", "私密暂停原因", "私密恢复原因");
        assertThat(messages.findValuesAsText("kind")).containsExactlyInAnyOrder("APPLICATION_PAUSED", "APPLICATION_RESUMED", "APPLICATION_PAUSED", "APPLICATION_RESUMED");
        assertThat(current(task).getAssignee()).isNull();
        assertThat(sourceCount()).isEqualTo(4);
        var pending = candidate(proxy, task);
        assertThat(notifications.candidates(Instant.now(), null)).anyMatch(value -> value.proxyId().equals(pending.proxyId()) && value.taskId().equals(pending.taskId()));
        assertThat(notifications.pending(pending)).isTrue();
        assertThat(notifications.pending(pending)).isFalse();
        assertThat(sourceCount()).isEqualTo(5);
    }

    @Test void withdrawalNotifiesFormerProxyWithoutGrantingHistoricalApplicationAccess() throws Exception {
        var task = submit(); grant();
        String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        write("/applications/" + application + "/withdraw", applicantToken, Map.of("expectedVersion", 2, "comment", "私密撤回原因"), 200);
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("kind").asText()).isEqualTo("APPLICATION_WITHDRAWN");
        assertThat(messages.toString()).doesNotContain("私密标题", "PRIVATE-", "私密节点", "private-value", "私密撤回原因");
        assertThat(read("/applications/" + application, substituteToken, 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(sourceCount()).isEqualTo(1);
    }

    @Test void pausedTerminationKeepsMinimalHistoryAndOriginalGrantGovernsExternalRecovery() throws Exception {
        enableEmail(); var task = submit(); var proxy = grant();
        String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        String administrator = identity(admin.userId(), Set.of("ADMIN"));
        String path = "/applications/" + application + "/rounds/1/runtime/";
        write(path + "pause", administrator, Map.of("expectedVersion", 2, "reason", "暂停后结束"), 200);
        write(path + "terminate", administrator, Map.of("expectedVersion", 3, "reason", "私密终止原因"), 200);
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages.findValuesAsText("kind")).containsExactlyInAnyOrder("APPLICATION_PAUSED", "APPLICATION_CANCELLED");
        assertThat(messages.toString()).doesNotContain("私密标题", "PRIVATE-", "私密终止原因");
        assertThat(current(task)).isNull();
        read("/applications/" + application, substituteToken, 404);
        var delivery = lifecycleDelivery("APPLICATION_CANCELLED");
        var claim = deliveries.claim(delivery.id(), Instant.now()); assertThat(claim).isNotNull();
        deliveries.finish(claim.delivery(), Outcome.unknown(FailureCode.SMTP_RESULT_UNKNOWN), Instant.now());
        proxies.revoke(admin, proxy.id(), 1, "结束原授权"); grant();
        var unknown = deliveryStore.find(delivery.id()).orElseThrow();
        assertThat(deliveries.retryEligibility(unknown).allowed()).isFalse();
        assertThatThrownBy(() -> deliveries.retry(new Actor(admin.tenantId(), "substitute", Set.of("EMPLOYEE")),
                unknown.id(), unknown.progress().version(), true, "新授权不能恢复旧消息", Instant.now())).hasMessageContaining("no longer valid");
        assertThat(deliveries.claim(lifecycleDelivery("APPLICATION_PAUSED").id(), Instant.now())).isNull();
        assertThat(read("/notifications", substituteToken, 200).path("items")).hasSize(2);
    }

    @Test void lifecycleAppendFailureRollsBackRuntimeAndSameRequestRecoversOnlyOnce() throws Exception {
        enableEmail(); var task = submit(); grant();
        String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        String path = "/applications/" + application + "/rounds/1/runtime/pause", key = UUID.randomUUID().toString();
        String administrator = identity(admin.userId(), Set.of("ADMIN"));
        var body = Map.of("expectedVersion", 2, "reason", "原请求恢复");
        var hit = new java.util.concurrent.atomic.AtomicBoolean();
        var target = AopTestUtils.<InboxRepository>getUltimateTargetObject(inbox);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            io.agentflow.notification.InboxMessage message = call.getArgument(1);
            if (message.recipient().equals("substitute") && message.kind() == io.agentflow.notification.InboxMessage.Kind.APPLICATION_PAUSED) {
                hit.set(true); throw new IllegalStateException("Synthetic lifecycle failure");
            }
            return result;
        }).when(target).append(anyString(), any());
        assertThatThrownBy(() -> write(path, administrator, body, 200, key));
        assertThat(hit).isTrue(); assertThat(current(task).isSuspended()).isFalse(); assertThat(version(task)).isEqualTo(2);
        assertThat(sourceCount()).isZero(); assertThat(read("/notifications", substituteToken, 200).path("items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dispatch WHERE tenant_id=?", Integer.class, admin.tenantId())).isZero();
        doCallRealMethod().when(target).append(anyString(), any());
        var receipt = write(path, administrator, body, 200, key);
        assertThat(write(path, administrator, body, 200, key)).isEqualTo(receipt);
        assertThat(version(task)).isEqualTo(3); assertThat(sourceCount()).isEqualTo(1);
        assertThat(read("/notifications", substituteToken, 200).path("items")).hasSize(1);
    }

    @Test void returnAndRejectionNotifyOnlyTheFormerScopeAndDoNotGrantHistory() throws Exception {
        grant();
        for (String action : List.of("RETURN", "REJECT")) {
            var task = submit(); String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
            write("/tasks/" + task.getId() + "/actions", principalToken, Map.of("action", action, "expectedVersion", 2, "comment", "私密决定理由"), 200);
            read("/applications/" + application, substituteToken, 404);
        }
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages.findValuesAsText("kind")).containsExactlyInAnyOrder("APPLICATION_RETURNED", "APPLICATION_REJECTED");
        assertThat(messages.toString()).doesNotContain("私密标题", "PRIVATE-", "私密决定理由");
        assertThat(sourceCount()).isEqualTo(2);
    }

    @Test void countersignRemovalAndThresholdCompletionPreserveProxyNoticeWithoutInventingVotes() throws Exception {
        var other = organization.createPerson(admin, "other", "独立会签人", true, true);
        String otherToken = identity(other.subject(), Set.of("APPROVER"));
        for (String mode : List.of("ALL", "ANY")) {
            definition = shared(mode, List.of(principal, other)); grant();
            var application = submittedApplication(); var pending = tasksFor(application.path("id").asText());
            var acting = pending.stream().filter(task -> other.subject().equals(task.getAssignee())).findFirst().orElseThrow();
            var represented = pending.stream().filter(task -> principal.subject().equals(task.getAssignee())).findFirst().orElseThrow();
            if (mode.equals("ALL")) write("/tasks/" + acting.getId() + "/countersign-changes", otherToken,
                    Map.of("action", "REMOVE", "targetTaskId", represented.getId(), "expectedVersion", 2, "reason", "私密减签理由"), 200);
            else write("/tasks/" + acting.getId() + "/actions", otherToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200);
            assertThat(current(represented)).isNull();
        }
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages.findValuesAsText("kind")).containsExactlyInAnyOrder("TASK_COUNTERSIGN_REMOVED", "TASK_COUNTERSIGN_COMPLETED");
        assertThat(messages.toString()).doesNotContain("私密减签理由", "私密标题", "PRIVATE-");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id=? AND actor_id='substitute' AND action='APPROVE'", Integer.class, admin.tenantId())).isZero();
    }

    @Test void pausedCountersignDoesNotAddAnotherProxyResponsibilityForANativeMember() throws Exception {
        definition = shared("ALL", List.of(principal, substitute)); grant();
        String application = submittedApplication().path("id").asText();
        String administrator = identity(admin.userId(), Set.of("ADMIN")), path = "/applications/" + application + "/rounds/1/runtime/";
        write(path + "pause", administrator, Map.of("expectedVersion", 2, "reason", "暂停会签"), 200);
        write(path + "terminate", administrator, Map.of("expectedVersion", 3, "reason", "结束会签"), 200);
        assertThat(sourceCount()).isZero();
        assertThat(read("/notifications", substituteToken, 200).path("items").findValuesAsText("kind"))
                .containsExactlyInAnyOrder("TASK_PENDING", "APPLICATION_PAUSED", "APPLICATION_CANCELLED");
    }

    @Test void expiryAfterAudienceCaptureCannotPersistAnExpiredLifecycleSource() throws Exception {
        var task = submit(); Instant ends = Instant.now().plusSeconds(6);
        proxies.create(admin, definition.id(), principal.id(), substitute.id(), Instant.now(), ends, "状态变更期间到期");
        String application = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        String administrator = identity(admin.userId(), Set.of("ADMIN"));
        var waiting = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            var message = call.<io.agentflow.notification.InboxMessage>getArgument(1);
            if (message.kind() == io.agentflow.notification.InboxMessage.Kind.APPLICATION_PAUSED && !message.recipient().equals(substitute.subject())) {
                waiting.countDown(); assertThat(release.await(12, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(inbox).append(anyString(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pausing = executor.submit(() -> write("/applications/" + application + "/rounds/1/runtime/pause", administrator,
                    Map.of("expectedVersion", 2, "reason", "先捕获接收范围再观察授权"), 200));
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(pausing.isDone()).isFalse(); assertThat(Instant.now()).isBefore(ends);
            Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), ends).toMillis()) + 30);
            release.countDown(); pausing.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        assertThat(current(task).isSuspended()).isTrue(); assertThat(version(task)).isEqualTo(3);
        assertThat(sourceCount()).isZero(); assertThat(read("/notifications", substituteToken, 200).path("items")).isEmpty();
    }

    @Test void parentPauseResumeAndTerminationNotifyTheChildProxyOnlyAboutItsOwnApplication() throws Exception {
        var child = definition; grant();
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("call", "私密父调用", NodeType.SUB_PROCESS, new io.agentflow.definition.SubprocessPolicy(child.key(), child.version(), Map.of()).properties()),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "call", ""), new Edge("b", "call", "end", "")));
        var draft = definitions.create(admin.tenantId(), "parent_" + UUID.randomUUID().toString().replace("-", ""), "父流程", graph);
        definition = definitions.publish(admin, draft.id(), draft.revision(), "父子代理提醒");
        String parent = submittedApplication().path("id").asText();
        var task = tasks.createTaskQuery().processVariableValueEquals("tenantId", admin.tenantId()).singleResult();
        String childApplication = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        assertThat(childApplication).isNotEqualTo(parent);
        String administrator = identity(admin.userId(), Set.of("ADMIN")), path = "/applications/" + parent + "/rounds/1/runtime/";
        write(path + "pause", administrator, Map.of("expectedVersion", 2, "reason", "父流程暂停"), 200);
        write(path + "resume", administrator, Map.of("expectedVersion", 3, "reason", "父流程恢复"), 200);
        write(path + "terminate", administrator, Map.of("expectedVersion", 4, "reason", "父流程结束"), 200);
        var messages = read("/notifications", substituteToken, 200).path("items");
        assertThat(messages).hasSize(3);
        assertThat(messages.findValuesAsText("applicationId")).containsOnly(childApplication);
        assertThat(messages.findValuesAsText("kind")).containsExactlyInAnyOrder("APPLICATION_PAUSED", "APPLICATION_RESUMED", "APPLICATION_CANCELLED");
        read("/applications/" + parent, substituteToken, 404); read("/applications/" + childApplication, substituteToken, 404);
    }

    @Test void concurrentProxyApprovalsDoNotAcquireEachOthersNotificationGrantLocks() throws Exception {
        var other = organization.createPerson(admin, "other", "另一原审批人", true, true);
        var otherSubstitute = organization.createPerson(admin, "other-substitute", "另一代理人", true, true);
        String otherToken = identity(otherSubstitute.subject(), Set.of("APPROVER"));
        definition = shared("ANY", List.of(principal, other)); grant();
        proxies.create(admin, definition.id(), other.id(), otherSubstitute.id(), Instant.now(), Instant.now().plusSeconds(3600), "另一直接代理");
        String firstApplication = submittedApplication().path("id").asText(), secondApplication = submittedApplication().path("id").asText();
        var first = tasksFor(firstApplication).stream().filter(task -> principal.subject().equals(task.getAssignee())).findFirst().orElseThrow();
        var second = tasksFor(secondApplication).stream().filter(task -> other.subject().equals(task.getAssignee())).findFirst().orElseThrow();
        var held = new CountDownLatch(2); var observed = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (observed.add(Thread.currentThread())) {
                held.countDown(); assertThat(held.await(8, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(AopTestUtils.<JdbcApprovalProxyRepository>getUltimateTargetObject(proxyRecords)).lock(eq(admin.tenantId()), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> write("/tasks/" + first.getId() + "/actions", substituteToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200));
            var two = executor.submit(() -> write("/tasks/" + second.getId() + "/actions", otherToken, Map.of("action", "APPROVE", "expectedVersion", 2), 200));
            assertThat(one.get(20, TimeUnit.SECONDS).path("applicationStatus").asText()).isEqualTo("APPROVED");
            assertThat(two.get(20, TimeUnit.SECONDS).path("applicationStatus").asText()).isEqualTo("APPROVED");
        } finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        assertThat(sourceCount()).isEqualTo(2);
        var firstMessages = read("/notifications", substituteToken, 200).path("items");
        var secondMessages = read("/notifications", otherToken, 200).path("items");
        assertThat(firstMessages.findValuesAsText("kind")).containsExactly("TASK_COUNTERSIGN_COMPLETED");
        assertThat(secondMessages.findValuesAsText("kind")).containsExactly("TASK_COUNTERSIGN_COMPLETED");
        assertThat(firstMessages.findValuesAsText("applicationId")).containsExactly(secondApplication);
        assertThat(secondMessages.findValuesAsText("applicationId")).containsExactly(firstApplication);
    }

    private NotificationDelivery lifecycleDelivery(String kind) {
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT d.id FROM notification_dispatch d JOIN approval_proxy_notification n ON n.tenant_id=d.tenant_id AND n.inbox_id=d.inbox_id WHERE d.tenant_id=? AND n.kind=?", String.class, admin.tenantId(), kind));
        return deliveryStore.find(id).orElseThrow();
    }

    private DefinitionDraft shared(String mode, List<OrganizationPerson> people) {
        var legal = organization.createUnit(admin, OrganizationUnit.Kind.LEGAL_ENTITY, "会签法人", null, null, true);
        var department = organization.createUnit(admin, OrganizationUnit.Kind.DEPARTMENT, "会签部门", legal.id(), null, true);
        var position = organization.createUnit(admin, OrganizationUnit.Kind.POSITION, "会签岗位", legal.id(), null, true);
        for (var person : people) organization.createAppointment(admin, person.id(), department.id(), position.id(), true);
        return publish(Map.of("assigneeRule", "role:ORG_UNIT_" + department.id(), "approvalMode", mode));
    }

    private DefinitionDraft publish() {
        return publish(Map.of("assigneeRule", "role:ORG_PERSON_" + principal.id()));
    }
    private DefinitionDraft publish(Map<String, String> properties) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "私密节点", NodeType.USER_TASK, properties),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""), new Edge("b", "review", "end", "")));
        var draft = definitions.create(admin.tenantId(), "proxy_notice_" + UUID.randomUUID().toString().replace("-", ""), "通知范围", graph);
        return definitions.publish(admin, draft.id(), draft.revision(), "代理提醒验证");
    }
    private Task submit() throws Exception {
        return tasksFor(submittedApplication().path("id").asText()).get(0);
    }
    private List<Task> tasksFor(String application) {
        return tasks.createTaskQuery().processVariableValueEquals("applicationId", application).list();
    }
    private JsonNode submittedApplication() throws Exception {
        var created = write("/applications", applicantToken, Map.of("businessNo", "PRIVATE-" + UUID.randomUUID(), "processKey", definition.key(),
                "definitionVersion", definition.version(), "title", "私密标题", "payload", Map.of("secret", "private-value")), 201);
        String id = created.path("id").asText(); return write("/applications/" + id + "/submit", applicantToken, Map.of("expectedVersion", 1), 200);
    }
    private ApprovalProxy grant() {
        return proxies.create(admin, definition.id(), principal.id(), substitute.id(), Instant.now(), Instant.now().plusSeconds(3600), "临时代理");
    }
    private FlowableApprovalProxyNotifications.Candidate candidate(ApprovalProxy proxy, Task task) {
        String id = (String) runtime.getVariable(task.getProcessInstanceId(), "applicationId");
        return new FlowableApprovalProxyNotifications.Candidate(admin.tenantId(), proxy.id(), UUID.fromString(id), task.getId());
    }
    private Task current(Task task) { return tasks.createTaskQuery().taskId(task.getId()).singleResult(); }
    private long version(Task task) {
        return jdbc.queryForObject("SELECT version FROM approval_application WHERE tenant_id=? AND id=?", Long.class,
                admin.tenantId(), runtime.getVariable(task.getProcessInstanceId(), "applicationId"));
    }
    private int sourceCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM approval_proxy_notification WHERE tenant_id=?", Integer.class, admin.tenantId()); }
    private void enableEmail() {
        preferences.revise(new Actor(admin.tenantId(), "substitute", Set.of("EMPLOYEE")), 0, true, false);
        var destination = new NotificationDestinations.Destination("proxy-test-email", admin.tenantId(), "substitute", NotificationChannel.EMAIL,
                "synthetic@example.invalid", null, "http://127.0.0.1:1", true, "a".repeat(64), null);
        doReturn(Optional.of(destination)).when(destinations).find(admin.tenantId(), "substitute", NotificationChannel.EMAIL);
    }
    private NotificationDelivery delivery() {
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT id FROM notification_dispatch WHERE tenant_id=?", String.class, admin.tenantId()));
        return deliveryStore.find(id).orElseThrow();
    }
    private String identity(String subject, Set<String> roles) {
        String token = UUID.randomUUID().toString(); doReturn(new Actor(admin.tenantId(), subject, roles)).when(auth).authenticate(token); return token;
    }
    private JsonNode read(String path, String token, int statusCode) throws Exception {
        var response = mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token)).andExpect(status().is(statusCode)).andReturn().getResponse();
        return json.read(response.getContentAsString(), JsonNode.class);
    }
    private JsonNode write(String path, String token, Object body, int statusCode) throws Exception {
        return write(path, token, body, statusCode, UUID.randomUUID().toString());
    }
    private JsonNode write(String path, String token, Object body, int statusCode, String key) throws Exception {
        var result = mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body))).andReturn();
        var response = result.getResponse();
        assertThat(response.getStatus()).as("HTTP %s: %s, cause=%s", path, response.getContentAsString(), result.getResolvedException()).isEqualTo(statusCode);
        return json.read(response.getContentAsString(), JsonNode.class);
    }
}
