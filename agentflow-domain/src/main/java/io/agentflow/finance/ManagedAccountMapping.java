package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

/**
 * 发送给 ERP 的已发布科目子集；不暴露本次凭证无关的账户配置。
 * @author owlzhangfq@gmail.com
 */
public record ManagedAccountMapping(String tenantId, UUID legalEntityId, String currency, AccountMappingSelection selection,
                                    List<AccountMappingPort.Entry> entries) {
    /** 外部回执必须保留完整选择身份以及本次使用的精确科目。 */
    public ManagedAccountMapping {
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || legalEntityId == null || selection == null
                || CollectionUtils.isEmpty(entries) || entries.size() > AccountMappingPort.MAX_KEYS
                || entries.stream().anyMatch(java.util.Objects::isNull)
                || entries.stream().map(AccountMappingPort.Entry::key).distinct().count() != entries.size()) throw invalid();
        Money.zero(currency);
        entries = entries.stream().sorted(Comparator.comparing(AccountMappingPort.Entry::key, AccountMappingPort.keyOrder())).toList();
    }

    /** 请求中的法人、币种和必要键须与管理配置的实际子集一致。 */
    public void requireScope(UUID entity, String expectedCurrency, List<AccountMappingPort.Key> keys) {
        if (!legalEntityId.equals(entity) || !currency.equals(expectedCurrency) || !entries.stream().map(AccountMappingPort.Entry::key).toList().equals(keys)) throw invalid();
    }

    private static DomainException invalid() { return new DomainException("INVALID_ACCOUNT_MAPPING_SELECTION", "Managed account mapping must bind the same tenant scope and required accounting keys"); }
}
