package io.agentflow.approval.copy;

import io.agentflow.attachment.AttachmentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 抄送专用只读接口；每次请求复核当前主体、收件轮次和字段权限。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/copies/{applicationId}/rounds/{roundNo}")
public class CopyController {
    private final CopyReadService service;
    private final AttachmentService attachments;
    /** 展示与附件使用同一个收件授权入口。 */
    public CopyController(CopyReadService service, AttachmentService attachments) { this.service = service; this.attachments = attachments; }

    /** 返回当时提交的快照，不读取后来修改的草稿内容。 */
    @GetMapping
    public ResponseEntity<CopyReadService.Snapshot> get(@PathVariable UUID applicationId, @PathVariable int roundNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(applicationId, roundNo));
    }

    /** 文件名与指纹同样需要该轮字段可读授权。 */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<AttachmentService.Metadata> metadata(@PathVariable UUID applicationId, @PathVariable int roundNo, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(attachments.copyMetadata(applicationId, id, roundNo));
    }

    /** 下载禁止内联执行，始终重新验证冻结引用和存储完整性。 */
    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID applicationId, @PathVariable int roundNo, @PathVariable UUID id) {
        var file = attachments.copyDownload(applicationId, id, roundNo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
