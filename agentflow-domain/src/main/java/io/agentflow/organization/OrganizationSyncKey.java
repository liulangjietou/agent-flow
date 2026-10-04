package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

/**
 * 来源标识按对象类型精确匹配，不用姓名或大小写归一化推断同一对象。
 * @author owlzhangfq@gmail.com
 */
public record OrganizationSyncKey(Kind kind, String externalId) {
    /** 外部标识保留原值，同号的部门、岗位和人员属于不同命名空间。 */
    public OrganizationSyncKey {
        if (kind == null) throw invalid();
        text(externalId, 128);
    }

    static String text(String value, int maximum) {
        if (StringUtils.isBlank(value) || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    static String source(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw invalid();
        return value;
    }

    static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid();
        return value;
    }

    static void reference(OrganizationSyncKey value, Kind kind, boolean optional) {
        if (value == null ? !optional : value.kind() != kind) throw invalid();
    }

    static DomainException invalid() { return new DomainException("INVALID_ORGANIZATION_SYNC_DATA", "Organization synchronization data is invalid"); }

    /**
     * 引用类型必须来自固定目录，不接受认证角色、账号密码或任意业务对象。
     * @author owlzhangfq@gmail.com
     */
    public enum Kind { LEGAL_ENTITY, DEPARTMENT, POSITION, PERSON, APPOINTMENT }
}
