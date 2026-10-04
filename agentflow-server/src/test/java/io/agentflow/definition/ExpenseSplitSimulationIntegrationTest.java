package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实模拟入口必须明确接收合成金额，不能查询业务或把合计覆盖到财务判断。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:expense-split-simulation;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class ExpenseSplitSimulationIntegrationTest {
    private static final String PREVIEW = "/api/v1/process-definitions/simulate";
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired DefinitionApplicationService definitions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void enabledRuleRejectsMissingSyntheticAmountInsteadOfPretendingToQueryBusiness() throws Exception {
        var before = snapshot();
        send(PREVIEW, body(graph("ENABLED", true), "3000.00", null), "admin")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("EXPENSE_SPLIT_SIMULATION_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void onlyMarkedBusinessGatewaysUseTheExplicitAmountAndFinancialRecheckKeepsOwnAmount() throws Exception {
        var before = snapshot();
        var request = body(graph("ENABLED", true), "3000.00", money("60000.01", "CNY"));
        String original = json.write(request);
        send(PREVIEW, request, "admin").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("path", org.hamcrest.Matchers.hasItems("department", "executive", "finance")))
                .andExpect(jsonPath("path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("recheck"))));
        send(PREVIEW, body(graph("ENABLED", true), "12000.00", money("60000.01", "CNY")), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.hasItems("executive", "recheck")));
        assertThat(json.write(request)).isEqualTo(original);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void unmarkedBusinessGatewayUsesOwnAmountAndThresholdEqualityRemainsExact() throws Exception {
        send(PREVIEW, body(graph("ENABLED", false), "3000.00", money("60000.01", "CNY")), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.hasItem("department")))
                .andExpect(jsonPath("path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("executive"))));
        send(PREVIEW, body(graph("ENABLED", true), "3000.00", money("5000.00", "CNY")), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("department"))));
        send(PREVIEW, body(graph("ENABLED", true), "3000.00", money("5000.01", "CNY")), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.hasItem("department")));
    }

    @Test
    void syntheticAmountCannotReduceOwnAmountOrCrossTheRuleOrFormCurrency() throws Exception {
        for (var amount : List.of(money("2999.99", "CNY"), money("6000.00", "USD"))) {
            send(PREVIEW, body(graph("ENABLED", true), "3000.00", amount), "admin")
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("EXPENSE_SPLIT_SIMULATION_INVALID"));
        }
        var request = body(graph("ENABLED", true), "3000.00", money("6000.00", "CNY"));
        var values = new LinkedHashMap<>(values("3000.00")); values.put("currency", "USD"); request.put("values", values);
        send(PREVIEW, request, "admin").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("EXPENSE_SPLIT_SIMULATION_INVALID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6000", "\"1e3\"", "\"1.001\"", "\"01.00\"", "\"-1\"", "\"NaN\"", "\"1000000000000000\"", "true", "null"})
    void transportRejectsNonCanonicalMoneyWithoutImplicitCoercion(String value) throws Exception {
        var request = body(graph("ENABLED", true), "3000.00", money("6000.00", "CNY"));
        String raw = json.write(request).replace("\"value\":\"6000.00\"", "\"value\":" + value);
        sendRaw(PREVIEW, raw, "admin").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_MONEY"));
    }

    @Test
    void disabledAndHistoricalDefinitionsKeepOwnRoutesAndRejectUnusedSyntheticInputs() throws Exception {
        for (String mode : List.of("DISABLED", "")) {
            send(PREVIEW, body(graph(mode, true), "3000.00", null), "admin")
                    .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("department"))));
            send(PREVIEW, body(graph(mode, true), "3000.00", money("6000.00", "CNY")), "admin")
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("EXPENSE_SPLIT_SIMULATION_UNEXPECTED"));
        }
    }

    @Test
    void savedDefinitionUsesTheSameExplicitInputAndRemainsReadOnly() throws Exception {
        var graph = graph("ENABLED", true);
        var draft = definitions.create("demo", "split-preview-" + UUID.randomUUID(), "合成拆单模拟", graph, catalog.get("expense-report").formSchema());
        String path = "/api/v1/process-definitions/" + draft.id() + "/simulate";
        var before = snapshot();
        send(path, Map.of("values", values("3000.00"), "splitRoutingAmount", money("60000.01", "CNY")), "admin")
                .andExpect(status().isOk()).andExpect(jsonPath("path", org.hamcrest.Matchers.hasItem("executive")))
                .andExpect(jsonPath("path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("recheck"))));
        send(path, Map.of("values", values("3000.00")), "admin").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("EXPENSE_SPLIT_SIMULATION_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void syntheticInputDoesNotExpandDesignerPermissionsOrPermitOverridingAFormField() throws Exception {
        var request = body(graph("ENABLED", true), "3000.00", money("6000.00", "CNY"));
        var before = snapshot();
        send(PREVIEW, request, null).andExpect(status().isUnauthorized());
        send(PREVIEW, request, "employee").andExpect(status().isForbidden());
        var values = new LinkedHashMap<>(values("3000.00")); values.put("splitRoutingAmount", money("6000.00", "CNY"));
        request.put("values", values);
        send(PREVIEW, request, "admin").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("details.fieldErrors.splitRoutingAmount").value("UNKNOWN_FIELD"));
        assertThat(snapshot()).isEqualTo(before);
    }

    private Graph graph(String mode, boolean markExecutive) {
        var base = catalog.get("expense-report").graph();
        return new Graph(base.nodes().stream().map(node -> {
            var properties = new LinkedHashMap<>(node.properties());
            List.of("expenseSplitRisk", "expenseSplitWindowDays", "expenseSplitThreshold", "expenseSplitCurrency", "expenseSplitRouting").forEach(properties::remove);
            if (node.type() == NodeType.START && !mode.isEmpty()) {
                properties.put("expenseSplitRisk", mode);
                if (mode.equals("ENABLED")) properties.putAll(Map.of("expenseSplitWindowDays", "7", "expenseSplitThreshold", "5000", "expenseSplitCurrency", "CNY"));
            }
            if (!mode.isEmpty() && (node.id().equals("amountGate") || markExecutive && node.id().equals("executiveGate"))) {
                properties.put("expenseSplitRouting", "AGGREGATE_AMOUNT");
            }
            return new Node(node.id(), node.name(), node.type(), properties);
        }).toList(), base.edges(), base.conditionLanguageVersion(), base.riskPolicy());
    }

    private Map<String, Object> values(String amount) { return Map.of("expenseDetails", "仅用于合成路由模拟", "amount", amount, "currency", "CNY", "overPolicy", false, "priorRequestOverTolerance", false, "hasProjectAllocation", false); }
    private Map<String, String> money(String amount, String currency) { return Map.of("value", amount, "currency", currency); }
    private Map<String, Object> body(Graph graph, String amount, Object synthetic) {
        var body = new LinkedHashMap<String, Object>();
        body.put("graph", graph); body.put("formSchema", catalog.get("expense-report").formSchema()); body.put("values", values(amount));
        if (synthetic != null) body.put("splitRoutingAmount", synthetic);
        return body;
    }
    private ResultActions send(String path, Object body, String user) throws Exception { return sendRaw(path, json.write(body), user); }
    private ResultActions sendRaw(String path, String body, String user) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) request.header("Authorization", "Bearer " + auth.login("demo", user, "demo").token());
        return mvc.perform(request).andDo(result -> {
            String directory = System.getProperty("agentflow.split.simulation.capture");
            if (directory != null) {
                Files.createDirectories(Path.of(directory));
                Files.writeString(Path.of(directory, UUID.randomUUID() + ".json"), json.write(Map.of("method", "POST", "path", path,
                        "request", json.read(body, Object.class), "status", result.getResponse().getStatus(),
                        "response", json.read(result.getResponse().getContentAsString(), Object.class))));
            }
        });
    }
    private Map<String, Integer> snapshot() {
        var counts = new LinkedHashMap<String, Integer>();
        for (String table : List.of("approval_definition", "approval_application", "expense_report", "expense_report_revision", "expense_split_routing", "expense_split_routing_source", "audit_event", "request_idempotency", "ACT_RU_EXECUTION", "ACT_RU_TASK")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }
}
