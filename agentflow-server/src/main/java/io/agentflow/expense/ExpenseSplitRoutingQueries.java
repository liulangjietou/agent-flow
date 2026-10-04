package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原轮次路由依据的只读编排；业务计算权不等于操作者的跨单明细读取权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSplitRoutingQueries {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final SubmissionRoundRepository rounds;
    private final JdbcExpenseSplitRoutingRepository routing;

    /** 复用申请参与关系和原轮次字段投影，不另建管理员或财务角色豁免。 */
    public ExpenseSplitRoutingQueries(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            ApplicationFieldViews fields, SubmissionRoundRepository rounds, JdbcExpenseSplitRoutingRepository routing) {
        this.actors = actors; this.reports = reports; this.applications = applications;
        this.fields = fields; this.rounds = rounds; this.routing = routing;
    }

    /** 先授权本单，再检查每份来源；任一来源受限时连风险命中结果也不披露。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID reportId, int roundNo) {
        var actor = actors.actor();
        var primary = readable(actor, reportId, roundNo);
        var snapshot = routing.find(actor.tenantId(), reportId, roundNo).orElse(null);
        if (snapshot == null) return new View(reportId, primary.applicationId(), roundNo, Status.NOT_RECORDED, false, null);
        if (!snapshot.applicationId().equals(primary.applicationId()) || !snapshot.processKey().equals(primary.processKey())
                || snapshot.definitionVersion() != primary.definitionVersion()) throw inconsistent();
        for (var source : snapshot.sources()) {
            if (source.reportId().equals(reportId)) continue;
            try {
                var comparison = readable(actor, source.reportId(), source.roundNo());
                if (!comparison.applicationId().equals(source.applicationId())) throw inconsistent();
            } catch (DomainException denied) {
                if (!denied.code().equals("NOT_FOUND") && !denied.code().equals("FORBIDDEN")) throw denied;
                return new View(reportId, primary.applicationId(), roundNo, Status.RESTRICTED, false, null);
            }
        }
        var status = switch (snapshot.configuration().mode()) {
            case UNCONFIGURED -> Status.UNCONFIGURED;
            case DISABLED -> Status.DISABLED;
            case ENABLED -> snapshot.assessment().suspected() ? Status.SPLIT_SUSPECTED : Status.CLEAR;
        };
        return new View(reportId, primary.applicationId(), roundNo, status, true, snapshot);
    }

    private Readable readable(Actor actor, UUID reportId, int roundNo) {
        var report = reports.find(actor.tenantId(), reportId).orElseThrow(ExpenseSplitRoutingQueries::notFound);
        var application = applications.getForActor(actor, report.applicationId());
        if (report.rounds().stream().noneMatch(round -> round.roundNo() == roundNo)) throw notFound();
        var submitted = rounds.findByRound(actor.tenantId(), application.id(), roundNo).orElseThrow(ExpenseSplitRoutingQueries::notFound);
        var projection = fields.attachmentViewForActor(actor, application, roundNo);
        if (!ExpenseFormContract.detailsReadable(submitted.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Original expense details must remain fully readable");
        }
        return new Readable(application.id(), application.processKey(), submitted.definitionVersion());
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense submission round not found"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted split routing does not match the original submission"); }

    /**
     * 受限状态不泄露是否存在命中；历史无记录不能当作检查通过。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_RECORDED, UNCONFIGURED, DISABLED, CLEAR, SPLIT_SUSPECTED, RESTRICTED }

    /**
     * 只有所有原轮次明细都可读时才返回冻结正文，空正文也显式输出。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, Status status, boolean sourcesReadable,
                       ExpenseSplitRoutingSnapshot details) { }

    /**
     * 原提交的定义绑定仅在读取编排内使用，不从同名新版本借用权限。
     * @author owlzhangfq@gmail.com
     */
    private record Readable(UUID applicationId, String processKey, long definitionVersion) { }
}
