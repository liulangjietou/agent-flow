package io.agentflow.agent;

import io.agentflow.agent.mapper.AssistRunRepositoryMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 保留不可变输入上下文、模型原文和人工修订；状态变更与追加记录使用同一事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAssistRunRepository implements AssistRunRepository {
    private final AssistRunRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 注入审批平台数据源与统一 JSON 编解码器，不依赖模型供应商。 */
    public JdbcAssistRunRepository(AssistRunRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    @Override
    @Transactional
    public void create(AssistRun run) {
        if (run.status() != AssistRun.Status.QUEUED || run.version() != 1) throw conflict();
        int inserted =
                sqlMapper.create(
                        run.id().toString(),
                        run.status().name(),
                        json.write(context(run)),
                        json.write(state(run)),
                        Timestamp.from(run.createdAt()),
                        run.tenantId(),
                        run.input().applicationId().toString(),
                        run.input().applicationVersion(),
                        run.input().roundNo());
        if (inserted != 1)
            throw new DomainException(
                    "AGENT_INPUT_CHANGED", "Authorized application context is no longer current");
        append(run);
    }

    @Override
    @Transactional
    public void update(AssistRun run, long expectedVersion) {
        if (run.version() != expectedVersion + 1 || run.status() == AssistRun.Status.QUEUED)
            throw conflict();
        if (run.status() == AssistRun.Status.ADOPTED) {
            // 锁定源申请直到本次采纳与追加记录提交，关闭版本检查之后申请被并发修改的窗口。
            var locked =
                    sqlMapper.update(
                            run.tenantId(),
                            run.input().applicationId().toString(),
                            run.input().applicationVersion(),
                            run.input().roundNo());
            if (CollectionUtils.isEmpty(locked))
                throw new DomainException(
                        "AGENT_INPUT_CHANGED", "Application changed before summary adoption");
        }
        String previous =
                switch (run.status()) {
                    case RUNNING -> "QUEUED";
                    case COMPLETED, FAILED -> "RUNNING";
                    case ADOPTED, DISMISSED -> "COMPLETED";
                    case QUEUED -> throw conflict();
                };
        // context_json 只作完整不可变上下文的等值比较，不能由一次状态更新替换原租户/申请/输入。

        int updated =
                sqlMapper.updateQuery(
                        new Object[] {
                            run.status().name(),
                            run.version(),
                            json.write(state(run)),
                            run.tenantId(),
                            run.id().toString(),
                            expectedVersion,
                            previous,
                            json.write(context(run))
                        });
        if (updated != 1) throw conflict();
        append(run);
    }

    @Override
    public Optional<AssistRun> find(String tenantId, UUID runId) {
        return SqlRows.map(sqlMapper.find(tenantId, runId.toString()), this::restore).stream()
                .findFirst();
    }

    private void append(AssistRun run) {
        sqlMapper.append(
                run.tenantId(),
                run.id().toString(),
                run.version(),
                run.status().name(),
                json.write(state(run)));
    }

    private AssistRun restore(SqlRow row) {
        Context context = json.read(row.getString("context_json"), Context.class);
        State state = json.read(row.getString("state_json"), State.class);
        if (!context.input().applicationId().toString().equals(row.getString("application_id"))
                || context.input().applicationVersion() != row.getLong("application_version")
                || context.input().roundNo() != row.getInt("round_no")) throw corrupt();
        AssistRun run = AssistRun.queue(UUID.fromString(row.getString("id")), row.getString("tenant_id"), context.requestedBy(),
                context.createdAt(), context.input(), context.promptVersion());
        // 用已持久化的精确时刻重放状态行为，保留纳秒，不因 SQL 时间精度损失改变原上下文。
        if (state.startedAt() != null) run.start(run.version(), state.startedAt());
        if (state.suggestion() != null) run.complete(run.version(), state.suggestion(), state.completedAt());
        if (state.failure() != null) run.fail(run.version(), state.failure(), state.completedAt());
        if (state.review() != null) {
            var review = state.review();
            if (review.acceptedText() == null) run.dismiss(run.version(), review.reviewer(), review.comment(), review.reviewedAt());
            else run.adopt(run.version(), context.input().applicationVersion(), review.reviewer(), review.acceptedText(), review.comment(), review.reviewedAt());
        }
        if (!run.status().name().equals(row.getString("status")) || run.version() != row.getLong("version")
                || !state(run).equals(state)) throw corrupt();
        return run;
    }

    private static Context context(AssistRun run) { return new Context(run.requestedBy(), run.createdAt(), run.input(), run.promptVersion()); }

    private static State state(AssistRun run) { return new State(run.startedAt(), run.completedAt(), run.suggestion(), run.failure(), run.review()); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Assist run or application context has changed"); }

    private static IllegalStateException corrupt() { return new IllegalStateException("Persisted assist run is inconsistent"); }

    /**
     * 不可变运行上下文，包含精确创建时刻和已授权的输入引用。
     *
     * @author owlzhangfq@gmail.com
     */
    private record Context(
            String requestedBy, Instant createdAt, AssistInput input, String promptVersion) {}

    /**
     * 可重放的运行状态，原模型建议与人工采纳值使用不同字段。
     *
     * @author owlzhangfq@gmail.com
     */
    private record State(
            Instant startedAt,
            Instant completedAt,
            AssistSuggestion suggestion,
            AssistRun.Failure failure,
            AssistRun.Review review) {}
}
