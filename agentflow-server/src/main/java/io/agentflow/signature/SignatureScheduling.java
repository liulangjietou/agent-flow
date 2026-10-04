package io.agentflow.signature;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 重启从数据库接续，工作器启停不改变历史授权、证据和原操作号。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.signatures.worker-enabled", havingValue = "true", matchIfMissing = true)
public class SignatureScheduling {
    private final SignatureWorker worker;
    /** 定时器与可独立验证的执行器分开。 */
    public SignatureScheduling(SignatureWorker worker) { this.worker = worker; }
    /** 扫描频率只影响处理延迟，不影响状态和副作用次数。 */
    @Scheduled(fixedDelayString = "${agentflow.signatures.poll-delay-ms:1000}")
    public void poll() { worker.poll(); }
}
