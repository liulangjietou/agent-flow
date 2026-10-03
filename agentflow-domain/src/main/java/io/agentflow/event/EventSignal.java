package io.agentflow.event;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 外部事件只能声明精确等待身份，不包含表单、流程变量或审批结果。
 * @author owlzhangfq@gmail.com
 */
public record EventSignal(int envelopeVersion, String tenantId, String sourceKey, String eventType,
                          UUID applicationId, int roundNo, String waitId, String contractKey, long contractVersion) {
    /** 验签后在入口一次性建立有界、明确的信封。 */
    public EventSignal {
        if (envelopeVersion != EventContract.ENVELOPE_VERSION || tenantId == null || !tenantId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
                || sourceKey == null || !sourceKey.matches("[a-z][a-z0-9._-]{0,63}") || eventType == null || !eventType.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")
                || applicationId == null || roundNo < 1 || waitId == null || !waitId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || contractKey == null || !contractKey.matches("[a-z][a-z0-9._-]{0,63}") || contractVersion < 1) {
            throw new DomainException("EVENT_INPUT_INVALID", "Event envelope is invalid");
        }
    }
}
