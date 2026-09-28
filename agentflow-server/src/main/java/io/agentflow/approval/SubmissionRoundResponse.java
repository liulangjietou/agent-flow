package io.agentflow.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.InitiatorContext;

import java.time.Instant;
import java.util.Map;

/**
 * 申请提交轮次视图，尚未完成的结论字段明确返回 null。
 * @author owlzhangfq@gmail.com
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubmissionRoundResponse(int roundNo, String processInstanceId, long definitionVersion,
                                      String title, Map<String, Object> payload, String submittedBy,
                                      Instant submittedAt, SubmissionRound.Status status, String reason,
                                      String completedBy, Instant completedAt, FormSchema formSchema, InitiatorContext initiatorContext) {
    /** 将授权后的轮次快照转换为公开接口字段。 */
    public static SubmissionRoundResponse from(SubmissionRound round) {
        return new SubmissionRoundResponse(round.roundNo(), round.processInstanceId(), round.definitionVersion(),
                round.title(), round.payload(), round.submittedBy(), round.submittedAt(), round.status(),
                round.reason(), round.completedBy(), round.completedAt(), round.formSchema(), round.initiatorContext());
    }
}
