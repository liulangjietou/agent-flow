package io.agentflow.approval.operations;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 通过真实查询与工作簿回读验证租户、完整筛选、旧记录与原文，不把 HTTP 成功当作文件验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_AUDIT_EXPORT_TEST_URL:jdbc:h2:mem:audit-export;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_AUDIT_EXPORT_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_AUDIT_EXPORT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_AUDIT_EXPORT_TEST_DRIVER:org.h2.Driver}",
        "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class AuditExportIntegrationTest {
    private static final String PATH = "/api/v1/operations/audit/export";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;
    @MockitoSpyBean AuditExportService exporter;

    @Test
    void requiresAdminAndNeverLinksAnotherTenantsApplication() throws Exception {
        String actor = UUID.randomUUID().toString();
        String app = application("demo", "本租户", "0001234567890123456789");
        String other = application("other", "其他租户", "OTHER");
        event("demo", app, "Application", app, "CREATE", actor, Instant.EPOCH);
        event("other", other, "Application", other, "CREATE", actor, Instant.EPOCH);
        event("demo", other, "Task", "old-task", "APPROVE", actor, Instant.EPOCH.plusSeconds(1));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String user : List.of("alice", "finance", "manager")) mvc.perform(get(PATH).header("Authorization", token(user))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("export-designer");
        mvc.perform(get(PATH).header("Authorization", "Bearer export-designer")).andExpect(status().isForbidden());
        try (var book = read(Map.of("actor", actor))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("0001234567890123456789");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("本租户");
            assertThat(book.getSheetAt(1).getRow(0).getCell(1).getStringCellValue()).isEqualTo("demo");
        }
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("export-other");
        byte[] bytes = mvc.perform(get(PATH).param("actor", actor).header("Authorization", "Bearer export-other"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(1).getCell(10).getStringCellValue()).isEqualTo("其他租户");
        }
    }

    @Test
    void keepsLegacyMissingMetadataAndDoesNotInventAssociations() throws Exception {
        String title = "旧记录-" + UUID.randomUUID();
        String app = application("demo", title, "LEGACY");
        event("demo", null, "Application", app, null, null, Instant.EPOCH);
        String taskId = event("demo", null, "Task", "task-" + UUID.randomUUID(), null, null, Instant.EPOCH);
        try (var book = read(Map.of("applicationId", app))) {
            var row = book.getSheetAt(0).getRow(1);
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(row.getCell(5).getStringCellValue()).isEmpty();
            assertThat(row.getCell(6).getStringCellValue()).isEmpty();
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo(app);
        }
        try (var book = read(Map.of("source", "Task"))) {
            var row = java.util.stream.StreamSupport.stream(book.getSheetAt(0).spliterator(), false)
                    .filter(candidate -> candidate.getCell(0).getStringCellValue().equals(taskId)).findFirst().orElseThrow();
            assertThat(row.getCell(8).getStringCellValue()).isEmpty();
            assertThat(row.getCell(10).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void exportsAllFilteredEventsInStableOrderWithLiteralTextAndNoMutation() throws Exception {
        String actor = UUID.randomUUID().toString();
        String title = "=1+1 中文😀 _x000D_\n控制\u0001字符";
        String app = application("demo", title, "AUDIT-%_!-0001234567890123456789");
        Instant start = Instant.parse("2020-01-02T00:00:00Z");
        for (int index = 0; index < 35; index++) event("demo", app, "Task", "=SUM(1,2)", "RETURN", actor, start);
        String last = event("demo", app, "Task", "_x005F_x000D_", "RETURN", actor, Instant.parse("2020-01-02T23:59:59.999999Z"));
        event("demo", app, "Task", "task", "RETURN", actor, Instant.parse("2020-01-03T00:00:00Z"));
        event("demo", app, "Task", "task", "APPROVE", actor, start);
        event("demo", app, "Application", app, "RETURN", actor, start);
        event("demo", app, "Task", "task", "RETURN", "other-actor", start);
        var before = jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        var expected = jdbc.query("SELECT id FROM audit_event WHERE actor_id=? AND aggregate_type='Task' AND action='RETURN' AND occurred_at>=? AND occurred_at<? ORDER BY occurred_at DESC,id DESC",
                (row, index) -> row.getString(1), actor, Timestamp.from(start), Timestamp.from(Instant.parse("2020-01-03T00:00:00Z")));
        try (var book = read(Map.of("actor", actor, "action", "RETURN", "source", "Task", "applicationId", app, "q", "%_!", "from", "2020-01-02", "to", "2020-01-02"))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(36);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(last);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("_x005F_x000D_");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("9007199254740993");
            for (int index = 0; index < expected.size(); index++) {
                var row = sheet.getRow(index + 1);
                assertThat(row.getLastCellNum()).isEqualTo((short) 11);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo(expected.get(index));
                assertThat(row.getCell(10).getStringCellValue()).isEqualTo(title);
                for (var cell : row) { assertThat(cell.getCellType()).isEqualTo(CellType.STRING); assertThat(cell.getHyperlink()).isNull(); }
            }
            assertThat(book.getExternalLinksTable()).isEmpty();
            assertThat(book.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("36");
            assertThat(book.getSheetAt(1).getRow(4).getCell(1).getStringCellValue()).isEqualTo("%_!");
            assertThat(book.getSheetAt(1).getRow(14).getCell(1).getStringCellValue()).contains("不代表整单");
        }
        assertThat(jdbc.queryForList("SELECT * FROM audit_event ORDER BY id")).isEqualTo(before);
        try (var empty = read(Map.of("actor", "alice' OR '1'='1"))) {
            assertThat(empty.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(empty.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("0");
        }
    }

    @Test
    void rejectsPaginationOverridesAndInvalidFiltersBeforeProducingAFile() throws Exception {
        for (var filters : List.of(Map.of("limit", "30"), Map.of("cursor", ""), Map.of("tenantId", "other"), Map.of("applicationId", "bad"),
                Map.of("action", "PAY"), Map.of("source", "SQL"), Map.of("q", "x".repeat(101)), Map.of("from", "2020-02-30"), Map.of("from", "2020-01-02", "to", "2020-01-01"))) {
            var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_AUDIT_QUERY"))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }
    }

    @Test
    void generationFailuresKeepJsonStatusAndNeverAdvertiseAPartialDownload() throws Exception {
        var failures = Map.of("AUDIT_EXPORT_BUSY", 429, "AUDIT_EXPORT_LIMIT_EXCEEDED", 422, "AUDIT_EXPORT_FAILED", 503);
        for (var failure : failures.entrySet()) {
            doThrow(new DomainException(failure.getKey(), "Export unavailable")).when(exporter).export(any(), any());
            mvc.perform(get(PATH).header("Authorization", token("admin")))
                    .andExpect(status().is(failure.getValue())).andExpect(jsonPath("code").value(failure.getKey()))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }
    }

    private XSSFWorkbook read(Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
        byte[] bytes = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"agentflow-audit.xlsx\""))
                .andExpect(content().contentType(TextWorkbook.MEDIA_TYPE)).andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
    private String application(String tenant, String title, String businessNo) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version) VALUES (?,?,?,'audit-export',1,'alice',?,'secret-form-body','DRAFT',1,1)", id, tenant, businessNo, title);
        return id;
    }
    private String event(String tenant, String app, String source, String aggregate, String action, String actor, Instant time) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_event (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,payload_json,application_id,action,actor_id,occurred_at) VALUES (?,?,?,?,?,9007199254740993,'secret-audit-body',?,?,?,?)",
                id, tenant, UUID.randomUUID().toString(), source, aggregate, app, action, actor, Timestamp.from(time));
        return id;
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
