package io.agentflow.finance;

import io.agentflow.api.GlobalExceptionHandler;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * HTTP 参数不能在 Map 绑定时静默折叠，重复筛选值必须在进入工作区前拒绝。
 * @author owlzhangfq@gmail.com
 */
class CashierPaymentQueryTest {
    @Test void repeatedDateSortAndCursorParametersAreRejectedBeforeRepositoryQueries() throws Exception {
        var workspace = mock(CashierPaymentWorkspace.class);
        when(workspace.list(anyMap())).thenReturn(new CashierPaymentWorkspace.Page(List.of(), null, 0));
        var mvc = MockMvcBuilders.standaloneSetup(new CashierPaymentController(workspace, mock(CashierPaymentActions.class), mock(IdempotencyExecutor.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (var entry : Map.of("limit", "1", "dueFrom", "2026-10-01", "dueTo", "2026-10-02", "sort", "DUE_DATE_ASC", "undated", "true").entrySet()) {
            mvc.perform(get("/api/v1/cashier/payments").param(entry.getKey(), entry.getValue(), entry.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_QUERY"));
        }
        verifyNoInteractions(workspace);
        mvc.perform(get("/api/v1/cashier/payments").param("dueFrom", "2026-10-01").param("sort", "DUE_DATE_ASC")).andExpect(status().isOk());
        verify(workspace).list(Map.of("dueFrom", "2026-10-01", "sort", "DUE_DATE_ASC"));
    }
}
