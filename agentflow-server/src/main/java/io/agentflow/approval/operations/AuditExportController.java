package io.agentflow.approval.operations;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * 当前租户管理员的审计摘要导出入口，复用检索条件并拒绝分页和租户覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class AuditExportController {
    private final CurrentActor currentActor;
    private final AuditExportService exporter;
    private final JsonUtil json;

    /** 注入主体和导出用例，不向浏览器暴露数据库或内部文件路径。 */
    public AuditExportController(CurrentActor currentActor, AuditExportService exporter, JsonUtil json) {
        this.currentActor = currentActor; this.exporter = exporter; this.json = json;
    }

    /** 下载完整匹配结果；生成完成前不提交响应，错误仍返回标准 JSON。 */
    @GetMapping("/api/v1/operations/audit/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        if (raw.containsKey("cursor") || raw.containsKey("limit")) throw new DomainException("INVALID_AUDIT_QUERY", "Export does not accept pagination parameters");
        var parameters = AuditSearchParameters.parse(actor, raw, json);
        byte[] workbook = exporter.export(actor, parameters.query());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(TextWorkbook.MEDIA_TYPE))
                .header("Content-Disposition", "attachment; filename=\"agentflow-audit.xlsx\"")
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .contentLength(workbook.length).body(workbook);
    }
}
