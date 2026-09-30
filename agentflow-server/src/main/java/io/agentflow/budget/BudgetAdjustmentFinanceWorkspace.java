package io.agentflow.budget;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原预算批准、财务台账复核和实际执行分别投影，完整命令和目标摘要留在服务端。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentFinanceWorkspace {
    private final CurrentActor actors;
    private final BudgetAdjustmentFinanceAccess access;
    private final BudgetAdjustmentRepository requests;
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final FinanceGatewayConfiguration configuration;
    /** 同一快照生成状态和操作提示，真正执行仍重新检查当前权限及版本。 */
    public BudgetAdjustmentFinanceWorkspace(CurrentActor actors, BudgetAdjustmentFinanceAccess access, BudgetAdjustmentRepository requests,
            JdbcBudgetAdjustmentReviewRepository reviews, JdbcBudgetAdjustmentOperationRepository operations, FinanceGatewayConfiguration configuration) {
        this.actors = actors; this.access = access; this.requests = requests; this.reviews = reviews; this.operations = operations; this.configuration = configuration;
    }
    /** 待授权台账只向发起该读取且当前有权办理的财务展示，参与人读取已授权执行事实。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID requestId, Map<String, String> parameters) {
        if (!Set.of("roundNo", "operationId").containsAll(parameters.keySet())) throw invalid();
        var context = access.read(requestId, round(parameters)); var view = context.view(); var actor = actors.actor(); var now = Instant.now();
        var request = requests.find(actor.tenantId(), requestId).orElseThrow(BudgetAdjustmentFinanceWorkspace::notFound);
        var operation = parameters.containsKey("operationId") ? operations.find(actor.tenantId(), uuid(parameters.get("operationId"))).orElseThrow(BudgetAdjustmentFinanceWorkspace::notFound)
                : operations.latestForRequest(actor.tenantId(), requestId).filter(value -> value.command().source().round().roundNo() == view.roundNo()).orElse(null);
        if (operation != null && (!operation.command().source().requestId().equals(requestId) || operation.command().source().round().roundNo() != view.roundNo())) throw notFound();
        var active = operations.activeForRequest(actor.tenantId(), requestId).orElse(null);
        var review = context.finance() ? reviews.latest(actor.tenantId(), requestId, actor.userId())
                .filter(value -> value.input().source().round().roundNo() == view.roundNo()).orElse(null) : null;
        var retirement = operation == null ? null : operations.retirement(actor.tenantId(), operation.command().id()).orElse(null);
        boolean destinationReady = request.approval() != null && configuration.destination(actor.tenantId())
                .map(target -> target.digest(actor.tenantId()).equals(request.currentRound().targetDigest())).orElse(false);
        boolean currentApproval = context.finance() && destinationReady && view.status() == ApplicationStatus.APPROVED && view.approval() != null
                && view.approval().applicationVersion() == view.applicationVersion();
        boolean approved = currentApproval && active == null;
        boolean actionable = context.finance() && operation != null && retirement == null;
        return new View(requestId, view.applicationId(), view.roundNo(), view.applicationVersion(), view.requestVersion(), destinationReady,
                review == null ? null : new ReviewView(review.input().id(), review.version(), review.status(), review.input().requestedAt(), review.checkedAt(),
                        deadline(review), review.ledger() == null ? List.of() : BudgetAdjustmentRoundView.positions(review.input().source().round().content(), review.ledger()), review.issue()),
                operation == null ? null : project(operation),
                new Actions(approved && (review == null || !review.active()), approved && review != null && review.usable(now)
                        && review.input().source().approvedRequestVersion() == view.requestVersion(),
                        actionable && operation.attempts() > 0 && !operation.running() && operation.status() != BudgetAdjustmentOperation.Status.QUEUED,
                        actionable && currentApproval && actor.userId().equals(operation.command().authorizedBy())
                                && operation.status() == BudgetAdjustmentOperation.Status.NOT_FOUND && operation.conflictingObservation() == null && now.isBefore(operation.command().expiresAt()),
                        actionable && operation.safelyUnexecuted()));
    }
    /** 历史有界分页仍按原申请和轮次授权；游标不能定位别人的财务执行。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page history(UUID requestId, Map<String, String> parameters) {
        if (!Set.of("roundNo", "before", "limit").containsAll(parameters.keySet())) throw invalid();
        var context = access.read(requestId, round(parameters));
        int limit = parameters.containsKey("limit") ? positive(parameters.get("limit")) : 20; if (limit > 50) throw invalid();
        UUID before = parameters.containsKey("before") ? uuid(parameters.get("before")) : null;
        var values = operations.list(actors.actor().tenantId(), requestId, before, limit + 1)
                .stream().filter(value -> value.command().source().round().roundNo() == context.view().roundNo()).toList();
        var items = values.stream().limit(limit).map(this::project).toList();
        return new Page(items, values.size() > limit ? items.get(items.size() - 1).id() : null);
    }
    private OperationView project(BudgetAdjustmentOperation value) {
        var command = value.command(); var retirement = operations.retirement(command.tenantId(), command.id()).orElse(null);
        return new OperationView(command.id(), value.version(), value.status(), command.authorizedBy(), command.reason(), command.authorizedAt(), command.expiresAt(),
                value.updatedAt(), BudgetAdjustmentRoundView.positions(command.source().round().content(), command.ledger()),
                result(value.observation()), result(value.conflictingObservation()), value.failure(), retirement);
    }
    private static ResultView result(BudgetAdjustmentObservation value) {
        return value == null ? null : new ResultView(value.status(), value.revision(), value.observedAt(), value.reference(), value.appliedAt(), value.rejection());
    }
    private static Instant deadline(BudgetAdjustmentReview review) {
        if (review.ledger() == null) return null;
        var observedLimit = review.ledger().observedAt().plus(BudgetLedgerPort.MAX_EVIDENCE_AGE);
        return review.ledger().validUntil().isBefore(observedLimit) ? review.ledger().validUntil() : observedLimit;
    }
    private static Integer round(Map<String, String> values) { return values.containsKey("roundNo") ? positive(values.get("roundNo")) : null; }
    private static int positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) throw invalid();
        try { return Integer.parseInt(value); } catch (NumberFormatException failed) { throw invalid(); }
    }
    private static UUID uuid(String value) {
        try { var id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
        catch (IllegalArgumentException failed) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_ADJUSTMENT_QUERY", "Budget execution accepts only a positive round, valid identity cursor and bounded page size"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original budget adjustment operation was not found in the selected round"); }
    /**
     * 当前批准、本人待授权复核及原指令分别展示，不展开内部目的地或摘要。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID requestId, UUID applicationId, int roundNo, long applicationVersion, long requestVersion, boolean destinationReady,
                       ReviewView review, OperationView operation, Actions actions) { }
    /**
     * 当前财务的原台账证据，金额全部来自服务端校验。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReviewView(UUID id, long version, BudgetAdjustmentReview.Status status, Instant requestedAt, Instant checkedAt, Instant validUntil,
                             List<BudgetAdjustmentRoundView.PositionView> positions, BudgetAdjustmentReview.Issue issue) { }
    /**
     * 原授权金额与外部结果保留区别，未知或核对不展示为已经生效。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OperationView(UUID id, long version, BudgetAdjustmentOperation.Status status, String authorizedBy, String reason,
                                Instant authorizedAt, Instant expiresAt, Instant updatedAt, List<BudgetAdjustmentRoundView.PositionView> positions,
                                ResultView observation, ResultView conflictingObservation, BudgetAdjustmentOperation.Failure failure, BudgetAdjustmentRetirement retirement) { }
    /**
     * 只有通过完整双端校验的原子结果才可能标记 APPLIED。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResultView(BudgetAdjustmentObservation.Status status, long revision, Instant observedAt, String reference, Instant appliedAt,
                             BudgetAdjustmentObservation.Rejection rejection) { }
    /**
     * 动作提示不代替实际办理时的原版本、角色、任职及字段权限核验。
     * @author owlzhangfq@gmail.com
     */
    public record Actions(boolean review, boolean authorize, boolean query, boolean retry, boolean retire) { }
    /**
     * 每页最多五十项，当前执行事实通过原编号再次读取。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<OperationView> items, UUID nextBefore) { }
}
