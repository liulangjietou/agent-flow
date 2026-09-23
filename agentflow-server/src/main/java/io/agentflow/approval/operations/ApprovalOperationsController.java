package io.agentflow.approval.operations;

import io.agentflow.common.CurrentActor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 只有拥有租户全部申请读取权的管理员可查询运营数据。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/operations")
public class ApprovalOperationsController {
    private final CurrentActor currentActor;
    private final ApprovalOperationsReadPort reader;

    /** 入口承担授权与参数校验，投影适配器只负责读取事实。 */
    public ApprovalOperationsController(CurrentActor currentActor, ApprovalOperationsReadPort reader) {
        this.currentActor = currentActor; this.reader = reader;
    }

    /** 读取有界运营报告，禁止共享缓存保留租户数据。 */
    @GetMapping("/approvals")
    public ResponseEntity<ApprovalOperationsReadPort.Report> approvals(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        actor.requireRole("ADMIN");
        Instant now = Instant.now();
        var query = OperationsQueryParameters.parse(raw, now.atOffset(ZoneOffset.UTC).toLocalDate());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reader.read(actor.tenantId(), query, now));
    }
}
