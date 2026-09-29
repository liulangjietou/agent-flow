package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.ReservedAmount;
import org.apache.commons.lang3.StringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 已批准的事前申请按行管理可核销额度；关闭后禁止增加占用，仍允许释放与完成原预留。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseRequest {
    private final UUID id;
    private final String tenantId;
    private final UUID applicationId;
    private final UUID legalEntityId;
    private final String employeeId;
    private final List<ApprovedLine> approvedLines;
    private Map<Integer, ReservedAmount> balances;
    private boolean closed;
    private long version = 1;

    /** 只能由审批批准编排或可信导入建立，不允许普通草稿自行声明可用额度。 */
    public ExpenseRequest(UUID id, String tenantId, UUID applicationId, UUID legalEntityId, String employeeId, List<ApprovedLine> approvedLines) {
        this.id = Objects.requireNonNull(id); this.applicationId = Objects.requireNonNull(applicationId); this.legalEntityId = Objects.requireNonNull(legalEntityId);
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                || approvedLines == null || approvedLines.isEmpty() || approvedLines.size() > ExpenseContent.MAX_LINES
                || approvedLines.stream().anyMatch(java.util.Objects::isNull)
                || approvedLines.stream().map(ApprovedLine::lineNo).distinct().count() != approvedLines.size()) throw invalid();
        this.tenantId = tenantId; this.employeeId = employeeId; this.approvedLines = List.copyOf(approvedLines);
        var initial = new LinkedHashMap<Integer, ReservedAmount>();
        for (var line : approvedLines) initial.put(line.lineNo(), ReservedAmount.available(line.limit()));
        balances = Map.copyOf(initial);
    }

    /** 关闭禁止新用量和增额，核减与释放原占用仍然允许。 */
    public void reserve(long expectedVersion, int requestLine, ExpenseUse use, Money amount) {
        requireVersion(expectedVersion); requireLineUse(use);
        var balance = balance(requestLine);
        if (closed && amount.compareTo(balance.reservedFor(use)) > 0) throw new DomainException("EXPENSE_REQUEST_CLOSED", "Closed prior requests cannot accept new reservations");
        replace(requestLine, balance.reserve(use, amount));
    }

    /** 原已预留额度可随补正轮次迁移，关闭后的迁移同样不得增额。 */
    public void move(long expectedVersion, int requestLine, ExpenseUse previous, ExpenseUse next, Money amount) {
        requireVersion(expectedVersion); requireLineUse(previous); requireLineUse(next);
        var balance = balance(requestLine);
        if (closed && amount.compareTo(balance.reservedFor(previous)) > 0) throw new DomainException("EXPENSE_REQUEST_CLOSED", "Closed prior requests cannot increase reservations");
        replace(requestLine, balance.move(previous, next, amount));
    }

    /** 核销只转移已占用额，不扩大原事前申请授权。 */
    public void consume(long expectedVersion, int requestLine, ExpenseUse use) {
        requireVersion(expectedVersion); requireLineUse(use); replace(requestLine, balance(requestLine).consume(use));
    }

    /** 独立取消原报销只冲回对应行核销，原批准额度和关闭状态保持。 */
    public void reverseConsumption(long expectedVersion, int requestLine, ExpenseUse use, UUID adjustmentId, Instant at) {
        requireVersion(expectedVersion); requireLineUse(use);
        replace(requestLine, balance(requestLine).reverseConsumption(use, adjustmentId, at));
    }

    /** 显式关闭停止新增核销计划，保留仍未结算的全部占用。 */
    public void close(long expectedVersion) {
        requireVersion(expectedVersion);
        if (closed) throw new DomainException("EXPENSE_REQUEST_CLOSED", "Prior request is already closed");
        closed = true; version++;
    }

    /** 按原批准行读取，不能把其他行的余额挪来掩盖超支。 */
    public ReservedAmount balance(int lineNo) {
        var balance = balances.get(lineNo);
        if (balance == null) throw new DomainException("PRIOR_REQUEST_LINE_NOT_FOUND", "Prior request line does not exist");
        return balance;
    }

    private void replace(int lineNo, ReservedAmount balance) { var changed = new LinkedHashMap<>(balances); changed.put(lineNo, balance); balances = Map.copyOf(changed); version++; }
    private static void requireLineUse(ExpenseUse use) { if (use == null || use.lineNo() < 1) throw invalid(); }
    private void requireVersion(long expectedVersion) { if (version != expectedVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Prior request version has changed"); }
    private static DomainException invalid() { return new DomainException("INVALID_PRIOR_REQUEST", "An approved prior request with unique positive line amounts is required"); }

    /** 恢复时核对每行上限仍等于原批准及容差，不允许账本改写授权额度。 */
    public static ExpenseRequest restore(State state) {
        var result = new ExpenseRequest(state.id(), state.tenantId(), state.applicationId(), state.legalEntityId(), state.employeeId(), state.approvedLines());
        if (state.version() < 1 || !state.balances().keySet().equals(result.balances.keySet())) throw invalid();
        for (var line : result.approvedLines) {
            var balance = state.balances().get(line.lineNo());
            if (balance == null || !balance.limit().equals(line.limit()) || java.util.stream.Stream.concat(balance.reservations().stream(), balance.consumptions().stream())
                    .anyMatch(item -> item.use().lineNo() < 1)) throw invalid();
        }
        result.balances = state.balances(); result.closed = state.closed(); result.version = state.version(); return result;
    }

    /** 保存原批准行与当前额度，不把关闭改写成消耗全部余额。 */
    public State state() { return new State(id, tenantId, applicationId, legalEntityId, employeeId, approvedLines, balances, closed, version); }
    public UUID id() { return id; }
    public String tenantId() { return tenantId; }
    public UUID applicationId() { return applicationId; }
    public UUID legalEntityId() { return legalEntityId; }
    public String employeeId() { return employeeId; }
    public List<ApprovedLine> approvedLines() { return approvedLines; }
    public Map<Integer, ReservedAmount> balances() { return balances; }
    public boolean closed() { return closed; }
    public long version() { return version; }

    /**
     * 事前申请完整状态，持久化时仍保留原批准行。
     * @author owlzhangfq@gmail.com
     */
    public record State(UUID id, String tenantId, UUID applicationId, UUID legalEntityId, String employeeId,
                         List<ApprovedLine> approvedLines, Map<Integer, ReservedAmount> balances, boolean closed, long version) {
        /** 防止恢复后共享调用方集合。 */
        public State { approvedLines = List.copyOf(approvedLines); balances = Map.copyOf(balances); }
    }

    /**
     * 容差是明确发布的额度授权，零表示严格控制；不把示例百分比写成默认值。
     * @author owlzhangfq@gmail.com
     */
    public record ApprovedLine(int lineNo, Money approvedAmount, BigDecimal toleranceFraction, String policyReference) {
        /** 非负容差最多百分之百，允许额度始终向下取整到分，不能越过授权边界。 */
        public ApprovedLine {
            if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || approvedAmount == null || approvedAmount.value().signum() <= 0
                    || toleranceFraction == null || toleranceFraction.signum() < 0 || toleranceFraction.compareTo(BigDecimal.ONE) > 0
                    || toleranceFraction.stripTrailingZeros().scale() > 6 || StringUtils.isBlank(policyReference) || policyReference.length() > 128) throw invalid();
        }
        /** 只在形成批准额度时计算，后续预留不重复扩大容差。 */
        public Money limit() { return new Money(approvedAmount.value().multiply(BigDecimal.ONE.add(toleranceFraction)).setScale(Money.SCALE, RoundingMode.DOWN), approvedAmount.currency()); }
    }
}
