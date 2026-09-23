package io.agentflow.approval.operations;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用实际 SQL 和解包工作簿验证导出权限、完整筛选及文本安全，不把响应 200 当作文件有效。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:application-export;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class ApplicationExportIntegrationTest {
    private static final String PATH = "/api/v1/operations/applications/export";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;

    @Test
    void requiresAdminAndKeepsTenantIsolationWithoutPayload() throws Exception {
        String key = "scope-" + UUID.randomUUID();
        seed("demo", key, "alice", "DRAFT", "本租户", "001234567890123456789", "2020-01-01T00:00:00Z");
        seed("other", key, "alice", "DRAFT", "其他租户", "other", "2020-01-01T00:00:00Z");
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String user : List.of("alice", "finance", "manager")) mvc.perform(get(PATH).header("Authorization", token(user))).andExpect(status().isForbidden());
        doReturn(new Actor("demo", "designer", Set.of("PROCESS_ADMIN"))).when(auth).authenticate("process-only");
        mvc.perform(get(PATH).header("Authorization", "Bearer process-only")).andExpect(status().isForbidden());
        try (var book = read(Map.of("processKey", key))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("001234567890123456789");
            assertThat(book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("本租户");
            assertThat(book.getSheetAt(0).getRow(1).getLastCellNum()).isEqualTo((short) 10);
            assertThat(book.getSheetAt(1).getRow(0).getCell(1).getStringCellValue()).isEqualTo("demo");
            assertThat(book.getAllNames()).extracting(name -> name.getNameName()).containsExactly("_xlnm._FilterDatabase"); assertThat(book.getExternalLinksTable()).isEmpty();
        }
        doReturn(new Actor("other", "admin", Set.of("ADMIN"))).when(auth).authenticate("other-admin");
        byte[] other = mvc.perform(get(PATH).param("processKey", key).header("Authorization", "Bearer other-admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(other))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("其他租户");
        }
    }

    @Test
    void exportsEveryMatchingRowInOneQueryWithLiteralTextAndNoBusinessMutation() throws Exception {
        String key = "rows-" + UUID.randomUUID();
        var titles = List.of("=1+1", "+SUM(1,2)", "-1", "@test", "＝全角", "含,逗号\"引号\n换行", "_x000D_ 原文", "_x005F_x000D_", "_x000D_x000A_", "控制\u0001字符", "中文😀", "正常标题");
        for (int index = 0; index < 35; index++) seed("demo", key, "alice", "RETURNED", titles.get(index % titles.size()), "EXPORT-%_!-" + index, "2020-01-02T00:00:00Z");
        seed("demo", key, "alice", "RETURNED", "结束日末尾", "EXPORT-%_!-last", "2020-01-02T23:59:59.999Z");
        seed("demo", key, "alice", "RETURNED", "日期之外", "EXPORT-%_!-out", "2020-01-03T00:00:00Z");
        seed("demo", key, "bob", "RETURNED", "其他申请人", "EXPORT-%_!-bob", "2020-01-02T00:00:00Z");
        var before = jdbc.queryForList("SELECT * FROM approval_application WHERE process_key=? ORDER BY id", key);
        var expected = jdbc.queryForList("SELECT id,title FROM approval_application WHERE process_key=? AND created_by='alice' AND created_at>=? AND created_at<? ORDER BY created_at DESC,id DESC",
                key, Timestamp.from(Instant.parse("2020-01-02T00:00:00Z")), Timestamp.from(Instant.parse("2020-01-03T00:00:00Z")));
        try (var book = read(Map.of("processKey", key, "definitionVersion", "1", "applicant", "alice", "status", "RETURNED", "q", "%_!", "from", "2020-01-02", "to", "2020-01-02"))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(36);
            for (int index = 0; index < expected.size(); index++) {
                var row = sheet.getRow(index + 1);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo(expected.get(index).get("ID"));
                assertThat(row.getCell(2).getStringCellValue()).isEqualTo(expected.get(index).get("TITLE"));
                for (var cell : row) { assertThat(cell.getCellType()).isEqualTo(CellType.STRING); assertThat(cell.getHyperlink()).isNull(); }
            }
            assertThat(book.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("36");
            assertThat(book.getSheetAt(1).getRow(4).getCell(1).getStringCellValue()).isEqualTo("%_!");
        }
        assertThat(jdbc.queryForList("SELECT * FROM approval_application WHERE process_key=? ORDER BY id", key)).isEqualTo(before);
        try (var empty = read(Map.of("processKey", key, "applicant", "alice' OR '1'='1"))) {
            assertThat(empty.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(empty.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("0");
        }
    }

    @Test
    void rejectsPaginationTenantOverridesAndInvalidFiltersBeforeGeneratingAFile() throws Exception {
        for (var filters : List.of(Map.of("limit", "30"), Map.of("cursor", ""), Map.of("tenantId", "other"), Map.of("q", "x".repeat(101)),
                Map.of("definitionVersion", "1"), Map.of("status", "INVALID"), Map.of("from", "2020-02-30"), Map.of("from", "2020-01-02", "to", "2020-01-01"))) {
            var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_APPLICATION_QUERY"))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }
    }

    private XSSFWorkbook read(Map<String, String> filters) throws Exception {
        var request = get(PATH).header("Authorization", token("admin")); filters.forEach(request::param);
        byte[] bytes = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"agentflow-applications.xlsx\""))
                .andExpect(content().contentType(ApplicationWorkbook.MEDIA_TYPE)).andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
    private void seed(String tenant, String key, String applicant, String state, String title, String businessNo, String time) {
        jdbc.update("INSERT INTO approval_application (id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,created_at,updated_at) VALUES (?,?,?,?,1,?,?,'secret-body',?,1,1,?,?)",
                UUID.randomUUID().toString(), tenant, businessNo, key, applicant, title, state, Timestamp.from(Instant.parse(time)), Timestamp.from(Instant.parse(time)));
    }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
