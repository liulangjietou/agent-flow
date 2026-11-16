package io.agentflow.config;

import io.agentflow.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 全部后台轮询的唯一调度入口：线程数等于已登记的定时任务数，每个任务独占一个线程。
 * 固定延迟任务不会自我重入，因此慢模型、慢银行或慢 Webhook 只占用自己的线程，不会推迟审批期限、付款回调等其他任务；
 * 各模块不再各自声明单线程调度器。跨实例互斥仍由数据库租约保证。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableScheduling
public class PollingSchedulingConfiguration implements SchedulingConfigurer, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(PollingSchedulingConfiguration.class);
    private static final String THREAD_PREFIX = "agentflow-poller-";
    private ThreadPoolTaskScheduler scheduler;

    /** Spring 在全部 @Scheduled 方法登记完成、尚未开始调度时回调，此时任务数已确定。 */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        int tasks = registrar.getFixedDelayTaskList().size() + registrar.getFixedRateTaskList().size()
                + registrar.getCronTaskList().size() + registrar.getTriggerTaskList().size();
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, tasks));
        scheduler.setThreadNamePrefix(THREAD_PREFIX);
        // 持久任务依靠数据库租约恢复，关闭时不等待网络调用结束。
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        // 与各轮询器一致只记录异常类型：业务异常消息可能带有单据或人员信息。
        scheduler.setErrorHandler(failure -> LOG.error("Scheduled poll failed, errorCode={}, thread={}, exceptionType={}",
                ErrorCode.INTERNAL_ERROR, Thread.currentThread().getName(), failure.getClass().getSimpleName()));
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
        LOG.info("Polling scheduler initialized, errorCode={}, scheduledTasks={}", "NONE", tasks);
    }

    /** 调度器由本配置创建，必须由本配置关闭。 */
    @Override
    public void destroy() {
        if (scheduler != null) scheduler.shutdown();
    }
}
