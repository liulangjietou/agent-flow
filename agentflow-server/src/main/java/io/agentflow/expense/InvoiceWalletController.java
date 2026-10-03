package io.agentflow.expense;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 个人票夹入口；请求不能填写已查验、已核销、归属人或文件路径。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceWalletController {
    private final InvoiceWalletService wallet;
    private final IdempotencyExecutor idempotency;
    /** JSON 登记与二进制上传分别采用请求幂等和不可变内容幂等。 */
    public InvoiceWalletController(InvoiceWalletService wallet, IdempotencyExecutor idempotency) { this.wallet = wallet; this.idempotency = idempotency; }

    /** 返回原件存储的真实可用性。 */
    @GetMapping("/options")
    public ResponseEntity<InvoiceWalletService.Options> options() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallet.options()); }

    /** 登记只能创建待上传、待查验原件。 */
    @PostMapping
    public ResponseEntity<String> reserve(@Valid @RequestBody InvoiceWalletService.UploadInput input, HttpServletRequest request) {
        var result = idempotency.execute(request, HttpStatus.CREATED, () -> wallet.reserve(input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    /** 原身份支持恢复上传，已经发布的内容不能覆盖。 */
    @PutMapping(value = "/{id}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InvoiceWalletService.OriginalMetadata> upload(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallet.upload(id, request.getInputStream()));
    }

    /** 元数据和票面同样仅属于本人。 */
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceWalletService.Item> get(@PathVariable UUID id) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallet.get(id)); }

    /** 当前员工的有界列表，不支持他人身份筛选。 */
    @GetMapping
    public ResponseEntity<InvoiceWalletService.Page> list(@RequestParam Map<String, String> parameters) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallet.list(parameters)); }

    /** 原件只以附件下载，禁止内联执行和内容嗅探。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var value = wallet.download(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(value.filename(), StandardCharsets.UTF_8).build().toString()).body(value.content());
    }
}
