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
 * 本人凭证消息只读入口；原财务权限每次重新核验，不发起会计或资金操作。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class VoucherNotificationController {
    private final VoucherNotificationAccess access;
    /** 通知只提供原身份，详情权限复用凭证业务入口。 */
    public VoucherNotificationController(VoucherNotificationAccess access) { this.access = access; }

    /** 禁止通过查询参数替换凭证号或轮次，禁止缓存财务响应。 */
    @GetMapping("/api/v1/notifications/{id}/voucher-target")
    public ResponseEntity<VoucherNotificationAccess.Target> target(@PathVariable UUID id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_INBOX_QUERY", "Voucher notification target does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.target(id));
    }
}
