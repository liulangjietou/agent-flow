package io.agentflow.finance;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

/**
 * 独立冲销查询与人工登记分离，幂等回放仍复核当前身份和原轮次字段权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/vouchers/{operationId}/reversal")
public class VoucherReversalController {
    private final VoucherReversalWorkspace workspace;
    private final VoucherReversalService service;
    private final IdempotencyExecutor idempotency;
    /** 外部 ERP 查询由事务外后台处理，HTTP 请求只保存本地意图。 */
    public VoucherReversalController(VoucherReversalWorkspace workspace, VoucherReversalService service, IdempotencyExecutor idempotency) { this.workspace = workspace; this.service = service; this.idempotency = idempotency; }
    /** 查询状态与登记事实都禁止被共享缓存保存。 */
    @GetMapping
    public ResponseEntity<VoucherReversalWorkspace.View> read(@PathVariable UUID id, @PathVariable UUID operationId, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.read(id, operationId, parameters));
    }
    /** 202 只表示只读查询已排队，不表示冲销已核验或登记。 */
    @PostMapping("/checks")
    public ResponseEntity<String> query(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalService.QueryInput input, HttpServletRequest request) {
        service.authorize(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.queue(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
    /** 登记只引用已保存的证据，不接受手工会计分录或金额。 */
    @PostMapping("/records")
    public ResponseEntity<String> record(@PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody VoucherReversalService.RecordInput input, HttpServletRequest request) {
        service.authorize(id, operationId, input.roundNo());
        var result = idempotency.execute(request, HttpStatus.ACCEPTED, () -> service.record(id, operationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }
}
