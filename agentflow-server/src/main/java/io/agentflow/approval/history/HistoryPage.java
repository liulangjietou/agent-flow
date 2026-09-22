package io.agentflow.approval.history;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 游标页明确返回 nextCursor=null，客户端不需要推断是否还有下一页。
 * @author owlzhangfq@gmail.com
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HistoryPage(List<HistoryEvent> items, String nextCursor) { }
