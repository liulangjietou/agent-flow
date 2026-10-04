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
 * 本人原供应商应付调整的只读入口，固定原编号并重新核验当前权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class SupplierAdjustmentNotificationController {
    private final SupplierAdjustmentNotificationAccess access;
    /** 归属、修订与字段权限统一由访问服务判断。 */
    public SupplierAdjustmentNotificationController(SupplierAdjustmentNotificationAccess access) { this.access = access; }
    /** 读取不重试调整、不完成占用，也不改变消息已读状态。 */
    @GetMapping("/api/v1/notifications/{id}/supplier-adjustment-target")
    public ResponseEntity<SupplierAdjustmentNotificationAccess.Target> target(@PathVariable UUID id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DomainException("INVALID_INBOX_QUERY", "Supplier adjustment notification target does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.target(id));
    }
}
