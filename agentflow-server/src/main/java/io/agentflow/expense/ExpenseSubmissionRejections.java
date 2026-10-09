package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseSubmissionRejectionsMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 原提交回滚后记录最小拒绝事实，不保存发票内容、账户或原始幂等键。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSubmissionRejections {
    private static final String OCCUPIED = "INVOICE_OCCUPIED";
    private final ExpenseSubmissionRejectionsMapper sqlMapper;
    private final JdbcExpensePrecheckRepository prechecks;
    private final CurrentActor actors;
    private final JsonUtil json;

    /** 沿用原预检的不可变身份，不在失败之后重新判断票据是否被占用。 */
    public ExpenseSubmissionRejections(
            ExpenseSubmissionRejectionsMapper sqlMapper,
            JdbcExpensePrecheckRepository prechecks,
            CurrentActor actors,
            JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.prechecks = prechecks;
        this.actors = actors;
        this.json = json;
    }

    /** 必须在原业务事务结束后调用；单条自动提交写入使唯一冲突不会污染调用方事务。 */
    @Transactional(propagation = Propagation.NEVER)
    public void record(
            UUID reportId,
            ExpenseSubmissionService.Input request,
            String key,
            DomainException failure) {
        if (!InvoiceOccupationConflict.matches(failure)) return;
        var actor = actors.actor();
        var checked =
                prechecks
                        .find(actor.tenantId(), request.precheckId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Original rejected precheck is unavailable"));
        var input = checked.input();
        // 回滚之后仍只接受刚才提交所引用的原预检身份和版本，不读取后来修改的草稿。
        if (!input.reportId().equals(reportId)
                || !input.employeeId().equals(actor.userId())
                || input.applicationVersion() != request.applicationVersion()
                || input.financialVersion() != request.financialVersion()
                || checked.status() != ExpensePrecheckJob.Status.READY)
            throw new IllegalStateException("Original rejected precheck identity differs");
        String digest = digest(json.write(new Attempt(reportId, actor.userId(), request, key)));
        try {
            sqlMapper.record(
                    actor.tenantId(),
                    digest,
                    reportId.toString(),
                    input.applicationId().toString(),
                    actor.userId(),
                    input.id().toString(),
                    input.applicationVersion(),
                    input.financialVersion(),
                    input.roundNo(),
                    input.initiator().legalEntityId().toString(),
                    input.initiator().departmentId().toString(),
                    OCCUPIED,
                    failure.code(),
                    actor.tenantId(),
                    digest);
        } catch (DuplicateKeyException concurrent) {
            // 相同身份、内容与原键的并发拒绝已由另一请求记录，不能再次累计。
        }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    /**
     * 固定字段顺序和规范化 DTO 保持重启后的同键识别，原始键仅参与内存摘要。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Attempt(
            UUID reportId, String actorId, ExpenseSubmissionService.Input input, String key) {}
}
