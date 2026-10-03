package io.agentflow.attachment;

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
import java.util.UUID;

/**
 * 附件登记、可恢复内容传输和实时鉴权下载入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {
    private final AttachmentService service;
    private final IdempotencyExecutor idempotency;
    /** 复用申请授权与 JSON 幂等执行器。 */
    public AttachmentController(AttachmentService service, IdempotencyExecutor idempotency) { this.service = service; this.idempotency = idempotency; }

    /** 返回当前登录用户填写附件时需要的实际部署限制。 */
    @GetMapping("/attachments/options")
    public ResponseEntity<AttachmentService.Options> options() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.options()); }

    /** 登记幂等上传身份，正文中的路径与租户没有控制存储的权限。 */
    @PostMapping("/applications/{applicationId}/attachments")
    public ResponseEntity<String> reserve(@PathVariable UUID applicationId, @Valid @RequestBody AttachmentService.UploadInput input, HttpServletRequest request) {
        var result = idempotency.execute(request, HttpStatus.CREATED, () -> service.reserve(applicationId, input));
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders()).cacheControl(CacheControl.noStore()).body(result.getBody());
    }

    /** 上传由固定附件标识与登记摘要保持幂等，不使用 JSON 请求缓存处理二进制正文。 */
    @PutMapping(value = "/applications/{applicationId}/attachments/{id}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<AttachmentService.Metadata> upload(@PathVariable UUID applicationId, @PathVariable UUID id,
            @RequestHeader("X-Application-Version") long version, HttpServletRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.upload(applicationId, id, version, request.getInputStream()));
    }

    /** 元数据同样受字段权限约束，不能通过文件名或长度绕过脱敏。 */
    @GetMapping("/applications/{applicationId}/attachments/{id}")
    public ResponseEntity<AttachmentService.Metadata> metadata(@PathVariable UUID applicationId, @PathVariable UUID id,
            @RequestParam(required = false) Integer roundNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.metadata(applicationId, id, roundNo));
    }

    /** 只提供下载，强制二进制和禁止 MIME 嗅探，不以内联页面执行上传内容。 */
    @GetMapping("/applications/{applicationId}/attachments/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID applicationId, @PathVariable UUID id,
            @RequestParam(required = false) Integer roundNo) {
        var file = service.download(applicationId, id, roundNo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
