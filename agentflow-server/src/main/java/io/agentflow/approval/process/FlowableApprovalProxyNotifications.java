package io.agentflow.approval.process;


import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.process.mapper.FlowableApprovalProxyNotificationsMapper;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import io.agentflow.notification.NotificationDelivery;
import io.agentflow.notification.TaskAudiencePort;
import io.agentflow.organization.ApprovalProxyRepository;
import io.agentflow.organization.OrganizationRepository;

import org.flowable.engine.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 将真实待办与已生效的直接代理相交，最小提醒不携带业务正文或替代身份源授权。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class FlowableApprovalProxyNotifications {
    public static final int BATCH_SIZE = 100;

    private final FlowableApprovalProxyNotificationsMapper sqlMapper;
    private final TaskService tasks;
    private final ApplicationRepository applications;
    private final SubprocessExecutionLocks executionLocks;
    private final ApprovalProxyRepository proxies;
    private final OrganizationRepository organization;
    private final FlowableApprovalProxyAccess access;
    private final InboxRepository inbox;

    /** 申请锁先于代理锁；消息、来源和外发意向加入同一事务，不在这里发送网络请求。 */
    public FlowableApprovalProxyNotifications(
            FlowableApprovalProxyNotificationsMapper sqlMapper,
            TaskService tasks,
            ApplicationRepository applications,
            SubprocessExecutionLocks executionLocks,
            ApprovalProxyRepository proxies,
            OrganizationRepository organization,
            FlowableApprovalProxyAccess access,
            InboxRepository inbox) {
        this.sqlMapper = sqlMapper;
        this.tasks = tasks;
        this.applications = applications;
        this.executionLocks = executionLocks;
        this.proxies = proxies;
        this.organization = organization;
        this.access = access;
        this.inbox = inbox;
    }

    /** 新任务、在审新增代理和未来期限生效使用同一有界扫描；旧消息不补发到新开启的渠道。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Instant now, Candidate after) {
        var parameters = times(now);

        if (after != null) {

            parameters.add(after.tenantId());
            parameters.add(after.tenantId());
            parameters.add(after.proxyId().toString());
            parameters.add(after.proxyId().toString());
            parameters.add(after.taskId());
        }
        parameters.add(BATCH_SIZE);
        return SqlRows.map(
                sqlMapper.candidatesQuery((after != null), parameters.toArray()),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("proxy_id")),
                                UUID.fromString(row.getString("application_id")),
                                row.getString("task_id"),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 锁等待之后重新观察期限和任务，不把扫描时的候选项当成通知或读取授权。 */
    @Transactional
    public boolean pending(Candidate candidate) { return notify(candidate, InboxMessage.Kind.TASK_PENDING); }

    /** 原 SLA 到期事件同时提醒当时有效的代理，不修改原到期时间或重建超时事件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void overdue(String tenantId, String taskId) {
        var parameters = times(Instant.now());
        parameters.add(tenantId);
        parameters.add(taskId);
        var candidates =
                SqlRows.map(
                        sqlMapper.overdue(parameters.toArray()),
                        row ->
                                new Candidate(
                                        row.getString("tenant_id"),
                                        UUID.fromString(row.getString("proxy_id")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getString("task_id"),
                                        row.getString("trace_id"),
                                        row.getString("business_no"),
                                        row.getString("process_instance_id")));
        for (var candidate : candidates) notify(candidate, InboxMessage.Kind.TASK_OVERDUE);
    }

    private boolean notify(Candidate candidate, InboxMessage.Kind kind) {
        var initial =
                applications.findById(candidate.tenantId(), candidate.applicationId()).orElse(null);
        if (initial == null) return false;
        var path = executionLocks.lockPath(initial);
        if (path.ancestors() != SubprocessExecutionLocks.AncestorState.ACTIVE) return false;
        var proxy = proxies.lock(candidate.tenantId(), candidate.proxyId()).orElse(null);
        if (proxy == null) return false;
        var person = organization.person(candidate.tenantId(), proxy.substituteId()).orElse(null);
        var task =
                tasks.createTaskQuery()
                        .taskId(candidate.taskId())
                        .active()
                        .includeProcessVariables()
                        .includeIdentityLinks()
                        .singleResult();
        Instant observedAt = Instant.now();
        if (person == null
                || task == null
                || !candidate
                        .applicationId()
                        .toString()
                        .equals(task.getProcessVariables().get("applicationId"))
                || !access.canNotify(
                        candidate.tenantId(), person.subject(), proxy.id(), task, observedAt))
            return false;
        if (kind == InboxMessage.Kind.TASK_OVERDUE
                && (task.getDueDate() == null || task.getDueDate().toInstant().isAfter(observedAt)))
            return false;
        if (Boolean.TRUE.equals(
                SqlRows.single(
                        sqlMapper.notify(
                                candidate.tenantId(),
                                proxy.id().toString(),
                                task.getId(),
                                kind.name(),
                                kind.name())))) return false;
        String eventKey = "task-proxy:" + proxy.id() + ":" + task.getId() + ":" + kind.name();
        UUID messageId =
                UUID.nameUUIDFromBytes(
                        (candidate.tenantId() + ":" + eventKey + ":" + person.subject())
                                .getBytes(StandardCharsets.UTF_8));
        String content =
                kind == InboxMessage.Kind.TASK_OVERDUE
                        ? "代理范围内有待办已超过原处理期限，请登录核对当前资格和任务状态。"
                        : "代理范围内有待办，请登录核对当前资格、任务状态和原处理期限。";
        // 目录不能证明身份源角色，因此不复制申请标题、单号、节点名称或自定义文案。
        inbox.append(
                eventKey,
                new InboxMessage(
                        messageId,
                        candidate.tenantId(),
                        person.subject(),
                        candidate.applicationId(),
                        "代理审批提醒",
                        "",
                        kind,
                        "system:approval-proxy",
                        task.getId(),
                        null,
                        path.application().roundNo(),
                        observedAt,
                        null,
                        content));
        sqlMapper.notify2(
                candidate.tenantId(),
                proxy.id().toString(),
                task.getId(),
                kind.name(),
                messageId.toString());
        return true;
    }

    /** 调用方持有申请锁，在暂停或删除任务前保存原生责任范围；此处不先锁代理，避免后续财务收尾反转锁序。 暂停任务可接收取消事实，但这份快照不允许读取或办理，入库前仍需重新核对原授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TaskAudiencePort.Audience> capture(
            Application application, List<TaskAudiencePort.Audience> previous) {
        if (previous.isEmpty()) return previous;
        Instant now = Instant.now();
        var parameters = times(now);
        parameters.add(application.tenantId());
        parameters.add(application.id().toString());
        var candidates =
                SqlRows.map(
                        sqlMapper.capture(parameters.toArray()),
                        row ->
                                new Candidate(
                                        row.getString("tenant_id"),
                                        UUID.fromString(row.getString("proxy_id")),
                                        UUID.fromString(row.getString("application_id")),
                                        row.getString("task_id"),
                                        row.getString("trace_id"),
                                        row.getString("business_no"),
                                        row.getString("process_instance_id")));
        var targets = new HashMap<String, List<TaskAudiencePort.ProxyRecipient>>();
        for (var candidate : candidates) {
            var original =
                    previous.stream()
                            .filter(item -> item.taskId().equals(candidate.taskId()))
                            .findFirst()
                            .orElse(null);
            if (original == null) continue;
            var proxy = proxies.find(candidate.tenantId(), candidate.proxyId()).orElse(null);
            if (proxy == null) continue;
            var person =
                    organization.person(candidate.tenantId(), proxy.substituteId()).orElse(null);
            var task =
                    tasks.createTaskQuery()
                            .taskId(candidate.taskId())
                            .includeProcessVariables()
                            .includeIdentityLinks()
                            .singleResult();
            if (person == null
                    || task == null
                    || original.recipients().contains(person.subject())
                    || !access.canNotifyUnfinished(
                            candidate.tenantId(), person.subject(), proxy.id(), task, now))
                continue;
            targets.computeIfAbsent(task.getId(), key -> new ArrayList<>())
                    .add(new TaskAudiencePort.ProxyRecipient(proxy.id(), person.subject()));
        }
        return previous.stream()
                .map(
                        item ->
                                new TaskAudiencePort.Audience(
                                        item.taskId(),
                                        item.nodeName(),
                                        item.recipients(),
                                        List.copyOf(
                                                targets.getOrDefault(item.taskId(), List.of()))))
                .toList();
    }

    /** 状态事实随业务同事务保存，按原申请版本防重，并在发送前重新观察原授权。 最小通知不授予权利，不追加其他代理的写锁，避免与另一申请已经选定的办理代理形成环路；外发仍重新鉴权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lifecycle(
            Application application,
            InboxMessage.Kind kind,
            List<TaskAudiencePort.Audience> previous,
            Set<String> nativeRecipients) {
        String content =
                switch (kind) {
                    case APPLICATION_PAUSED -> "原代理范围内的申请发生暂停，请登录核对当前状态。";
                    case APPLICATION_RESUMED -> "原代理范围内的申请已恢复，请登录核对当前资格、任务和原处理期限。";
                    case APPLICATION_WITHDRAWN -> "原代理范围内的申请已撤回，本条提醒不表示仍有处理权限。";
                    case APPLICATION_CANCELLED -> "原代理范围内的申请已取消，本条提醒不表示仍有处理权限。";
                    case APPLICATION_RETURNED -> "原代理范围内的申请已退回，本条提醒不表示仍有处理权限。";
                    case APPLICATION_REJECTED -> "原代理范围内的申请已驳回，本条提醒不表示仍有处理权限。";
                    case TASK_COUNTERSIGN_REMOVED -> "原代理范围内的会签责任已移除，本条提醒不表示已经作出审批意见。";
                    case TASK_COUNTERSIGN_COMPLETED -> "原代理范围内的会签待办已随节点结束，本条提醒不表示已经作出审批意见。";
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported approval proxy lifecycle event");
                };
        var targets =
                previous.stream()
                        .flatMap(
                                task ->
                                        task.proxies().stream()
                                                .map(
                                                        proxy ->
                                                                new LifecycleTarget(
                                                                        task.taskId(), proxy)))
                        .filter(target -> !nativeRecipients.contains(target.proxy().subject()))
                        .sorted(
                                Comparator.comparing(
                                                (LifecycleTarget target) ->
                                                        target.proxy().proxyId().toString())
                                        .thenComparing(LifecycleTarget::taskId))
                        .toList();
        for (var target : targets) {
            UUID proxyId = target.proxy().proxyId();
            Instant now = Instant.now();
            if (!originalGrantActive(
                    application.tenantId(), target.proxy().subject(), proxyId, now)) continue;
            if (Boolean.TRUE.equals(
                    SqlRows.single(
                            sqlMapper.lifecycle(
                                    application.tenantId(),
                                    proxyId.toString(),
                                    target.taskId(),
                                    kind.name(),
                                    application.version())))) continue;
            String eventKey =
                    "task-proxy:"
                            + proxyId
                            + ":"
                            + target.taskId()
                            + ":"
                            + kind
                            + ":"
                            + application.version();
            UUID id =
                    UUID.nameUUIDFromBytes(
                            (application.tenantId()
                                            + ":"
                                            + eventKey
                                            + ":"
                                            + target.proxy().subject())
                                    .getBytes(StandardCharsets.UTF_8));
            inbox.append(
                    eventKey,
                    new InboxMessage(
                            id,
                            application.tenantId(),
                            target.proxy().subject(),
                            application.id(),
                            "代理审批状态提醒",
                            "",
                            kind,
                            "system:approval-proxy",
                            target.taskId(),
                            null,
                            application.roundNo(),
                            now,
                            null,
                            content));
            sqlMapper.lifecycle2(
                    application.tenantId(),
                    proxyId.toString(),
                    target.taskId(),
                    kind.name(),
                    id.toString(),
                    application.version());
        }
    }

    /** 外发领取和人工恢复只复核原消息的那份代理，新授权不能复活旧的待发提醒。 */
    public boolean deliveryAllowed(NotificationDelivery delivery, Instant now) {
        var sources =
                SqlRows.map(
                        sqlMapper.deliveryAllowed(
                                delivery.tenantId(), delivery.inboxId().toString()),
                        row ->
                                new Source(
                                        UUID.fromString(row.getString("proxy_id")),
                                        row.getString("task_id"),
                                        InboxMessage.Kind.valueOf(row.getString("kind")),
                                        row.getLong("event_version")));
        if (sources.isEmpty()) return true;
        var source = sources.get(0);
        // 状态通知记录已发生事实，任务可能已删除；只保留原授权和双方当前资格，不重新授予任务权利。
        if (source.eventVersion() > 0)
            return originalGrantActive(
                    delivery.tenantId(), delivery.recipient(), source.proxyId(), now);
        var task =
                tasks.createTaskQuery()
                        .taskId(source.taskId())
                        .active()
                        .includeProcessVariables()
                        .includeIdentityLinks()
                        .singleResult();
        return task != null
                && access.canNotify(
                        delivery.tenantId(), delivery.recipient(), source.proxyId(), task, now)
                && (source.kind() != InboxMessage.Kind.TASK_OVERDUE
                        || task.getDueDate() != null
                                && !task.getDueDate().toInstant().isAfter(now));
    }

    private boolean originalGrantActive(String tenantId, String recipient, UUID proxyId, Instant now) {
        return proxies.activeForSubstitute(tenantId, recipient, now).stream().anyMatch(active -> active.proxy().id().equals(proxyId));
    }

    private static ArrayList<Object> times(Instant now) {
        var time = Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return new ArrayList<>(List.of(time, time, time));
    }

    /**
     * 扫描游标只携带原始关联，实际处理必须在锁内重查。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID proxyId,
            UUID applicationId,
            String taskId,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧扫描不推测业务关联；已知的原生任务编号仍可用于诊断。 */
        public Candidate(String tenantId, UUID proxyId, UUID applicationId, String taskId, String traceId) { this(tenantId, proxyId, applicationId, taskId, traceId, null, null); }

        /** 显式业务调用和旧来源不伪造异步创建来源。 */
        public Candidate(String tenantId, UUID proxyId, UUID applicationId, String taskId) { this(tenantId, proxyId, applicationId, taskId, null); }
    }

    /**
     * 来源仅用于抑制失效外发，不赋予消息持有人申请或表单读取权。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Source(UUID proxyId, String taskId, InboxMessage.Kind kind, long eventVersion) {}

    /**
     * 同一事务中捕获的原任务和代理接收人，按代理编号与任务编号稳定排列。
     *
     * @author owlzhangfq@gmail.com
     */
    private record LifecycleTarget(String taskId, TaskAudiencePort.ProxyRecipient proxy) {}
}
