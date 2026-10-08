package io.agentflow.expense;

import io.agentflow.expense.mapper.AdvanceOverdueRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 到期索引只缩小候选范围；未还余额始终由最新借款实体计算。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceOverdueRepository {
    public static final int BATCH_SIZE = 100;
    private final AdvanceOverdueRepositoryMapper sqlMapper;

    /** 复用财务事务数据源，提醒和原余额的并发更新可按同一行排序。 */
    public JdbcAdvanceOverdueRepository(AdvanceOverdueRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 新借款检查只遍历本人本法人，不按币种忽略旧借款。 */
    public List<Candidate> ownedDueBefore(
            String tenant, String employee, UUID entity, LocalDate date, Candidate after) {
        var parameters = new ArrayList<Object>(List.of(tenant, employee, entity.toString(), date));
        String cursor = "";
        if (after != null) {
            cursor = " AND (p.due_on>? OR (p.due_on=? AND p.advance_id>?))";
            parameters.add(after.dueOn());
            parameters.add(after.dueOn());
            parameters.add(after.id().toString());
        }
        return restoreCandidates(
                sqlMapper.ownedDueBeforeRows((after != null), parameters.toArray()));
    }

    /** 使用最早进入次日的合法偏移筛候选，真正日期仍按原借款法人时区复核。 */
    public List<Candidate> candidates(Instant now, Candidate after) {
        var parameters = new ArrayList<Object>(List.of(LocalDate.ofInstant(now, ZoneOffset.MAX)));
        String cursor = "";
        if (after != null) {
            cursor =
                    " AND (p.due_on>? OR (p.due_on=? AND (p.tenant_id>? OR (p.tenant_id=? AND"
                        + " p.advance_id>?))))";
            parameters.add(after.dueOn());
            parameters.add(after.dueOn());
            parameters.add(after.tenantId());
            parameters.add(after.tenantId());
            parameters.add(after.id().toString());
        }
        // 已登记的原放款持续独占该借款；与还款来源使用相同授权绑定，不借申请当前轮次。
        return restoreCandidates(sqlMapper.candidatesRows((after != null), parameters.toArray()));
    }

    /** 余额行锁使还款、冲销和重复扫描不会在本次提醒落库之间改写依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Candidate candidate) {
        sqlMapper.lock(candidate.tenantId(), candidate.id().toString());
    }

    /** 每笔不可变归还约定只生成一次提醒，已读和重启均不重置资格。 */
    public boolean recorded(Candidate candidate) {
        return SqlRows.single(sqlMapper.recorded(candidate.tenantId(), candidate.id().toString()))
                > 0;
    }

    /** 仅在同一事务内已有站内消息后保存可追溯财务依据。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            EmployeeAdvance advance, UUID inboxId, LocalDate date, String zone, Instant now) {
        sqlMapper.record(
                advance.tenantId(),
                advance.id().toString(),
                inboxId.toString(),
                advance.employeeId(),
                advance.version(),
                date,
                zone,
                advance.outstanding().value(),
                advance.outstanding().currency(),
                Timestamp.from(now));
    }

    private List<Candidate> restoreCandidates(List<SqlRow> rows) {
        return SqlRows.map(
                rows,
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("advance_id")),
                                row.getDate("due_on").toLocalDate(),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /**
     * 内部游标只从实际查询结果生成，不接受外部传入的归属或日期。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID id,
            LocalDate dueOn,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧扫描不推测业务关联，也不借用调用线程的实例或任务。 */
        public Candidate(String tenantId, UUID id, LocalDate dueOn, String traceId) { this(tenantId, id, dueOn, traceId, null, null); }

        /** 旧索引没有创建来源，不从扫描线程补写。 */
        public Candidate(String tenantId, UUID id, LocalDate dueOn) { this(tenantId, id, dueOn, null); }
    }
}
