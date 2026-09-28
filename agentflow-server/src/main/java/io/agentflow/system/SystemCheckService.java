package io.agentflow.system;

import io.agentflow.common.Actor;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 编排管理员只读自检。单诊断线程最多排队一项，依赖卡住时不会创建无限后台任务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SystemCheckService {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private final SystemDiagnostics diagnostics;
    private final ClasspathProcessTemplateCatalog catalog;
    private final boolean demoEnabled;
    private final boolean oidcEnabled;
    private final boolean jdbcSessions;
    private final Duration timeout;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "system-diagnostics");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 创建自检查询服务。 */
    @Autowired
    public SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog,
                              @Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled,
                              @Value("${agentflow.auth.oidc.enabled:false}") boolean oidcEnabled,
                              @Value("${agentflow.auth.session.jdbc-enabled:false}") boolean jdbcSessions) {
        this(diagnostics, catalog, demoEnabled, oidcEnabled, jdbcSessions, PROBE_TIMEOUT);
    }

    SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog, boolean demoEnabled) {
        this(diagnostics, catalog, demoEnabled, false, false, PROBE_TIMEOUT);
    }

    SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog,
                       boolean demoEnabled, boolean oidcEnabled) {
        this(diagnostics, catalog, demoEnabled, oidcEnabled, false, PROBE_TIMEOUT);
    }

    SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog,
                       boolean demoEnabled, Duration timeout) {
        this(diagnostics, catalog, demoEnabled, false, false, timeout);
    }

    private SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog,
                       boolean demoEnabled, boolean oidcEnabled, boolean jdbcSessions, Duration timeout) {
        this.diagnostics = diagnostics;
        this.catalog = catalog;
        this.demoEnabled = demoEnabled;
        this.oidcEnabled = oidcEnabled;
        this.jdbcSessions = jdbcSessions;
        this.timeout = timeout;
    }

    /** 入口校验管理员权限后才调度依赖检查，结果不包含配置值或异常原文。 */
    public Report check(Actor actor) {
        actor.requireRole("ADMIN");
        List<Check> checks = new ArrayList<>();
        checks.add(inspect("database", () -> {
            diagnostics.database();
            return up("database", "数据库查询成功。");
        }));
        checks.add(inspect("migrations", () -> up("migrations", "迁移校验通过，当前版本 V" + diagnostics.migrations() + "。")));
        checks.add(inspect("flowable", () -> {
            diagnostics.flowable(actor.tenantId());
            return up("flowable", "流程定义、实例、任务与历史查询均成功。");
        }));
        var templates = catalog.list();
        int scenarios = templates.stream().mapToInt(template -> template.scenarios().size()).sum();
        checks.add(up("templates", "已加载 " + templates.size() + " 个模板，启动时验证 " + scenarios + " 个路由场景。"));
        checks.add(new Check("authentication", Status.WARNING,
                oidcEnabled ? "OIDC_CONFIGURED" : demoEnabled ? "DEMO_AUTH_ONLY" : "AUTH_PROVIDER_NOT_CONFIGURED",
                oidcEnabled ? "已配置企业 OIDC 登录；本次未探测身份服务可用性，组织状态见本地目录检查。"
                        : demoEnabled ? "使用演示账号，企业身份认证尚未接入。" : "演示登录已关闭，企业身份认证尚未接入。"));
        checks.add(inspect("notifications", () -> {
            diagnostics.notifications(actor.tenantId());
            return new Check("notifications", Status.WARNING, "IN_APP_ONLY", "站内消息存储查询成功；邮件和 IM 尚未接入。本项不验证超时提醒调度是否运行。");
        }));
        checks.add(jdbcSessions ? inspect("sessionStorage", () -> {
            diagnostics.sessions();
            return up("sessionStorage", "共享会话存储查询成功；空闲超时、令牌到期和当前身份映射仍受校验。");
        }) : new Check("sessionStorage", Status.WARNING, "JDBC_SESSIONS_DISABLED", oidcEnabled
                ? "当前使用单实例内存会话；多实例部署需显式启用共享会话。"
                : "共享会话未启用；此能力适用于企业 OIDC 登录。"));
        checks.add(inspect("organization", () -> diagnostics.organization(actor.tenantId())
                ? new Check("organization", Status.UP, "LOCAL_ORGANIZATION_ENABLED",
                        "本租户已启用本地组织目录，存储查询成功；人员、任职与审批资格需在组织管理中核对。")
                : new Check("organization", Status.WARNING, "LOCAL_ORGANIZATION_NOT_INITIALIZED",
                        "本租户尚未启用本地组织目录；管理员可在“组织与人员”中启用并配置。")));
        checks.add(inspect("objectStorage", () -> diagnostics.attachments(actor.tenantId())
                ? new Check("objectStorage", Status.UP, "LOCAL_ATTACHMENT_STORAGE",
                        "附件元数据可查询，持久目录访问权限正常；本项未写入文件或校验全部存量内容。当前未接入内容扫描。")
                : new Check("objectStorage", Status.WARNING, "ATTACHMENT_STORAGE_NOT_CONFIGURED",
                        "附件持久目录尚未配置；管理员配置后可在申请表单中上传。")));
        checks.add(new Check("model", Status.NOT_IMPLEMENTED, "ADAPTER_NOT_IMPLEMENTED", "当前版本尚未实现模型服务接入，未执行连接检查。"));
        return new Report(Instant.now(), List.copyOf(checks));
    }

    private Check inspect(String id, Supplier<Check> probe) {
        final Future<Check> task;
        try {
            task = executor.submit(probe::get);
        } catch (RejectedExecutionException exception) {
            return new Check(id, Status.UNKNOWN, "CHECK_BUSY", "另一项诊断仍在执行，本项未检查，请稍后重试。");
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            executor.purge();
            return new Check(id, Status.UNKNOWN, "CHECK_TIMEOUT", "依赖未在限定时间内响应，请检查服务状态后重试。");
        } catch (InterruptedException exception) {
            task.cancel(true);
            executor.purge();
            Thread.currentThread().interrupt();
            return new Check(id, Status.UNKNOWN, "CHECK_INTERRUPTED", "本次检查已中断，请重新检查。");
        } catch (ExecutionException exception) {
            return new Check(id, Status.DOWN, "CHECK_FAILED", "依赖检查失败，请查看对应服务日志与部署配置。");
        }
    }

    private static Check up(String id, String message) { return new Check(id, Status.UP, "CHECK_PASSED", message); }

    /** 应用关闭时停止诊断工作线程。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); }

    /**
     * UNKNOWN 表示未取得结果，不等同于依赖已经故障。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { UP, DOWN, UNKNOWN, WARNING, NOT_IMPLEMENTED }

    /**
     * 单项自检结果，稳定标识供前端映射展示。
     * @author owlzhangfq@gmail.com
     */
    public record Check(String id, Status status, String code, String message) { }

    /**
     * 本次检查快照；不将未实现能力折算为整体可用。
     * @author owlzhangfq@gmail.com
     */
    public record Report(Instant checkedAt, List<Check> checks) { }
}
