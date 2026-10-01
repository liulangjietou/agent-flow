package io.agentflow.event;

/**
 * 验签后的不可变事实；不保留密钥、签名或未解析的外部正文。
 * @author owlzhangfq@gmail.com
 */
public record ReceivedEvent(String eventId, String payloadDigest, long trustRevision, EventSignal signal) {
    /** 信任修订由配置提供，投递时间戳不属于消息去重身份。 */
    public ReceivedEvent {
        if (eventId == null || !eventId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") || payloadDigest == null
                || !payloadDigest.matches("[a-f0-9]{64}") || trustRevision < 1 || signal == null) {
            throw new IllegalArgumentException("Invalid verified event identity");
        }
    }
}
