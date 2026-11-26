package io.agentflow.approval.operations;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.SystemRole;
import io.agentflow.observability.DiagnosticContext;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 面向大数据量场景的流式 CSV 导出接口（仅当前租户 ADMIN），与既有 XLSX 导出共用检索校验边界。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class StreamingCsvExportController {
    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private final CurrentActor currentActor;
    private final StreamingCsvExportService exporter;
    private final JsonUtil json;

    /** 构造流式 CSV 导出控制器。 */
    public StreamingCsvExportController(CurrentActor currentActor, StreamingCsvExportService exporter, JsonUtil json) {
        this.currentActor = currentActor;
        this.exporter = exporter;
        this.json = json;
    }

    /** 流式导出审计摘要 CSV（单次最高 100,000 行，恒定内存占用）。 */
    @GetMapping("/api/v1/operations/audit/export.csv")
    public ResponseEntity<StreamingResponseBody> exportAuditCsv(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        actor.requireRole(SystemRole.ADMIN);
        if (raw.containsKey("cursor") || raw.containsKey("limit")) {
            throw new DomainException(ErrorCode.INVALID_AUDIT_QUERY, "Export does not accept pagination parameters");
        }
        var parameters = AuditSearchParameters.parse(actor, raw, json);
        var query = parameters.query();
        var trace = DiagnosticContext.capture();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(TEXT_CSV)
                .header("Content-Disposition", "attachment; filename=\"agentflow-audit.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(output -> {
                    try (var scope = trace.open()) {
                        exporter.streamAuditCsv(actor, query, output);
                    }
                });
    }

    /** 流式导出申请摘要 CSV（单次最高 100,000 行，恒定内存占用）。 */
    @GetMapping("/api/v1/operations/applications/export.csv")
    public ResponseEntity<StreamingResponseBody> exportApplicationsCsv(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        actor.requireRole(SystemRole.ADMIN);
        if (raw.containsKey("cursor") || raw.containsKey("limit")) {
            throw new DomainException(ErrorCode.INVALID_APPLICATION_QUERY, "Export does not accept pagination parameters");
        }
        var parameters = ApplicationSearchParameters.parse(actor, raw, json);
        var query = parameters.query();
        var trace = DiagnosticContext.capture();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(TEXT_CSV)
                .header("Content-Disposition", "attachment; filename=\"agentflow-applications.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(output -> {
                    try (var scope = trace.open()) {
                        exporter.streamApplicationsCsv(actor, query, output);
                    }
                });
    }
}
