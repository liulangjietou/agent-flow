package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在事务外读取本人当前目录，按原预览有效期重建精确内容，发送前与人工确认前均可复核。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseDraftAssistPreparation {
    private final ExpenseDraftAssistService service;
    private final FinanceMasterDataPort catalogs;
    private final FinanceGatewayConfiguration finance;
    private final AssistConfiguration model;
    private final ExpenseDraftAssistInputs inputs;
    private final JsonUtil json;

    /** 目录走既有可信财务端口，调用方只能提交行程及目录代码。 */
    public ExpenseDraftAssistPreparation(ExpenseDraftAssistService service, FinanceMasterDataPort catalogs,
            FinanceGatewayConfiguration finance, AssistConfiguration model, ExpenseDraftAssistInputs inputs, JsonUtil json) {
        this.service = service; this.catalogs = catalogs; this.finance = finance; this.model = model; this.inputs = inputs; this.json = json;
    }

    /** 首次预览使用真实目录有效期；排队必须携带原预览有效期，不能静默延长。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(UUID reportId, Request request, Instant validUntil) {
        return prepare(service.editable(reportId, request.applicationVersion(), request.financialVersion()), request, validUntil);
    }

    /** 重新读取原本人目录，来源权限、名称或版本变化均要求重新生成。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared refresh(ExpenseDraftAssistRun.Context context) {
        var input = context.input(); var options = input.options();
        String brief = json.read(input.sources().stream().filter(source -> source.reference().sourceId().equals(ExpenseDraftAssistInput.BRIEF))
                .findFirst().orElseThrow().content(), String.class);
        var selection = new ExpenseDraftAssistInputs.CatalogSelection(options.categories().stream().map(value -> value.code()).toList(),
                options.costCenters().stream().map(value -> value.code()).toList(), options.projects().stream().map(value -> value.code()).toList());
        var request = new Request(input.applicationVersion(), input.financialVersion(), brief, input.itinerary(), selection);
        var prepared = prepare(service.sources(context), request, input.validUntil());
        if (!prepared.input().equals(input) || !prepared.targetDigest().equals(context.targetDigest())) throw changed();
        return prepared;
    }

    private Prepared prepare(ExpenseDraftAssistService.Snapshot snapshot, Request request, Instant originalExpiry) {
        String tenant = snapshot.report().tenantId(), employee = snapshot.report().employeeId();
        model.requireAvailable(); String modelTarget = model.targetDigest(ExpenseDraftAssistRun.PROMPT_VERSION);
        String provider = model.getProviderId(), modelName = model.getModel(), destination = model.uri().getAuthority();
        String financeTarget = destination(tenant); var catalog = catalogs.catalog(tenant, employee).requireValue();
        Instant now = Instant.now(); Instant expiry = originalExpiry == null ? catalog.validUntil() : originalExpiry;
        if (!expiry.isAfter(now) || expiry.isAfter(catalog.validUntil()) || !financeTarget.equals(destination(tenant))) throw changed();
        model.requireAvailable();
        if (!modelTarget.equals(model.targetDigest(ExpenseDraftAssistRun.PROMPT_VERSION))) throw new DomainException("AGENT_TARGET_CHANGED", "Expense draft model destination changed during preparation");
        var value = inputs.select(snapshot.application(), snapshot.report(), catalog, financeTarget, request.brief(), request.itinerary(), request.catalog());
        var frozen = new ExpenseDraftAssistInput(value.reportId(), value.applicationId(), value.applicationVersion(), value.financialVersion(),
                value.legalEntityId(), value.reportType(), value.catalogVersion(), expiry, value.financeTargetDigest(), value.itinerary(), value.options(), value.sources());
        String consent = AssistConfiguration.digest(json.write(new Consent(tenant, employee, frozen, modelTarget)));
        return new Prepared(tenant, employee, frozen, modelTarget, consent, provider, modelName, destination);
    }
    private String destination(String tenant) {
        return finance.destination(tenant).orElseThrow(() -> new DomainException("FINANCE_GATEWAY_UNAVAILABLE", "NOT_CONFIGURED")).digest(tenant);
    }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Refresh the authorized expense draft catalog and sources"); }

    /**
     * 本人行程与所选代码，原始模型正文始终由服务端投影。
     * @author owlzhangfq@gmail.com
     */
    public record Request(long applicationVersion, long financialVersion, String brief,
            List<ExpenseDraftAssistInput.Leg> itinerary, ExpenseDraftAssistInputs.CatalogSelection catalog) { }
    /**
     * 事务外准备结果只在服务器内部传递，不能作为 HTTP 请求接收。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(String tenantId, String requestedBy, ExpenseDraftAssistInput input, String targetDigest, String consentDigest,
                           String providerId, String model, String destination) {
        /** 只展示本人实际发送内容及目的地，不返回凭据或内部目录响应。 */
        public Preview preview() { return new Preview(input, providerId, model, destination, targetDigest, consentDigest); }
    }
    /**
     * 原有效期与摘要由排队请求回传；内容发生变化必须再次展示并确认。
     * @author owlzhangfq@gmail.com
     */
    public record Preview(ExpenseDraftAssistInput input, String providerId, String model, String destination, String targetDigest, String consentDigest) { }
    /**
     * 固定字段序列绑定身份、原有效期、完整发送内容和实际模型目的地。
     * @author owlzhangfq@gmail.com
     */
    private record Consent(String tenantId, String requestedBy, ExpenseDraftAssistInput input, String targetDigest) { }
}
