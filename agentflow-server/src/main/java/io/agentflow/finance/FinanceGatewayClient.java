package io.agentflow.finance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.agentflow.common.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 财务端口的固定协议传输；在事务外调用，不重定向、不自动重试、不透传远端错误正文。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FinanceGatewayClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    // 部分挂账最多保留 20,052 条实际分录；四个 128 字符字段经 JSON 转义后仍须有界接收。
    private static final int MAX_ACCRUAL_REDUCTION_RESPONSE_BYTES = 128 * 1024 * 1024;
    private static final int CONTRACT_VERSION = 1;
    private final FinanceGatewayConfiguration configuration;
    private final JsonUtil json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 隔离外部协议的严格反序列化策略，不改变平台已有 JSON 兼容行为。 */
    @SuppressWarnings("deprecation")
    public FinanceGatewayClient(FinanceGatewayConfiguration configuration, ObjectMapper mapper) {
        this.configuration = configuration;
        // 信封先读 JSON 树再映射领域类型；中间树也必须精确保留数量、汇率等十进制事实。
        var strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        // Jackson 的标量开关不禁止数字转文本；主体及来源标识必须保留财务协议的字符串类型。
        for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
            strict.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        this.json = new JsonUtil(strict);
    }

    /** 业务适配器核对结果与请求一致性，基础传输只认固定操作及封闭结果类型。 */
    public <T> FinanceResult<T> read(String tenantId, Operation operation, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        if (operation == Operation.BUDGET_COMMAND || operation == Operation.BUDGET_QUERY
                || operation == Operation.BUDGET_ADJUSTMENT_COMMAND || operation == Operation.BUDGET_ADJUSTMENT_QUERY
                || operation == Operation.BUDGET_REVERSAL_COMMAND || operation == Operation.BUDGET_REVERSAL_QUERY
                || operation == Operation.BUDGET_REDUCTION_COMMAND || operation == Operation.BUDGET_REDUCTION_QUERY
                || operation == Operation.PAYMENT_COMMAND || operation == Operation.PAYMENT_QUERY
                || operation == Operation.SUPPLIER_PAYABLE_HOLD_COMMAND || operation == Operation.SUPPLIER_PAYABLE_HOLD_QUERY
                || operation == Operation.SUPPLIER_PAYMENT_COMMAND || operation == Operation.SUPPLIER_PAYMENT_QUERY
                || operation == Operation.SUPPLIER_PAYABLE_SETTLEMENT_COMMAND || operation == Operation.SUPPLIER_PAYABLE_SETTLEMENT_QUERY
                || operation == Operation.SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND || operation == Operation.SUPPLIER_PAYABLE_ADJUSTMENT_QUERY
                || operation == Operation.VOUCHER_COMMAND || operation == Operation.VOUCHER_QUERY
                || operation == Operation.EXPENSE_ACCRUAL_REDUCTION_COMMAND || operation == Operation.EXPENSE_ACCRUAL_REDUCTION_QUERY
                || operation == Operation.ACCOUNTING_PERIOD || operation == Operation.ACCOUNT_MAPPING || operation == Operation.DEBIT_ACCOUNTS
                || operation == Operation.ADVANCE_REPAYMENT || operation == Operation.ADVANCE_REPAYMENT_ADJUSTMENT || operation == Operation.ADVANCE_DISBURSEMENT_RETURN
                || operation == Operation.EXPENSE_PAYMENT_RETURN || operation == Operation.SUPPLIER_PAYMENT_RETURN
                || operation == Operation.PROCUREMENT_PAYABLE || operation == Operation.BUDGET_LEDGER
                || operation == Operation.VOUCHER_REVERSAL || operation == Operation.VOUCHER_REVERSAL_COMMAND || operation == Operation.VOUCHER_REVERSAL_QUERY) {
            throw new IllegalArgumentException("A financial operation requires its persisted identity and destination");
        }
        return exchange(tenantId, null, operation, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 排队后的查询继续绑定原目的地；凭据可以轮换，但不能查询另一个预算系统。 */
    public <T> FinanceResult<T> queryBudget(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.BUDGET_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 预算写操作使用持久化编号作为请求编号和幂等头，绝不生成新的重试编号。 */
    public <T> FinanceResult<T> executeBudget(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A budget operation identity is required");
        return exchange(tenantId, targetDigest, Operation.BUDGET_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 独立冲正使用已持久化的授权编号，不改写原预算消费命令。 */
    public <T> FinanceResult<T> executeBudgetReversal(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A budget reversal operation identity is required");
        return exchange(tenantId, targetDigest, Operation.BUDGET_REVERSAL_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 冲正恢复始终查询原目标和原授权，不能创建替代命令。 */
    public <T> FinanceResult<T> queryBudgetReversal(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.BUDGET_REVERSAL_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 已消费预算只发送持久的独立差额，完整前后位置由预算系统原子核对。 */
    public <T> FinanceResult<T> executeBudgetReduction(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A budget reduction operation identity is required");
        return exchange(tenantId, targetDigest, Operation.BUDGET_REDUCTION_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 差额结果未知时查询相同目标和指令，不能通过普通读取生成替代编号。 */
    public <T> FinanceResult<T> queryBudgetReduction(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.BUDGET_REDUCTION_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 付款查询始终使用原租户、原目标和原授权，不能改查当前新配置的资金系统。 */
    public <T> FinanceResult<T> queryPayment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.PAYMENT_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 授权号兼作传输编号与外部幂等号，适配器不会创建替代授权或重试任务。 */
    public <T> FinanceResult<T> executePayment(String tenantId, String targetDigest, UUID authorizationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (authorizationId == null) throw new IllegalArgumentException("A payment authorization identity is required");
        return exchange(tenantId, targetDigest, Operation.PAYMENT_COMMAND, authorizationId, data, resultType, matchesRequest);
    }

    /** 原应付预留固定已保存的财务授权号和目的地，与银行付款采用不同操作。 */
    public <T> FinanceResult<T> reserveSupplierPayable(String tenantId, String targetDigest, UUID authorizationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (authorizationId == null) throw new IllegalArgumentException("A supplier payment authorization identity is required");
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_HOLD_COMMAND, authorizationId, data, resultType, matchesRequest);
    }

    /** 查询永远读取原预留，不能携带新预留指令或改用当前配置中的另一个 ERP。 */
    public <T> FinanceResult<T> querySupplierPayableHold(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_HOLD_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 供应商银行指令固定原财务授权号，预留命令与付款命令由独立操作类型区分。 */
    public <T> FinanceResult<T> executeSupplierPayment(String tenantId, String targetDigest, UUID authorizationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (authorizationId == null) throw new IllegalArgumentException("A supplier payment authorization identity is required");
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYMENT_COMMAND, authorizationId, data, resultType, matchesRequest);
    }

    /** 银行结果未知或授权到期后，只能向原目标查询原供应商付款。 */
    public <T> FinanceResult<T> querySupplierPayment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYMENT_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 原应付结算固定本次结算号及原银行付款，按独立操作类型去重，不再次调用银行付款。 */
    public <T> FinanceResult<T> settleSupplierPayable(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A supplier payable settlement identity is required");
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_SETTLEMENT_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 查询仅向原 ERP 找回原结算，不受原支付授权或会计期间窗口影响。 */
    public <T> FinanceResult<T> querySupplierPayableSettlement(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_SETTLEMENT_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 原付款回款另建固定调整号，ERP 原子核对新增入款、原记账和指定期间。 */
    public <T> FinanceResult<T> adjustSupplierPayable(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A supplier payable adjustment identity is required");
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 已发送调整的恢复不重复确认原付款或入款，永远查询原目标及原调整号。 */
    public <T> FinanceResult<T> querySupplierPayableAdjustment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYABLE_ADJUSTMENT_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 付款前账户复查绑定原目标，只允许员工账户及出纳出款目录两个只读操作。 */
    public <T> FinanceResult<T> readPaymentAccounts(String tenantId, String targetDigest, Operation operation, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operation != Operation.EMPLOYEE_ACCOUNT && operation != Operation.DEBIT_ACCOUNTS) throw new IllegalArgumentException("A payment account read operation is required");
        return exchange(tenantId, targetDigest, operation, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 结算准备中的只读事实仍绑定原财务系统，期间和映射不能来自不同目标。 */
    public <T> FinanceResult<T> readAccounting(String tenantId, String targetDigest, Operation operation, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operation != Operation.ACCOUNTING_PERIOD && operation != Operation.ACCOUNT_MAPPING) throw new IllegalArgumentException("An accounting read operation is required");
        return exchange(tenantId, targetDigest, operation, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 凭证重发只能保留原持久化编号，ERP 必须按同一编号与摘要幂等。 */
    public <T> FinanceResult<T> postVoucher(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A voucher operation identity is required");
        return exchange(tenantId, targetDigest, Operation.VOUCHER_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 查询过账事实无需重发明细，传输号与业务操作号分别保存。 */
    public <T> FinanceResult<T> queryVoucher(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.VOUCHER_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 还款查询绑定原放款财务系统，只读取收款和已过账冲减事实。 */
    public <T> FinanceResult<T> queryAdvanceRepayment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.ADVANCE_REPAYMENT, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 原还款复核只读取实际退回和已过账调整，不能更换原放款的财务目标。 */
    public <T> FinanceResult<T> queryRepaymentAdjustment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.ADVANCE_REPAYMENT_ADJUSTMENT, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 原放款退回仅按已固定目标读取公司实际回款与借款调整，禁止发送资金命令。 */
    public <T> FinanceResult<T> queryDisbursementReturn(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.ADVANCE_DISBURSEMENT_RETURN, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 报销退回按原付款及原挂账科目查询，不与借款退票或新付款命令混用。 */
    public <T> FinanceResult<T> queryExpensePaymentReturn(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.EXPENSE_PAYMENT_RETURN, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 供应商退回只读取原公司账户的实际入款，不继承新付款的发送窗口或改变原目标。 */
    public <T> FinanceResult<T> querySupplierPaymentReturn(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.SUPPLIER_PAYMENT_RETURN, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 采购应付匹配只读取原目标，不创建应付、余额占用或付款命令。 */
    public <T> FinanceResult<T> readProcurementPayable(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.PROCUREMENT_PAYABLE, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 独立预算读取原额度与占用，不向报销预算冻结接口发送调整意图。 */
    public <T> FinanceResult<T> readBudgetLedger(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.BUDGET_LEDGER, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 预算额度调整必须绑定持久授权，两端共用一个原子幂等编号。 */
    public <T> FinanceResult<T> executeBudgetAdjustment(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A persisted budget adjustment identity is required");
        return exchange(tenantId, targetDigest, Operation.BUDGET_ADJUSTMENT_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 调整恢复只读原目标和原编号，不通过新命令推断旧结果。 */
    public <T> FinanceResult<T> queryBudgetAdjustment(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.BUDGET_ADJUSTMENT_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 独立反向凭证只读核验，不以查询请求触发 ERP 冲销或新的资金动作。 */
    public <T> FinanceResult<T> queryVoucherReversal(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.VOUCHER_REVERSAL, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 真正冲销命令使用本地持久编号作幂等键，不能与只读分录核验混用。 */
    public <T> FinanceResult<T> postVoucherReversal(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("A persisted reversal operation identity is required");
        return exchange(tenantId, targetDigest, Operation.VOUCHER_REVERSAL_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 查询已经发出的原冲销命令，传输关联号变化不改变业务编号和摘要。 */
    public <T> FinanceResult<T> queryVoucherReversalOperation(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.VOUCHER_REVERSAL_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    /** 挂账部分调整使用独立持久编号，不覆盖原凭证或复用完整冲销写入。 */
    public <T> FinanceResult<T> postExpenseAccrualReduction(String tenantId, String targetDigest, UUID operationId, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        if (operationId == null) throw new IllegalArgumentException("An expense accrual reduction identity is required");
        return exchange(tenantId, targetDigest, Operation.EXPENSE_ACCRUAL_REDUCTION_COMMAND, operationId, data, resultType, matchesRequest);
    }

    /** 未知的挂账差额继续查询原目的地、原编号与摘要，不触发新的过账。 */
    public <T> FinanceResult<T> queryExpenseAccrualReduction(String tenantId, String targetDigest, Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        requireTarget(targetDigest);
        return exchange(tenantId, targetDigest, Operation.EXPENSE_ACCRUAL_REDUCTION_QUERY, UUID.randomUUID(), data, resultType, matchesRequest);
    }

    private <T> FinanceResult<T> exchange(String tenantId, String targetDigest, Operation operation, UUID requestId,
                                        Object data, Class<T> resultType, Predicate<T> matchesRequest) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Finance gateway must run outside a transaction");
        var selected = configuration.destination(tenantId);
        if (selected.isEmpty()) return unavailable(FinanceResult.Failure.NOT_CONFIGURED);
        var destination = selected.get();
        if (targetDigest != null && !targetDigest.equals(destination.digest(tenantId))) return unavailable(FinanceResult.Failure.TARGET_CHANGED);
        var request = HttpRequest.newBuilder(destination.baseUri().resolve(operation.path)).timeout(destination.timeout())
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(new Request(CONTRACT_VERSION, tenantId, requestId, data)), StandardCharsets.UTF_8));
        if (operation == Operation.BUDGET_COMMAND || operation == Operation.PAYMENT_COMMAND || operation == Operation.VOUCHER_COMMAND
                || operation == Operation.VOUCHER_REVERSAL_COMMAND || operation == Operation.BUDGET_REVERSAL_COMMAND || operation == Operation.BUDGET_ADJUSTMENT_COMMAND
                || operation == Operation.BUDGET_REDUCTION_COMMAND
                || operation == Operation.EXPENSE_ACCRUAL_REDUCTION_COMMAND
                || operation == Operation.SUPPLIER_PAYABLE_HOLD_COMMAND || operation == Operation.SUPPLIER_PAYMENT_COMMAND
                || operation == Operation.SUPPLIER_PAYABLE_SETTLEMENT_COMMAND || operation == Operation.SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND) request.header("Idempotency-Key", requestId.toString());
        if (!destination.token().isEmpty()) request.header("Authorization", "Bearer " + destination.token());
        var future = client.sendAsync(request.build(), response -> new BoundedBody(operation.maxResponseBytes));
        try {
            var response = future.get(destination.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) return unavailable(FinanceResult.Failure.AUTHENTICATION);
            if (response.statusCode() != 200) return unavailable(FinanceResult.Failure.REMOTE_FAILURE);
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!"application/json".equalsIgnoreCase(contentType)) return unavailable(FinanceResult.Failure.INVALID_RESPONSE);
            return parse(new String(response.body(), StandardCharsets.UTF_8), tenantId, requestId, operation, resultType, matchesRequest);
        } catch (TimeoutException timeout) { return unavailable(FinanceResult.Failure.TIMEOUT); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return unavailable(FinanceResult.Failure.CONNECTION); }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseTooLarge) return unavailable(FinanceResult.Failure.RESPONSE_TOO_LARGE);
            return unavailable(cause instanceof java.net.http.HttpTimeoutException ? FinanceResult.Failure.TIMEOUT : FinanceResult.Failure.CONNECTION);
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private <T> FinanceResult<T> parse(String body, String tenantId, UUID requestId, Operation operation, Class<T> resultType, Predicate<T> matchesRequest) {
        try {
            JsonNode envelope = json.read(body, JsonNode.class);
            if (!envelope.isObject() || envelope.size() != 5 || !envelope.path("contractVersion").isInt()
                    || envelope.path("contractVersion").intValue() != CONTRACT_VERSION || !envelope.path("tenantId").isTextual()
                    || !tenantId.equals(envelope.path("tenantId").textValue()) || !envelope.path("requestId").isTextual()
                    || !requestId.toString().equals(envelope.path("requestId").textValue()) || !envelope.path("outcome").isTextual()) return invalid();
            if ("REJECTED".equals(envelope.path("outcome").textValue()) && envelope.path("reason").isTextual()) {
                var reason = FinanceResult.Reason.valueOf(envelope.path("reason").textValue());
                return operation.reasons.contains(reason) ? new FinanceResult.Rejected<>(reason) : invalid();
            }
            if (!"SUCCESS".equals(envelope.path("outcome").textValue()) || !envelope.path("data").isObject()) return invalid();
            T result = json.read(envelope.path("data").toString(), resultType);
            return matchesRequest.test(result) ? new FinanceResult.Success<>(result) : invalid();
        } catch (RuntimeException malformed) { return invalid(); }
    }

    private static <T> FinanceResult<T> unavailable(FinanceResult.Failure failure) { return new FinanceResult.Unavailable<>(failure); }
    private static <T> FinanceResult<T> invalid() { return unavailable(FinanceResult.Failure.INVALID_RESPONSE); }
    private static void requireTarget(String targetDigest) {
        if (StringUtils.isBlank(targetDigest) || !targetDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A persisted finance gateway target is required");
    }

    /**
     * 已实现接口的固定路径；预算操作的拒绝必须放在绑定原命令的事实中，不能只返回通用拒绝。
     * @author owlzhangfq@gmail.com
     */
    public enum Operation {
        CATALOG("catalog", Set.of(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        EMPLOYEE_ACCOUNT("employee-account", Set.of(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE, FinanceResult.Reason.ACCOUNT_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        EXCHANGE_RATE("exchange-rate", Set.of(FinanceResult.Reason.RATE_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        EXPENSE_POLICY_GUIDANCE("expense-policy-guidance", Set.of(FinanceResult.Reason.POLICY_NOT_FOUND,
                FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        EXPENSE_POLICY("expense-policy", Set.of(FinanceResult.Reason.POLICY_NOT_FOUND, FinanceResult.Reason.EXPENSE_PROHIBITED,
                FinanceResult.Reason.PRIOR_REQUEST_REQUIRED, FinanceResult.Reason.COST_OBJECT_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        INVOICE_VERIFICATION("invoice-verification", Set.of(FinanceResult.Reason.INVOICE_INVALID, FinanceResult.Reason.INVOICE_CANCELLED,
                FinanceResult.Reason.INVOICE_BUYER_MISMATCH, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        BUDGET_PRECHECK("budget-precheck", Set.of(FinanceResult.Reason.BUDGET_INSUFFICIENT, FinanceResult.Reason.BUDGET_POLICY_UNAVAILABLE,
                FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED, FinanceResult.Reason.COST_OBJECT_UNAVAILABLE,
                FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        BUDGET_LEDGER("budget-ledger", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE,
                FinanceResult.Reason.BUDGET_POSITION_UNAVAILABLE, FinanceResult.Reason.BUDGET_POLICY_UNAVAILABLE, FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED)),
        BUDGET_ADJUSTMENT_COMMAND("budget-adjustment-command", Set.of()),
        BUDGET_ADJUSTMENT_QUERY("budget-adjustment-query", Set.of()),
        BUDGET_COMMAND("budget-command", Set.of()),
        BUDGET_QUERY("budget-query", Set.of()),
        BUDGET_REVERSAL_COMMAND("budget-consumption-reversal-command", Set.of()),
        BUDGET_REVERSAL_QUERY("budget-consumption-reversal-query", Set.of()),
        BUDGET_REDUCTION_COMMAND("budget-consumption-reduction-command", Set.of()),
        BUDGET_REDUCTION_QUERY("budget-consumption-reduction-query", Set.of()),
        PAYMENT_COMMAND("payment-command", Set.of()),
        PAYMENT_QUERY("payment-query", Set.of()),
        DEBIT_ACCOUNTS("debit-accounts", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.CASHIER_UNAVAILABLE, FinanceResult.Reason.DEBIT_ACCOUNT_UNAVAILABLE)),
        ACCOUNTING_PERIOD("accounting-period", Set.of(FinanceResult.Reason.ACCOUNTING_PERIOD_CLOSED, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        ACCOUNT_MAPPING("account-mapping", Set.of(FinanceResult.Reason.ACCOUNT_MAPPING_UNAVAILABLE, FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        VOUCHER_COMMAND("voucher-command", Set.of()),
        VOUCHER_QUERY("voucher-query", Set.of()),
        ADVANCE_REPAYMENT("advance-repayment", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        ADVANCE_REPAYMENT_ADJUSTMENT("advance-repayment-adjustment", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        ADVANCE_DISBURSEMENT_RETURN("advance-disbursement-return", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        EXPENSE_PAYMENT_RETURN("expense-payment-return", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE)),
        PROCUREMENT_PAYABLE("procurement-payable", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.EMPLOYEE_UNAVAILABLE,
                FinanceResult.Reason.SUPPLIER_UNAVAILABLE, FinanceResult.Reason.ACCOUNT_UNAVAILABLE,
                FinanceResult.Reason.PROCUREMENT_PAYABLE_UNAVAILABLE, FinanceResult.Reason.PROCUREMENT_MATCH_REQUIRED)),
        SUPPLIER_PAYABLE_HOLD_COMMAND("supplier-payable-hold-command", Set.of()),
        SUPPLIER_PAYABLE_HOLD_QUERY("supplier-payable-hold-query", Set.of()),
        SUPPLIER_PAYMENT_COMMAND("supplier-payment-command", Set.of()),
        SUPPLIER_PAYMENT_QUERY("supplier-payment-query", Set.of()),
        SUPPLIER_PAYMENT_RETURN("supplier-payment-return", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE, FinanceResult.Reason.SUPPLIER_UNAVAILABLE)),
        SUPPLIER_PAYABLE_SETTLEMENT_COMMAND("supplier-payable-settlement-command", Set.of()),
        SUPPLIER_PAYABLE_SETTLEMENT_QUERY("supplier-payable-settlement-query", Set.of()),
        SUPPLIER_PAYABLE_ADJUSTMENT_COMMAND("supplier-payable-adjustment-command", Set.of()),
        SUPPLIER_PAYABLE_ADJUSTMENT_QUERY("supplier-payable-adjustment-query", Set.of()),
        VOUCHER_REVERSAL("voucher-reversal", Set.of(FinanceResult.Reason.LEGAL_ENTITY_UNAVAILABLE)),
        VOUCHER_REVERSAL_COMMAND("voucher-reversal-command", Set.of()),
        VOUCHER_REVERSAL_QUERY("voucher-reversal-query", Set.of()),
        EXPENSE_ACCRUAL_REDUCTION_COMMAND("expense-accrual-reduction-command", Set.of(), MAX_ACCRUAL_REDUCTION_RESPONSE_BYTES),
        EXPENSE_ACCRUAL_REDUCTION_QUERY("expense-accrual-reduction-query", Set.of(), MAX_ACCRUAL_REDUCTION_RESPONSE_BYTES);
        private final String path;
        private final Set<FinanceResult.Reason> reasons;
        private final int maxResponseBytes;
        Operation(String path, Set<FinanceResult.Reason> reasons) { this(path, reasons, MAX_RESPONSE_BYTES); }
        Operation(String path, Set<FinanceResult.Reason> reasons, int maxResponseBytes) {
            this.path = path; this.reasons = reasons; this.maxResponseBytes = maxResponseBytes;
        }
    }

    /**
     * 每次读取使用独立编号，成功和业务拒绝都必须回传原租户与编号。
     * @author owlzhangfq@gmail.com
     */
    private record Request(int contractVersion, String tenantId, UUID requestId, Object data) { }

    /**
     * 接收期间超限立即取消，限制整个响应体，而非只限制 Content-Length。
     * @author owlzhangfq@gmail.com
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private BoundedBody(int maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (value.remaining() > maxBytes - body.size()) {
                    subscription.cancel(); result.completeExceptionally(new ResponseTooLarge()); return;
                }
                byte[] bytes = new byte[value.remaining()]; value.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }

    /**
     * 只携带稳定分类，不把远端响应或凭据加入异常。
     * @author owlzhangfq@gmail.com
     */
    private static final class ResponseTooLarge extends RuntimeException { }
}
