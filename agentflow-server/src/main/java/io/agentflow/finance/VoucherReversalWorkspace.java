package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 完整字段授权后投影原凭证与独立反向分录，候选仅提供给仍具任职资格的原查询财务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalWorkspace {
    private final CurrentActor actors;
    private final VoucherAccess access;
    private final VoucherReversalSources sources;
    private final PaymentPersonnel personnel;
    private final JdbcVoucherReversalCheckRepository checks;
    private final JdbcVoucherReversalRecordRepository records;
    private final VoucherReversalService service;
    /** 页面不接触财务目标和包含银行信息的完整原命令。 */
    public VoucherReversalWorkspace(CurrentActor actors, VoucherAccess access, VoucherReversalSources sources, PaymentPersonnel personnel,
            JdbcVoucherReversalCheckRepository checks, JdbcVoucherReversalRecordRepository records, VoucherReversalService service) {
        this.actors = actors; this.access = access; this.sources = sources; this.personnel = personnel; this.checks = checks; this.records = records; this.service = service;
    }
    /** 读取只展示已有事实，不创建查询、不续期也不自动采纳凭证。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID applicationId, UUID operationId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid();
        Integer round = null;
        if (parameters.containsKey("roundNo")) { var raw = parameters.get("roundNo"); if (!raw.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(raw); }
        var context = access.read(applicationId, round); var source = sources.find(context, operationId); var command = source.request().command(); var actor = actors.actor();
        boolean finance = context.finance() && VoucherDisputeResolution.independent(command, actor.userId()) && personnel.eligible(actor.tenantId(), actor.userId(), command.legalEntityId());
        var check = finance ? checks.latest(actor.tenantId(), operationId, actor.userId()).orElse(null) : null;
        var record = records.forOperation(actor.tenantId(), operationId).orElse(null); var original = source.request().original();
        return new View(applicationId, operationId, context.roundNo(), context.application().version(), context.businessVersion(), source.current().version(), command.kind(), source.current().status(),
                new Original(original.postingReference(), original.voucherReference(), original.periodReference(), original.accountingDate(), original.debitTotal(), original.postedAt()),
                finance && record == null && source.current().status() == VoucherOperation.Status.REVERSED && (check == null || !check.active()), check == null ? null : check(source, check),
                record == null ? null : new Recorded(record.id(), record.operationVersion(), record.receipt().reversal(), record.recordedBy(), record.recordedAt(), record.evidenceReference(), record.comment()));
    }
    private Check check(VoucherReversalSources.Source source, VoucherReversalCheck check) {
        var receipt = check.receipt(); var issue = service.confirmationIssue(source, check, Instant.now());
        return new Check(check.input().id(), check.version(), check.status(), check.input().requestedAt(), check.updatedAt(), check.issue() == null ? null : check.issue().name(),
                receipt == null ? null : new Evidence(receipt.status(), receipt.revision(), receipt.observedAt(), receipt.validUntil(), receipt.current() == null ? null : receipt.current().revision(),
                        receipt.current() == null ? null : receipt.current().status(), receipt.reversal()), issue == null, issue);
    }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_QUERY", "Only a positive roundNo is accepted for voucher reversal status"); }
    /**
     * 原件、候选和不可变登记分别展示，原凭证状态不等同于冲销已登记。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID applicationId, UUID operationId, int roundNo, long applicationVersion, long businessVersion, long operationVersion, VoucherCommand.Kind kind,
            VoucherOperation.Status originalStatus, Original original, boolean canQuery, Check latestCheck, Recorded record) { }
    /**
     * 最小原件身份及单边合计，避免公开完整的付款或科目映射命令。
     * @author owlzhangfq@gmail.com
     */
    public record Original(String postingReference, String voucherReference, String periodReference, LocalDate accountingDate, Money total, Instant postedAt) { }
    /**
     * 本人的最新查询及实时确认边界。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(UUID id, long version, VoucherReversalCheck.Status status, Instant requestedAt, Instant updatedAt, String issue, Evidence evidence, boolean canRecord, String confirmationIssue) { }
    /**
     * 完整反向分录可逐行审阅，未核清时为空。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(VoucherReversalPort.Status status, long revision, Instant observedAt, Instant validUntil, Long originalRevision, VoucherObservation.Status originalStatus, VoucherReversalPort.Posting reversal) { }
    /**
     * 已登记事实允许原申请人在字段权限内读取，不再受查询有效期限制。
     * @author owlzhangfq@gmail.com
     */
    public record Recorded(UUID id, long operationVersion, VoucherReversalPort.Posting reversal, String recordedBy, Instant recordedAt, String evidenceReference, String comment) { }
}
