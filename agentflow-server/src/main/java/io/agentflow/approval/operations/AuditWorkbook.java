package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 追加审计摘要的文件表示，申请标题保留当前值且不推断缺失历史元数据。
 * @author owlzhangfq@gmail.com
 */
final class AuditWorkbook {
    private static final String[] HEADINGS = {"记录标识", "事件标识", "事件来源", "来源对象", "聚合版本", "动作代码", "操作人账号", "操作时间（UTC）", "关联申请标识", "业务单号", "申请当前标题"};
    private static final int[] WIDTHS = {38, 38, 18, 38, 14, 18, 22, 32, 38, 28, 46};

    private AuditWorkbook() { }

    /** 导出原始摘要字段，长版本号和标识均作为文本，空元数据不填造默认值。 */
    static byte[] write(List<AuditSearchPort.Item> items, Actor actor, AuditSearchPort.Query query, Instant startedAt) throws IOException {
        var values = items.stream().map(item -> new String[]{item.id().toString(), item.eventId(), item.source(), item.aggregateId(),
                Long.toString(item.aggregateVersion()), item.action(), item.actor(), item.occurredAt().toString(),
                item.applicationId() == null ? null : item.applicationId().toString(), item.businessNo(), item.currentTitle()}).toList();
        String[][] metadata = {{"租户", actor.tenantId()}, {"导出账号", actor.userId()}, {"生成开始时间（UTC）", startedAt.toString()},
                {"记录数", Integer.toString(items.size())}, {"申请当前标题或单号包含", query.text()}, {"操作人账号", query.actor()},
                {"动作代码", query.action()}, {"事件来源", query.source()}, {"关联申请标识", query.applicationId() == null ? null : query.applicationId().toString()},
                {"操作时间起点（含，UTC）", query.occurredFrom() == null ? null : query.occurredFrom().toString()},
                {"操作时间终点（不含，UTC）", query.occurredBefore() == null ? null : query.occurredBefore().toString()},
                {"范围", "本次查询全部匹配操作的摘要，不含表单正文、审批意见或完整审计 JSON。"},
                {"时效", "单次数据库查询结果；之后可能出现新操作，关联申请标题是查询时的当前值。空筛选表示该项不限。"},
                {"缺失信息", "旧记录缺失的元数据保留为空单元格，不推断操作人、动作或关联申请。"},
                {"动作说明", "APPROVE 表示一次任务同意操作，不代表整单已经批准。"}};
        return TextWorkbook.write("操作审计", HEADINGS, WIDTHS, values, metadata);
    }
}
