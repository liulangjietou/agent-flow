package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把平台版本配置绑定到真实费用用例；不承担企业职级、城市等级、税额或费用结论的推断。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePolicyConfiguration {
    private final ExpenseConfigurationService service;
    private final JdbcExpenseConfigurationRepository repository;
    private final JsonUtil json;

    /** 配置读取使用统一正文与摘要，提交锁与管理员发布共用同一租户配置头。 */
    public ExpensePolicyConfiguration(ExpenseConfigurationService service, JdbcExpenseConfigurationRepository repository, JsonUtil json) {
        this.service = service; this.repository = repository; this.json = json;
    }

    /** 一次预检固定一个完整快照，不能在不同行之间自动更换新制度。 */
    public Snapshot snapshot(String tenant) {
        var current = service.current(tenant); var policy = current.activePolicy();
        var selection = policy == null ? null : new ExpensePolicySelection(policy.policyId(), policy.version(), current.categories().version(), current.activeRevision(), digest(json.write(policy.definition())));
        return new Snapshot(current, selection);
    }

    /** 旧预检在制度发布或生效目录修订后必须失效，草稿编辑不影响已发布判定。 */
    public boolean current(String tenant, ExpensePolicySelection selection) { return Objects.equals(snapshot(tenant).selection(), selection); }

    /** 事前计划只使用启用后的类别目录，费用制度单独发布不改变其金额或有效性。 */
    public Long categoryRevision(String tenant) {
        var current = service.current(tenant);
        return current.activePolicy() == null ? null : current.categories().version();
    }

    /** 配置锁必须留在最终提交事务内，不能检查后释放再创建审批轮次。 */
    public void lockForSubmission(String tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Expense policy submission lock requires the active write transaction");
        }
        repository.lock(tenant);
    }

    /** 平台类别只能缩小员工授权目录，新增代码或单位不会凭配置获得外部主数据授权。 */
    public FinanceCatalog filterCatalog(String tenant, FinanceCatalog authorized) {
        var state = snapshot(tenant); if (state.selection() == null) return authorized;
        var configured = new HashMap<String, ExpenseCategoryCatalog.Category>();
        state.current().categories().activeCategories().forEach(category -> configured.put(category.code(), category));
        var categories = authorized.categories().stream().filter(category -> configured.containsKey(category.code()))
                .map(category -> {
                    var entry = configured.get(category.code());
                    var units = category.units().stream().filter(entry.units()::contains).toList();
                    return units.isEmpty() ? null : new FinanceCatalog.Category(entry.code(), entry.name(), units);
                }).filter(Objects::nonNull).toList();
        return new FinanceCatalog(authorized.employeeId(), "managed:" + digest(authorized.sourceVersion() + "\n" + json.write(state.selection())),
                authorized.validUntil(), authorized.legalEntities(), categories, authorized.costCenters(), authorized.projects(), authorized.cities());
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    /**
     * 固定本次配置，可为多条费用生成同一版本的请求。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(ExpenseConfigurationService.Current current, ExpensePolicySelection selection) {
        /** 未管理租户保留旧外部制度，已管理租户缺少类别时明确阻断。 */
        public ManagedExpensePolicy forLine(ExpenseLine line) {
            return forCategory(line.categoryCode(), line.unit());
        }

        /** 填报规则提示和正式预检引用相同的类别修订与发布正文。 */
        public ManagedExpensePolicy forCategory(String categoryCode, ExpenseLine.Unit unit) {
            if (selection == null) return null;
            var category = current.categories().activeCategories().stream()
                    .filter(value -> value.code().equals(categoryCode) && value.units().contains(unit)).findFirst()
                    .orElseThrow(() -> new DomainException("EXPENSE_CATEGORY_UNAVAILABLE", "Expense category or unit is not available in the managed revision"));
            return new ManagedExpensePolicy(selection, current.activePolicy().definition(), category);
        }
    }
}
