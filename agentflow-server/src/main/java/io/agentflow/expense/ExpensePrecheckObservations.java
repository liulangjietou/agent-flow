package io.agentflow.expense;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePrecheckObservationsMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 费用上下文保留解释的权威版本依据；不向模型或页面暴露资源标识、票号与其他单据归属。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePrecheckObservations {
    private final ExpensePrecheckObservationsMapper sqlMapper;
    private final ExpensePrecheckResources resources;
    private final ExpensePolicyConfiguration policies;
    private final JdbcInvoiceOriginalRepository originals;
    private final JsonUtil json;

    /** 原件只读取元数据，文件校验和财务 HTTP 继续由预检执行器负责。 */
    public ExpensePrecheckObservations(
            ExpensePrecheckResources resources,
            ExpensePolicyConfiguration policies,
            JdbcInvoiceOriginalRepository originals,
            ExpensePrecheckObservationsMapper sqlMapper,
            JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.resources = resources;
        this.policies = policies;
        this.originals = originals;
        this.json = json;
    }

    /** 在外部调用前用短事务固定本地依赖，未完成的检查不会因此获得提交资格。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Capture capture(ExpenseReport report, Instant at) {
        return new Capture(policies.snapshot(report.tenantId()), dependencies(report), at.truncatedTo(ChronoUnit.MILLIS));
    }

    /** 历史缺少依据、制度换版及资源变化都要求新检查，不能把当前值补进旧结论。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String failure(ExpenseReport report, ExpensePrecheckObservation observation, Instant now) {
        if (observation == null) return "PRECHECK_EXPLANATION_REFRESH_REQUIRED";
        if (!observation.currentAt(now)) return "FACTS_EXPIRED";
        if (!policies.current(report.tenantId(), observation.policySelection())) return "POLICY_CONFIGURATION_CHANGED";
        return dependencies(report).equals(observation.dependencyDigest()) ? null : "RESOURCES_CHANGED";
    }

    private String dependencies(ExpenseReport report) {
        ExpenseSubmissionResources.Resources loaded;
        try {
            loaded = resources.load(report);
        } catch (DomainException unavailable) {
            if (!"EXPENSE_RESOURCE_UNAVAILABLE".equals(unavailable.code())) throw unavailable;
            // 不读取或披露他人资源；引用重新可用后，依赖摘要自然变化。
            return digest(json.write(Map.of("resourceFailure", unavailable.code())));
        }
        var ids = loaded.invoices().keySet().stream().map(Object::toString).sorted().toList();
        List<CheckRevision> checks =
                ids.isEmpty()
                        ? List.of()
                        : SqlRows.map(
                                sqlMapper.dependencies(
                                        Map.of("tenant", report.tenantId(), "ids", ids)),
                                row ->
                                        new CheckRevision(
                                                row.getString("invoice_id"),
                                                row.getLong("attempts"),
                                                row.getLong("revisions")));
        var keys =
                loaded.invoices().values().stream()
                        .filter(value -> value.facts() != null)
                        .map(value -> value.facts().key().canonical())
                        .distinct()
                        .sorted()
                        .toList();
        List<Claim> claims =
                keys.isEmpty()
                        ? List.of()
                        : SqlRows.map(
                                sqlMapper.dependencies2(
                                        Map.of("tenant", report.tenantId(), "keys", keys)),
                                row ->
                                        new Claim(
                                                row.getString("invoice_key"),
                                                row.getString("invoice_id"),
                                                row.getString("report_id"),
                                                row.getInt("round_no"),
                                                row.getInt("line_no"),
                                                row.getString("status")));
        var originalValues =
                originals.findAll(report.tenantId(), loaded.invoices().keySet()).values().stream()
                        .sorted(Comparator.comparing(value -> value.invoiceId().toString()))
                        .toList();
        return digest(
                json.write(
                        new Dependencies(
                                ExpensePrecheckResources.versions(loaded),
                                originalValues,
                                checks,
                                claims)));
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    /**
     * 单次执行私有的事实收集器，已读取事实只允许缩短解释期限。
     *
     * @author owlzhangfq@gmail.com
     */
    public static final class Capture {
        private final ExpensePolicyConfiguration.Snapshot policy;
        private final String dependencyDigest;
        private final Instant observedAt;
        private Instant validUntil;

        private Capture(ExpensePolicyConfiguration.Snapshot policy, String dependencyDigest, Instant observedAt) {
            this.policy = policy; this.dependencyDigest = dependencyDigest; this.observedAt = observedAt;
            this.validUntil = observedAt.plusSeconds(ExpensePrecheckObservation.MAX_VALIDITY_SECONDS);
        }

        ExpensePolicyConfiguration.Snapshot policy() { return policy; }

        Instant validUntil() { return validUntil; }

        void limit(Instant expiry) { if (expiry.isBefore(validUntil)) validUntil = expiry; }

        ExpensePrecheckObservation observation() { return new ExpensePrecheckObservation(observedAt, validUntil, policy.selection(), dependencyDigest); }
    }

    /**
     * 稳定顺序的内部摘要材料，不保存正文副本也不作为模型来源。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Dependencies(
            List<ExpensePrecheckEvidence.ResourceVersion> resources,
            List<InvoiceOriginal> originals,
            List<CheckRevision> checks,
            List<Claim> claims) {}

    /**
     * 排队和失败同样改变查验修订，即使发票本身没有升级也会使解释失效。
     *
     * @author owlzhangfq@gmail.com
     */
    private record CheckRevision(String invoiceId, long attempts, long revisions) {}

    /**
     * 同票号的其他文件或采购占用也属于依赖，摘要之外不外发这些归属。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Claim(
            String key,
            String invoiceId,
            String reportId,
            int roundNo,
            int lineNo,
            String status) {}
}
