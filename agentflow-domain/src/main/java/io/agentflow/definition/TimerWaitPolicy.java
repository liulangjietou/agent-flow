package io.agentflow.definition;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.util.Map;

/**
 * 相对激活时刻的单次等待；定义不能提供 cron、循环或引擎表达式。
 * @author owlzhangfq@gmail.com
 */
public record TimerWaitPolicy(int delaySeconds) {
    public static final String PROPERTY = "timerDelaySeconds";
    public static final int MAX_DELAY_SECONDS = 365 * 24 * 60 * 60;

    /** 有界整秒时长保证持久到期时间可解释，不由页面或后台补默认值。 */
    public TimerWaitPolicy {
        if (delaySeconds < 1 || delaySeconds > MAX_DELAY_SECONDS) throw invalid();
    }

    /** 发布入口只接受明确、规范的十进制秒数。 */
    public static TimerWaitPolicy fromProperties(Map<String, String> properties) {
        String raw = properties.get(PROPERTY);
        if (raw == null) throw new DomainException("TIMER_DELAY_REQUIRED", "An explicit timer wait duration is required");
        if (!raw.matches("[1-9][0-9]{0,7}")) throw invalid();
        return new TimerWaitPolicy(Integer.parseInt(raw));
    }

    /** BPMN 只接收领域值生成的常量持续时间。 */
    public Duration duration() { return Duration.ofSeconds(delaySeconds); }

    private static DomainException invalid() {
        return new DomainException("TIMER_DELAY_INVALID", "Timer wait duration must be a whole number of seconds between 1 and 31536000");
    }
}
