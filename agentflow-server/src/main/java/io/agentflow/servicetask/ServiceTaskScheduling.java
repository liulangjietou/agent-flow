package io.agentflow.servicetask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 启动后从持久队列接续，不依赖内存回调保存执行进度。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.service-tasks.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ServiceTaskScheduling {
    private final ServiceTaskWorker worker;
    /** 计划任务与可独立验证的执行器分离。 */
    public ServiceTaskScheduling(ServiceTaskWorker worker) { this.worker = worker; }
    /** 每次扫描有界，间隔只影响延迟，不影响原命令身份和租约。 */
    @Scheduled(fixedDelayString = "${agentflow.service-tasks.poll-delay-ms:1000}")
    public void poll() { worker.poll(); }
}
