package io.agentflow.notification;

import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人付款消息的只读入口定位；按当前原业务权限返回，不执行资金操作。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class PaymentNotificationController {
    private final PaymentNotificationAccess access;
    /** 原角色与字段权限统一由付款读取链路校验。 */
    public PaymentNotificationController(PaymentNotificationAccess access) { this.access = access; }

    /** 不接受外部指定付款号、租户或轮次，禁用缓存避免旧权限复用。 */
    @GetMapping("/api/v1/notifications/{id}/payment-target")
    public ResponseEntity<PaymentNotificationAccess.Target> target(@PathVariable UUID id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_INBOX_QUERY", "Payment notification target does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.target(id));
    }
}
