package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 版本停用与恢复的不可变事实，身份和前后状态来自服务端。
 * @author owlzhangfq@gmail.com
 */
public record DefinitionAvailabilityChange(String tenantId, UUID definitionId, long revision,
        boolean previousEnabled, boolean startEnabled, String changedBy, String authorizedRole,
        Instant changedAt, String reason) {
    public static final int MAX_REASON_LENGTH = 2000;

    /** 说明是治理事实的一部分，不能用空记录代替真实操作依据。 */
    public DefinitionAvailabilityChange {
        if (StringUtils.isBlank(reason) || reason.length() > MAX_REASON_LENGTH) {
            throw new DomainException("INVALID_AVAILABILITY_REASON", "Availability reason must contain 1 to 2000 characters");
        }
        reason = reason.strip();
    }

    /** 校验后生成下一修订的事件，由应用服务与聚合变更一起提交。 */
    public static DefinitionAvailabilityChange prepare(DefinitionModels.DefinitionDraft definition, Actor actor,
                                                       boolean enabled, String reason, Instant changedAt) {
        String role;
        if (actor.hasRole("ADMIN")) role = "ADMIN";
        else if (actor.hasRole("PROCESS_ADMIN")) role = "PROCESS_ADMIN";
        else throw new DomainException("FORBIDDEN", "Process administrator role is required");
        return new DefinitionAvailabilityChange(definition.tenantId(), definition.id(), definition.revision() + 1,
                definition.startEnabled(), enabled, actor.userId(), role, changedAt, reason);
    }
}
