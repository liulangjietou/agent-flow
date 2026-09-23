package io.agentflow.integration;

/**
 * 同步等待单次外部确认的传输边界；调用方必须位于数据库事务之外。
 * @author owlzhangfq@gmail.com
 */
public interface WebhookTransport {
    /** 发送不可变请求体，返回稳定结果；每次重试使用同一事件标识。 */
    DeliveryProgress.Outcome send(WebhookTargets.Destination target, String eventId, String body);
}
