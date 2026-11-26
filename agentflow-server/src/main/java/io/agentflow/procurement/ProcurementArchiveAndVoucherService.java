package io.agentflow.procurement;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.JsonUtil;
import io.agentflow.common.SystemRole;
import io.agentflow.finance.Money;
import io.agentflow.procurement.mapper.SupplierPaymentAuthorizationRepositoryMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 采购付款的财务对称能力：已批准单据 ERP 凭证草稿派生、不可变电子归档 ZIP 生成以及支付授权前的财务撤销。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcurementArchiveAndVoucherService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcurementArchiveAndVoucherService.class);
    private final ProcurementPaymentService payments;
    private final ProcurementPaymentRepository requests;
    private final ApprovalApplicationFacade applications;
    private final ProcurementPayableReservations reservations;
    private final SupplierPaymentAuthorizationRepositoryMapper authorizations;
    private final CurrentActor currentActor;
    private final JsonUtil json;

    /** 构造采购付款归档与凭证服务。 */
    public ProcurementArchiveAndVoucherService(
            ProcurementPaymentService payments,
            ProcurementPaymentRepository requests,
            ApprovalApplicationFacade applications,
            ProcurementPayableReservations reservations,
            SupplierPaymentAuthorizationRepositoryMapper authorizations,
            CurrentActor currentActor,
            JsonUtil json) {
        this.payments = payments;
        this.requests = requests;
        this.applications = applications;
        this.reservations = reservations;
        this.authorizations = authorizations;
        this.currentActor = currentActor;
        this.json = json;
    }

    /** 为已批准采购付款派生借贷平衡的 ERP 凭证草稿，复用原申请与字段权限读取边界。 */
    @Transactional(readOnly = true)
    public VoucherDraft voucherDraft(UUID id, Integer roundNo) {
        var view = payments.read(id, roundNo);
        if (view.status() != ApplicationStatus.APPROVED || view.approval() == null || view.financialRound() == null) {
            throw new DomainException(ErrorCode.ARCHIVE_NOT_READY,
                    "Approved procurement payment round is required for ERP voucher draft");
        }
        var round = view.financialRound();
        var content = view.content();
        var payable = round.payable();
        Money amount = content.amount();
        LocalDate accountingDate = LocalDate.ofInstant(
                view.approval().approvedAt(), ZoneId.of(round.legalEntity().timeZone()));
        List<VoucherLine> lines = List.of(
                new VoucherLine(1, "SUPPLIER_PAYABLE", "DEBIT", amount.value().toPlainString(), amount.currency(),
                        content.supplierReference(), content.payableReference(), payable.accrualVoucherReference()),
                new VoucherLine(2, "BANK_CLEARING", "CREDIT", amount.value().toPlainString(), amount.currency(),
                        content.supplierReference(), content.payableReference(), payable.matchingReference()));
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("procurementPaymentId", view.id());
        basis.put("applicationId", view.applicationId());
        basis.put("roundNo", view.roundNo());
        basis.put("applicationVersion", view.applicationVersion());
        basis.put("requestVersion", view.requestVersion());
        basis.put("legalEntityId", content.legalEntityId());
        basis.put("supplierReference", content.supplierReference());
        basis.put("payableReference", content.payableReference());
        basis.put("amount", amount.value().toPlainString());
        basis.put("currency", amount.currency());
        basis.put("accountingDate", accountingDate.toString());
        String basisDigest = sha256Hex(json.write(basis).getBytes(StandardCharsets.UTF_8));
        return new VoucherDraft(
                view.id(), view.applicationId(), view.businessNo(), view.roundNo(),
                content.legalEntityId(), accountingDate, amount.value().toPlainString(), amount.currency(),
                content.supplierReference(), payable.supplierName(), content.payableReference(),
                payable.accrualVoucherReference(), lines, basisDigest);
    }

    /** 生成包含冻结单据快照、ERP 凭证草稿与离线 SHA-256 校验清单的不可变 ZIP 归档包。 */
    @Transactional(readOnly = true)
    public ArchiveSnapshot prepareArchive(UUID id, Integer roundNo) {
        var view = payments.read(id, roundNo);
        var draft = voucherDraft(id, roundNo);
        return new ArchiveSnapshot(view, draft, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 流式写出不可变 ZIP 归档字节，包含逐文件 SHA-256 校验清单。 */
    public void writeArchiveZip(ArchiveSnapshot snapshot, OutputStream output) throws IOException {
        byte[] paymentBytes = json.write(snapshot.view()).getBytes(StandardCharsets.UTF_8);
        byte[] voucherBytes = json.write(snapshot.voucherDraft()).getBytes(StandardCharsets.UTF_8);
        String paymentSha = sha256Hex(paymentBytes);
        String voucherSha = sha256Hex(voucherBytes);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "agentflow-procurement-archive-v1");
        manifest.put("procurementPaymentId", snapshot.view().id());
        manifest.put("applicationId", snapshot.view().applicationId());
        manifest.put("businessNo", snapshot.view().businessNo());
        manifest.put("roundNo", snapshot.view().roundNo());
        manifest.put("approvedBy", snapshot.view().approval().approvedBy());
        manifest.put("approvedAt", snapshot.view().approval().approvedAt());
        manifest.put("voucherDigest", snapshot.voucherDraft().basisDigest());
        manifest.put("generatedAt", snapshot.generatedAt());
        manifest.put("files", Map.of(
                "procurement-payment.json", paymentSha,
                "voucher-draft.json", voucherSha));
        byte[] manifestBytes = json.write(manifest).getBytes(StandardCharsets.UTF_8);
        String manifestSha = sha256Hex(manifestBytes);

        String checksums = manifestSha + "  manifest.json\n"
                + paymentSha + "  procurement-payment.json\n"
                + voucherSha + "  voucher-draft.json\n";
        byte[] checksumBytes = checksums.getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addEntry(zip, "manifest.json", manifestBytes);
            addEntry(zip, "procurement-payment.json", paymentBytes);
            addEntry(zip, "voucher-draft.json", voucherBytes);
            addEntry(zip, "sha256sums.txt", checksumBytes);
        }
    }

    /** 财务角色撤销尚未发起付款授权的已批准采购付款申请，并同事务释放应付占用。 */
    @Transactional
    public ProcurementPaymentService.Receipt revokeApproved(UUID id, long applicationVersion, long requestVersion, String comment) {
        var actor = currentActor.actor();
        actor.requireRole(SystemRole.FINANCE);
        if (StringUtils.isBlank(comment)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "A revocation reason is required");
        }
        var payment = requests.find(actor.tenantId(), id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Procurement payment not found"));
        var application = applications.get(payment.applicationId());
        requests.lock(actor.tenantId(), id);
        var lockedPayment = requests.find(actor.tenantId(), id)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Procurement payment not found"));
        if (lockedPayment.version() != requestVersion) {
            throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "Procurement payment version changed");
        }
        if (!authorizations.forRequest(actor.tenantId(), id.toString()).isEmpty()) {
            throw new DomainException(ErrorCode.SUPPLIER_PAYMENT_ALREADY_AUTHORIZED,
                    "Cannot revoke procurement payment after supplier payment authorization has started");
        }
        var revoked = applications.revokeBusiness(
                application.id(), applicationVersion, comment.trim(), application.businessReference());
        reservations.releaseStopped(revoked, actor.userId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        LOG.info("Approved procurement payment revoked by finance, tenantId={}, procurementId={}", actor.tenantId(), id);
        return ProcurementPaymentService.receipt(revoked, lockedPayment);
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 采购付款已批准单据的借贷平衡 ERP 凭证草稿。
     * @author owlzhangfq@gmail.com
     */
    public record VoucherDraft(
            UUID procurementPaymentId,
            UUID applicationId,
            String businessNo,
            int roundNo,
            UUID legalEntityId,
            LocalDate accountingDate,
            String amount,
            String currency,
            String supplierReference,
            String supplierName,
            String payableReference,
            String accrualVoucherReference,
            List<VoucherLine> lines,
            String basisDigest) { }

    /**
     * ERP 凭证借贷分录行。
     * @author owlzhangfq@gmail.com
     */
    public record VoucherLine(
            int lineNo,
            String role,
            String side,
            String amount,
            String currency,
            String supplierReference,
            String payableReference,
            String sourceReference) { }

    /**
     * 归档快照（在流式写 ZIP 前于事务内固定）。
     * @author owlzhangfq@gmail.com
     */
    public record ArchiveSnapshot(
            ProcurementPaymentService.View view,
            VoucherDraft voucherDraft,
            Instant generatedAt) { }
}
