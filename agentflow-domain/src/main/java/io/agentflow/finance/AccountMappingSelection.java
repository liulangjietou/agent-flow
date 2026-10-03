package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 本次会计准备固定的发布物、生效修订及 ERP 目标，不接受跨版本拼接。
 * @author owlzhangfq@gmail.com
 */
public record AccountMappingSelection(UUID mappingId, long mappingVersion, long categoryRevision, long activeRevision,
                                      String definitionDigest, String targetDigest) {
    /** 未采用平台配置的历史凭证使用外层空值，不伪造零版已发布映射。 */
    public AccountMappingSelection {
        if (mappingId == null || mappingVersion < 1 || categoryRevision < 0 || activeRevision < 1
                || !validDigest(definitionDigest) || !validDigest(targetDigest)) {
            throw new DomainException("INVALID_ACCOUNT_MAPPING_SELECTION", "Account mapping selection requires immutable versions and a bound finance target");
        }
    }
    private static boolean validDigest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
