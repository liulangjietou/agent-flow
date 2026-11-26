package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 流式 CSV 导出服务：按游标分批读取并实时写出 UTF-8 BOM + RFC 4180 CSV（带电子表格公式注入防护），
 * 支持单次最高 100,000 行且堆内存恒定为单批大小。
 * @author owlzhangfq@gmail.com
 */
@Service
public class StreamingCsvExportService {
    static final int PAGE_SIZE = 500;
    static final int MAX_STREAM_ROWS = 100_000;
    private static final String UTF8_BOM = "\uFEFF";

    private final AuditSearchPort auditReader;
    private final ApplicationSearchPort applicationReader;

    /** 构造流式 CSV 导出服务。 */
    public StreamingCsvExportService(AuditSearchPort auditReader, ApplicationSearchPort applicationReader) {
        this.auditReader = auditReader;
        this.applicationReader = applicationReader;
    }

    /** 按游标分批流式写出审计事件摘要 CSV。 */
    public void streamAuditCsv(Actor actor, AuditSearchPort.Query baseQuery, OutputStream output) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(UTF8_BOM);
            writeRow(writer, "eventId", "occurredAt", "source", "action", "actor",
                    "aggregateId", "aggregateVersion", "applicationId", "businessNo", "currentTitle");
            Instant beforeTime = null;
            UUID beforeId = null;
            int written = 0;
            while (written < MAX_STREAM_ROWS) {
                int batchLimit = Math.min(PAGE_SIZE, MAX_STREAM_ROWS - written);
                var pageQuery = new AuditSearchPort.Query(
                        baseQuery.text(), baseQuery.actor(), baseQuery.action(), baseQuery.source(),
                        baseQuery.applicationId(), baseQuery.occurredFrom(), baseQuery.occurredBefore(),
                        batchLimit, beforeTime, beforeId);
                List<AuditSearchPort.Item> rows = auditReader.search(actor.tenantId(), pageQuery);
                int count = Math.min(rows.size(), batchLimit);
                for (int i = 0; i < count; i++) {
                    AuditSearchPort.Item item = rows.get(i);
                    writeRow(writer,
                            item.eventId(),
                            item.occurredAt() == null ? "" : item.occurredAt().toString(),
                            item.source(),
                            item.action(),
                            item.actor(),
                            item.aggregateId(),
                            Long.toString(item.aggregateVersion()),
                            item.applicationId() == null ? "" : item.applicationId().toString(),
                            item.businessNo(),
                            item.currentTitle());
                }
                writer.flush();
                written += count;
                if (rows.size() <= batchLimit || count == 0) break;
                AuditSearchPort.Item last = rows.get(count - 1);
                beforeTime = last.occurredAt();
                beforeId = last.id();
            }
        }
    }

    /** 按游标分批流式写出申请摘要 CSV。 */
    public void streamApplicationsCsv(Actor actor, ApplicationSearchPort.Query baseQuery, OutputStream output) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(UTF8_BOM);
            writeRow(writer, "id", "businessNo", "title", "processKey", "definitionVersion",
                    "createdBy", "status", "roundNo", "createdAt", "updatedAt");
            Instant beforeTime = null;
            UUID beforeId = null;
            int written = 0;
            while (written < MAX_STREAM_ROWS) {
                int batchLimit = Math.min(PAGE_SIZE, MAX_STREAM_ROWS - written);
                var pageQuery = new ApplicationSearchPort.Query(
                        baseQuery.text(), baseQuery.status(), baseQuery.processKey(),
                        baseQuery.definitionVersion(), baseQuery.applicant(),
                        baseQuery.createdFrom(), baseQuery.createdBefore(),
                        batchLimit, beforeTime, beforeId);
                List<ApplicationSearchPort.Item> rows = applicationReader.search(actor, pageQuery);
                int count = Math.min(rows.size(), batchLimit);
                for (int i = 0; i < count; i++) {
                    ApplicationSearchPort.Item item = rows.get(i);
                    writeRow(writer,
                            item.id().toString(),
                            item.businessNo(),
                            item.title(),
                            item.processKey(),
                            Long.toString(item.definitionVersion()),
                            item.createdBy(),
                            item.status(),
                            Integer.toString(item.roundNo()),
                            item.createdAt() == null ? "" : item.createdAt().toString(),
                            item.updatedAt() == null ? "" : item.updatedAt().toString());
                }
                writer.flush();
                written += count;
                if (rows.size() <= batchLimit || count == 0) break;
                ApplicationSearchPort.Item last = rows.get(count - 1);
                beforeTime = last.createdAt();
                beforeId = last.id();
            }
        }
    }

    private static void writeRow(BufferedWriter writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(escapeCsv(values[i]));
        }
        writer.write("\r\n");
    }

    /** RFC 4180 转义并防护 Excel 公式注入（=, +, -, @, TAB, CR）。 */
    static String escapeCsv(String raw) {
        if (raw == null) return "";
        String value = raw;
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                value = "'" + value;
            }
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
