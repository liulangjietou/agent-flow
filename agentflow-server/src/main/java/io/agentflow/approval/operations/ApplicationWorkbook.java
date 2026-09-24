package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 申请摘要的列与筛选说明，单元格安全编码由共用工作簿表示器处理。
 * @author owlzhangfq@gmail.com
 */
final class ApplicationWorkbook {
    static final String MEDIA_TYPE = TextWorkbook.MEDIA_TYPE;
    private static final String[] HEADINGS = {"申请标识", "业务单号", "标题", "流程标识", "流程版本", "申请人账号", "状态代码", "当前轮次", "创建时间（UTC）", "更新时间（UTC）"};
    private static final int[] WIDTHS = {38, 28, 46, 30, 12, 22, 20, 12, 30, 30};

    private ApplicationWorkbook() { }

    /** 使用字符串类型保留前导零、长单号、换行及以等号开头的原文。 */
    static byte[] write(List<ApplicationSearchPort.Item> items, Actor actor, ApplicationSearchPort.Query query, Instant startedAt) throws IOException {
        var values = items.stream().map(item -> new String[]{item.id().toString(), item.businessNo(), item.title(), item.processKey(), Long.toString(item.definitionVersion()),
                item.createdBy(), item.status(), Integer.toString(item.roundNo()), item.createdAt().toString(), item.updatedAt().toString()}).toList();
        String[][] metadata = {{"租户", actor.tenantId()}, {"导出账号", actor.userId()}, {"生成开始时间（UTC）", startedAt.toString()},
                {"记录数", Integer.toString(items.size())}, {"标题或业务单号包含", query.text()}, {"状态代码", query.status()},
                {"流程标识", query.processKey()}, {"流程版本", query.definitionVersion() == null ? "" : query.definitionVersion().toString()},
                {"申请人账号", query.applicant()}, {"创建时间起点（含，UTC）", query.createdFrom() == null ? "" : query.createdFrom().toString()},
                {"创建时间终点（不含，UTC）", query.createdBefore() == null ? "" : query.createdBefore().toString()},
                {"范围", "本次查询全部匹配申请的当前摘要，不含表单正文、评论或审批意见。"},
                {"时效", "单次数据库查询结果；审批状态之后可能变化。空筛选表示该项不限。"},
                {"状态说明", "DRAFT 草稿；IN_APPROVAL 审批中；RETURNED 已退回；WITHDRAWN 已撤回；APPROVED 已批准；REJECTED 已驳回；CANCELLED 已作废；REVOKED 已撤销。"}};
        return TextWorkbook.write("申请记录", HEADINGS, WIDTHS, values, metadata);
    }
}
