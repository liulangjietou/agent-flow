package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.Map;
import java.util.UUID;

/**
 * 授权后的状态与完整包下载，没有手工覆盖清单或强制归档入口。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpenseArchiveController {
    private final ExpenseArchiveWorkspace workspace;
    private final ExpenseArchiveFiles files;
    /** 原文件及清单共用一个权限边界，每次下载重新核验字节指纹。 */
    public ExpenseArchiveController(ExpenseArchiveWorkspace workspace, ExpenseArchiveFiles files) { this.workspace = workspace; this.files = files; }
    /** 当轮财务完整读取权限也适用于归档状态。 */
    @GetMapping("/api/v1/expense-reports/{id}/archive")
    public ResponseEntity<ExpenseArchiveWorkspace.View> get(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workspace.get(id, parameters));
    }
    /** 返回标准 ZIP，保存的 JSON 清单及原始电子文件可离线逐项核验。 */
    @GetMapping("/api/v1/expense-reports/{id}/archive/content")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        var entry = workspace.download(id, parameters); int round = entry.archive().manifest().source().roundNo();
        var trace = DiagnosticContext.capture();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("expense-" + id + "-round-" + round + ".zip").build().toString())
                .header("X-Content-Type-Options", "nosniff").body(output -> {
                    // 流式响应在另一线程执行，仅携带诊断上下文，不复制认证主体。
                    try (var scope = trace.open()) { files.write(entry, output); }
                });
    }
}
