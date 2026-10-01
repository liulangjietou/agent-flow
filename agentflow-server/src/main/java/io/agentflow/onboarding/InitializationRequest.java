package io.agentflow.onboarding;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 首次配置的入口选择，创建与采用已有对象互斥；具体组织和日历规则仍由原领域维护。
 * @author owlzhangfq@gmail.com
 */
public record InitializationRequest(String workspaceName, Long expectedOrganizationRevision,
                                    Boolean confirmLocalDirectory, OrganizationChoice organization,
                                    CalendarChoice calendar, NotificationChoice notifications) {
    private static final int MAX_NAME_LENGTH = 128;

    /** 校验向导自身的名称、明确确认和来源修订。 */
    public InitializationRequest {
        require(StringUtils.isNotBlank(workspaceName) && workspaceName.length() <= MAX_NAME_LENGTH
                && workspaceName.chars().noneMatch(Character::isISOControl));
        workspaceName = workspaceName.strip();
        require(expectedOrganizationRevision != null && expectedOrganizationRevision >= 0
                && Boolean.TRUE.equals(confirmLocalDirectory) && organization != null && calendar != null && notifications != null);
    }

    /** 不忽略身份、角色或未定义设置，防止页面误以为额外配置已生效。 */
    @JsonAnySetter public void reject(String key, Object value) { throw invalid(); }

    /** 创建明确的新对象，或采用已核对的现有对象。
     * @author owlzhangfq@gmail.com
     */
    public enum Source { CREATE, EXISTING }

    /** 采用已有任职时不接受名称、人员或组织的覆盖字段。
     * @author owlzhangfq@gmail.com
     */
    public record OrganizationChoice(Source source, UUID appointmentId, String legalEntityName,
                                     String departmentName, String positionName, String administratorName) {
        /** 不允许把创建信息混入采用已有任职的选择。 */
        public OrganizationChoice {
            require(source != null);
            if (source == Source.CREATE) require(appointmentId == null && legalEntityName != null
                    && departmentName != null && positionName != null && administratorName != null);
            else require(appointmentId != null && legalEntityName == null && departmentName == null
                    && positionName == null && administratorName == null);
        }
        /** 任职身份由登录和组织目录解析，不能随请求自报。 */
        @JsonAnySetter public void reject(String key, Object value) { throw invalid(); }
    }

    /** 日历采用精确历史版本；不会把请求中的规则覆盖到现有日历。
     * @author owlzhangfq@gmail.com
     */
    public record CalendarChoice(Source source, UUID id, Long revision, String key, String name, CalendarRules rules) {
        /** 不允许把新规则与已有版本引用混合提交。 */
        public CalendarChoice {
            require(source != null);
            if (source == Source.CREATE) require(id == null && revision == null && key != null && name != null && rules != null);
            else require(id != null && revision != null && revision > 0 && key == null && name == null && rules == null);
        }
        /** 拒绝通过其他字段间接指定租户或更新人。 */
        @JsonAnySetter public void reject(String key, Object value) { throw invalid(); }
    }

    /** 只选择本人已受控绑定的外部渠道；绑定摘要用于识别查看后发生的部署变更。
     * @author owlzhangfq@gmail.com
     */
    public record NotificationChoice(Long expectedVersion, Boolean emailEnabled, Boolean enterpriseImEnabled,
                                     String emailBindingDigest, String enterpriseImBindingDigest) {
        /** 启用须携带绑定摘要，关闭时不能夹带另一个收件目标。 */
        public NotificationChoice {
            require(expectedVersion != null && expectedVersion >= 0 && emailEnabled != null && enterpriseImEnabled != null);
            requireBinding(emailEnabled, emailBindingDigest);
            requireBinding(enterpriseImEnabled, enterpriseImBindingDigest);
        }
        /** 不接收凭据、收件地址、其他主体或站内关闭开关。 */
        @JsonAnySetter public void reject(String key, Object value) { throw invalid(); }
        private static void requireBinding(boolean enabled, String digest) {
            require(enabled ? digest != null && digest.matches("[a-f0-9]{64}") : digest == null);
        }
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static DomainException invalid() { return new DomainException("INVALID_INITIALIZATION_REQUEST", "Invalid tenant initialization selection"); }
}
