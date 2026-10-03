package io.agentflow.finance;

import io.agentflow.api.GlobalExceptionHandler;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 目录 API 从当前主体取租户和员工，拒绝页面覆盖，并保持不可用与业务拒绝的不同状态码。
 * @author owlzhangfq@gmail.com
 */
class FinanceCatalogControllerTest {
    private final CurrentActor actors = new CurrentActor();
    private final FinanceMasterDataPort master = mock(FinanceMasterDataPort.class);
    private MockMvc mvc;

    @BeforeEach
    void configure() {
        actors.set(new Actor("tenant-a", "alice", Set.of("EMPLOYEE")));
        mvc = MockMvcBuilders.standaloneSetup(new FinanceCatalogController(actors, master)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clearIdentity() { actors.clear(); }

    @Test
    void directoryAlwaysUsesCurrentIdentityAndDisablesCaching() throws Exception {
        when(master.catalog("tenant-a", "alice")).thenReturn(new FinanceResult.Success<>(
                new FinanceCatalog("alice", "v1", Instant.now().plusSeconds(60), List.of(), List.of(), List.of(), List.of(), List.of())));
        mvc.perform(get("/api/v1/finance/catalog")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.employeeId").value("alice"));
        verify(master).catalog("tenant-a", "alice");
        actors.set(new Actor("tenant-b", "alice", Set.of("ADMIN")));
        when(master.catalog("tenant-b", "alice")).thenReturn(new FinanceResult.Unavailable<>(FinanceResult.Failure.NOT_CONFIGURED));
        mvc.perform(get("/api/v1/finance/catalog")).andExpect(status().isServiceUnavailable());
        verify(master).catalog("tenant-b", "alice");
    }

    @Test
    void employeeTenantOverridesAndAnonymousReadsDoNotCallMasterData() throws Exception {
        for (String query : List.of("employeeId=bob", "tenantId=tenant-b", "unknown=value")) {
            mvc.perform(get("/api/v1/finance/catalog?" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FINANCE_QUERY"));
        }
        actors.clear();
        mvc.perform(get("/api/v1/finance/catalog")).andExpect(status().isUnauthorized());
        verifyNoInteractions(master);
    }

    @Test
    void unavailableIs503AndBusinessRejectionIs422WithoutSyntheticDefaults() throws Exception {
        when(master.catalog("tenant-a", "alice")).thenReturn(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT));
        mvc.perform(get("/api/v1/finance/catalog")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FINANCE_GATEWAY_UNAVAILABLE")).andExpect(jsonPath("$.message").value("TIMEOUT"));
        when(master.catalog("tenant-a", "alice")).thenReturn(new FinanceResult.Rejected<>(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE));
        mvc.perform(get("/api/v1/finance/catalog")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FINANCE_RULE_REJECTED")).andExpect(jsonPath("$.message").value("EMPLOYEE_UNAVAILABLE"));
    }
}
