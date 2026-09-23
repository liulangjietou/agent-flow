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
                              @Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled) {
        this(diagnostics, catalog, demoEnabled, PROBE_TIMEOUT);
    }

    SystemCheckService(SystemDiagnostics diagnostics, ClasspathProcessTemplateCatalog catalog,
                       boolean demoEnabled, Duration timeout) {
        this.diagnostics = diagnostics;
        this.catalog = catalog;
        this.demoEnabled = demoEnabled;
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
        checks.add(new Check("authentication", Status.WARNING, demoEnabled ? "DEMO_AUTH_ONLY" : "AUTH_PROVIDER_NOT_CONFIGURED",
                demoEnabled ? "使用演示账号，企业身份认证尚未接入。" : "演示登录已关闭，企业身份认证尚未接入。"));
        for (String id : List.of("objectStorage", "notifications", "organization", "model")) {
            checks.add(new Check(id, Status.NOT_IMPLEMENTED, "ADAPTER_NOT_IMPLEMENTED", "当前版本尚未实现此服务接入，未执行连接检查。"));
        }
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
