package io.agentflow.procurement;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.observability.DiagnosticContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

/**
 * 采购付款财务对称接口：ERP 凭证草稿、可离线核验的电子归档 ZIP 下载及未付款前的财务撤销。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/procurement-payments")
public class ProcurementArchiveController {
    private final ProcurementArchiveAndVoucherService service;
    private final IdempotencyExecutor idempotency;

    /** 构造采购付款归档与凭证控制器。 */
    public ProcurementArchiveController(ProcurementArchiveAndVoucherService service, IdempotencyExecutor idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /** 获取已批准采购付款的借贷平衡 ERP 凭证草稿。 */
    @GetMapping("/{id}/voucher-draft")
    public ResponseEntity<ProcurementArchiveAndVoucherService.VoucherDraft> voucherDraft(
            @PathVariable UUID id, @RequestParam(required = false) Integer roundNo) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.voucherDraft(id, roundNo));
    }

    /** 下载已批准采购付款的不可变电子归档 ZIP（含 JSON 快照、凭证草稿与 SHA-256 校验清单）。 */
    @GetMapping("/{id}/archive/content")
    public ResponseEntity<StreamingResponseBody> downloadArchive(
            @PathVariable UUID id, @RequestParam(required = false) Integer roundNo) {
        var snapshot = service.prepareArchive(id, roundNo);
        int round = snapshot.view().roundNo();
        var trace = DiagnosticContext.capture();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("procurement-" + id + "-round-" + round + ".zip").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(output -> {
                    try (var scope = trace.open()) {
                        service.writeArchiveZip(snapshot, output);
                    }
                });
    }

    /** 财务角色撤销尚未发起供应商付款授权的已批准采购付款申请。 */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revokeApproved(
            @PathVariable UUID id, @Valid @RequestBody RevokeProcurementRequest request, HttpServletRequest http) {
        return idempotency.execute(http, HttpStatus.OK,
                () -> service.revokeApproved(id, request.applicationVersion(), request.requestVersion(), request.comment()));
    }

    /**
     * 采购付款已批撤销请求；双版本与原因必填。
     * @author owlzhangfq@gmail.com
     */
    public record RevokeProcurementRequest(
            @NotNull @Positive Long applicationVersion,
            @NotNull @Positive Long requestVersion,
            @NotBlank @Size(max = 2000) String comment) { }
}
