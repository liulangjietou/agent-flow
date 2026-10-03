package io.agentflow.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真实 HTTP 制度提示以本人身份、授权目录和发布版本为边界，不产生预检或提交结论。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties={"agentflow.auth.demo-enabled=true","agentflow.finance-gateway.enabled=true",
        "agentflow.expenses.precheck-worker-enabled=false","agentflow.expense-plans.precheck-worker-enabled=false",
        "agentflow.invoices.verification-worker-enabled=false","agentflow.budgets.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ExpensePolicyGuidanceIntegrationTest {
    private static final UUID ENTITY=UUID.randomUUID(), POLICY=UUID.randomUUID();
    private static final AtomicReference<ExpensePolicyGuidanceIntegrationTest> ACTIVE=new AtomicReference<>();
    private static final HttpServer SERVER=server();
    private static final String PATH="/api/v1/finance/expense-policy-guidance";
    private static final String QUERY="legalEntityId="+ENTITY+"&reportType=TRAVEL&categoryCode=TRAVEL&cityCode=SH&incurredOn=2026-10-02&currency=CNY&unit=NIGHT";
    private final Actor admin=new Actor("demo","admin",Set.of("FINANCE_CONFIG_ADMIN"));
    private final List<JsonNode> calls=new CopyOnWriteArrayList<>();
    private final List<String> operations=new CopyOnWriteArrayList<>();
    private BiFunction<String,JsonNode,String> responder;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseConfigurationService configuration;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory",()->"/fyoung/tmp/agentflow-policy-guidance-"+ENTITY);
        registry.add("agentflow.finance-gateway.tenants.demo.endpoint",()->"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/finance");
        registry.add("agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback",()->"true");
        registry.add("spring.datasource.url",()->System.getenv().getOrDefault("AGENTFLOW_GUIDANCE_TEST_URL","jdbc:h2:mem:policy-guidance;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name",()->System.getenv().getOrDefault("AGENTFLOW_GUIDANCE_TEST_DRIVER","org.h2.Driver"));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("AGENTFLOW_GUIDANCE_TEST_USER","sa"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("AGENTFLOW_GUIDANCE_TEST_PASSWORD",""));
    }
    @BeforeEach void reset() { ACTIVE.set(this); responder=this::normal; }
    @AfterEach void clear() {
        jdbc.update("UPDATE expense_configuration SET active_revision=0,active_policy_id=NULL,active_policy_version=NULL WHERE tenant_id='demo'");
        for (String table:List.of("expense_policy_activation","expense_policy_version","expense_policy_draft_revision","expense_policy_draft","expense_category_revision","expense_configuration")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id='demo'");
    }
    @AfterAll static void close() { SERVER.stop(0); }

    @Test void managedGuidanceUsesCallerAndPublishedRuleWithoutLeakingOtherRules() throws Exception {
        configured(); var response=read(QUERY,"alice"); var body=ok(response,200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body.at("/guidance/selection/categoryRevision").asLong()).isEqualTo(1);
        assertThat(body.at("/guidance/constraints/unitPriceLimit/value").asText()).isEqualTo("200.00");
        assertThat(body.at("/context/cityCode").asText()).isEqualTo("SH");
        assertThat(body.at("/guidance/factSourceReference").asText()).isEqualTo("synthetic-grade-city-source");
        assertThat(body.toString()).doesNotContain("employeeGrades","cityTiers","OTHER_EMPLOYEE_RULE");
        assertThat(body.path("guidance").has("definition")).isFalse();
        assertThat(Instant.parse(body.at("/guidance/validUntil").asText())).isBefore(Instant.now().plusSeconds(121));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).at("/data/employeeId").asText()).isEqualTo("alice");
        assertThat(calls.get(1).at("/data/employeeId").asText()).isEqualTo("alice");
        assertThat(calls.get(1).at("/data/managedPolicy/selection")).isEqualTo(body.at("/guidance/selection"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expense_precheck_job",Integer.class)).isZero();
    }

    @Test void externalOnlyTenantStillReceivesVersionedGuidance() throws Exception {
        var body=ok(read(QUERY,"alice"),200);
        assertThat(body.at("/guidance/policyId").asText()).isEqualTo(POLICY.toString());
        assertThat(body.at("/guidance/policyVersion").asLong()).isEqualTo(7);
        assertThat(body.at("/guidance/selection").isNull() || body.at("/guidance/selection").isMissingNode()).isTrue();
        assertThat(calls.get(1).at("/data/managedPolicy").isNull() || calls.get(1).at("/data/managedPolicy").isMissingNode()).isTrue();
    }

    @Test void callerCannotOverrideEmployeeTenantGradeOrSupplyRepeatedDimensions() throws Exception {
        for (String suffix:List.of("&employeeId=bob","&tenantId=other","&employeeGrade=G9","&cityTier=T9","&cityCode=OTHER","&selection=x")) {
            assertThat(read(QUERY+suffix,"alice").getStatus()).isEqualTo(400);
        }
        for (String bad:List.of(QUERY.replace("NIGHT","UNKNOWN"),QUERY.replace("2026-10-02","2026-02-30"),QUERY.replace("currency=CNY","currency=cny"),
                QUERY.replace("currency=CNY","currency=JPY"),QUERY.replace("currency=CNY","currency=ZZZ"),QUERY.replace("&unit=NIGHT",""))) {
            assertThat(read(bad,"alice").getStatus()).isEqualTo(400);
        }
        assertThat(mvc.perform(get(PATH+"?"+QUERY)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(calls).isEmpty();
    }

    @Test void unauthorizedCatalogDimensionsNeverReachPolicySource() throws Exception {
        for (String invalid:List.of(QUERY.replace(ENTITY.toString(),UUID.randomUUID().toString()),QUERY.replace("categoryCode=TRAVEL","categoryCode=SECRET"),QUERY.replace("cityCode=SH","cityCode=SECRET"),QUERY.replace("unit=NIGHT","unit=KILOMETER"))) {
            assertThat(read(invalid,"alice").getStatus()).isEqualTo(422);
        }
        assertThat(calls).hasSize(4); assertThat(operations).containsOnly("catalog");
    }

    @Test void publishingWhileSourceIsWaitingDiscardsOldGuidance() throws Exception {
        configured(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        responder=(operation,request)->{ if (operation.equals("expense-policy-guidance")) { entered.countDown(); await(release); } return normal(operation,request); };
        var pool=Executors.newSingleThreadExecutor();
        try {
            var response=pool.submit(()->read(QUERY,"alice")); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            configuration.saveDraft(admin,"guidance",1,definition("新版合成规则"),"修改草稿");
            configuration.publish(admin,"guidance",2,1,1,"发布新版本"); release.countDown();
            var result=response.get(10,TimeUnit.SECONDS); assertThat(result.getStatus()).isEqualTo(409);
            assertThat(tree(result).path("code").asText()).isEqualTo("POLICY_CONFIGURATION_CHANGED");
            assertThat(tree(result).has("guidance")).isFalse();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"selection","policyId","ruleKey","constraints","expired","currency","source","name"})
    void mismatchedManagedReceiptsNeverBecomeFillingAdvice(String defect) throws Exception {
        configured(); responder=(operation,request)->{
            var body=json.read(normal(operation,request),ObjectNode.class);
            if (!operation.equals("expense-policy-guidance")) return json.write(body);
            var data=(ObjectNode)body.path("data");
            switch(defect) {
                case "selection"->((ObjectNode)data.path("selection")).put("activeRevision",99);
                case "policyId"->data.put("policyId",UUID.randomUUID().toString());
                case "ruleKey"->data.put("ruleKey","absent");
                case "constraints"->((ObjectNode)data.path("constraints")).put("priorRequestRequired",false);
                case "expired"->data.put("validUntil","2000-01-01T00:00:00Z");
                case "currency"->((ObjectNode)data.at("/constraints/unitPriceLimit")).put("currency","USD");
                case "source"->data.put("factSourceReference","");
                case "name"->data.put("policyName","wrong-definition-name");
                default->throw new IllegalArgumentException("Unknown synthetic defect");
            }
            return json.write(body);
        };
        var response=read(QUERY,"alice"); assertThat(response.getStatus()).isEqualTo(503);
        assertThat(tree(response).path("code").asText()).isEqualTo("FINANCE_GATEWAY_UNAVAILABLE");
        assertThat(tree(response).has("guidance")).isFalse();
    }

    private void configured() {
        configuration.saveCategories(admin,0,List.of(new ExpenseCategoryCatalog.Category("TRAVEL","差旅",List.of(ExpenseLine.Unit.NIGHT),true)),"合成类别");
        configuration.saveDraft(admin,"guidance",0,definition("合成差旅制度"),"合成草稿");
        configuration.publish(admin,"guidance",1,1,0,"合成发布");
    }
    private ExpensePolicyDefinition definition(String name) {
        return new ExpensePolicyDefinition(name,List.of(new ExpensePolicyDefinition.Rule("hotel","住宿标准",
                new ExpensePolicyDefinition.Match(List.of(ENTITY),List.of("TRAVEL"),List.of("T1"),List.of("G1"),null,null,"CNY"),constraints()),
                new ExpensePolicyDefinition.Rule("other","OTHER_EMPLOYEE_RULE",new ExpensePolicyDefinition.Match(List.of(ENTITY),List.of("TRAVEL"),List.of("T9"),List.of("G9"),null,null,"CNY"),constraints())));
    }
    private ExpensePolicyDefinition.Constraints constraints() {
        return new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW,new Money(new BigDecimal("200"),"CNY"),ExpenseLine.Unit.NIGHT,30,ExpensePolicyDefinition.AgeAction.REQUIRE_REASON,List.of("STANDARD"),true);
    }
    private String normal(String operation,JsonNode request) {
        Object data;
        if (operation.equals("catalog")) data=new FinanceCatalog(request.at("/data/employeeId").asText(),"catalog-1",Instant.now().plusSeconds(120),
                List.of(new FinanceCatalog.LegalEntity(ENTITY,"合成法人","CNY",false,"v1","Asia/Shanghai")),List.of(new FinanceCatalog.Category("TRAVEL","差旅",List.of(ExpenseLine.Unit.NIGHT))),List.of(),List.of(),List.of(new FinanceCatalog.City("SH","上海")));
        else if (operation.equals("expense-policy-guidance")) {
            var managed=request.at("/data/managedPolicy"); boolean unmanaged=managed.isNull() || managed.isMissingNode(); var value=new java.util.LinkedHashMap<String,Object>();
            value.put("policyId",unmanaged?POLICY:UUID.fromString(managed.at("/selection/policyId").asText()));
            value.put("policyVersion",unmanaged?7:managed.at("/selection/policyVersion").asLong());
            value.put("policyName",unmanaged?"外部差旅制度":managed.at("/definition/name").asText());
            value.put("ruleKey","hotel"); value.put("ruleName","住宿标准"); value.put("constraints",constraints());
            value.put("factSourceReference","synthetic-grade-city-source"); value.put("validUntil",Instant.now().plusSeconds(600));
            value.put("selection",unmanaged?null:managed.path("selection")); data=value;
        } else throw new IllegalArgumentException("Unexpected synthetic operation: "+operation);
        return json.write(Map.of("contractVersion",1,"tenantId","demo","requestId",request.path("requestId").asText(),"outcome","SUCCESS","data",data));
    }
    private MockHttpServletResponse read(String query,String user) throws Exception { return mvc.perform(get(PATH+"?"+query).header("Authorization","Bearer "+auth.login("demo",user,"demo").token())).andReturn().getResponse(); }
    private JsonNode tree(MockHttpServletResponse response) { return json.read(new String(response.getContentAsByteArray(),StandardCharsets.UTF_8),JsonNode.class); }
    private JsonNode ok(MockHttpServletResponse response,int status) { assertThat(response.getStatus()).as(new String(response.getContentAsByteArray(),StandardCharsets.UTF_8)).isEqualTo(status); return tree(response); }
    private static void await(CountDownLatch latch) { try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic wait timed out"); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
    private static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/finance",exchange->{
                var current=ACTIVE.get(); var request=current.json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),JsonNode.class); current.calls.add(request);
                String operation=exchange.getRequestURI().getPath().substring("/finance/".length()); current.operations.add(operation);
                byte[] response=current.responder.apply(operation,request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,response.length);
                exchange.getResponseBody().write(response); exchange.close();
            });server.start();return server;
        } catch(java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
