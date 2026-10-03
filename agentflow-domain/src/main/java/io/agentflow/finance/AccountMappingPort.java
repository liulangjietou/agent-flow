package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ERP 校验本次科目映射；平台发布配置时固定版本意图，不能猜测或替代 ERP 科目事实。
 * @author owlzhangfq@gmail.com
 */
public interface AccountMappingPort {
    int MAX_KEYS = io.agentflow.expense.ExpenseContent.MAX_LINES + 4;
    /** 只查询本次业务所需科目，缺任一映射均不能组成可推送凭证。 */
    FinanceResult<Mapping> mapping(String tenantId, String targetDigest, Request request);

    /**
     * 固定的会计用途，不接受自由表达式或任意映射类型。
     * @author owlzhangfq@gmail.com
     */
    enum Role { EXPENSE, DEDUCTIBLE_TAX, EMPLOYEE_RECEIVABLE, EMPLOYEE_PAYABLE, BANK }

    /**
     * 费用科目按类别选择，银行科目按已授权出款账户引用选择，其他用途无选择器。
     * @author owlzhangfq@gmail.com
     */
    record Key(Role role, String selector) {
        /** 空选择器使用空串，禁止省略真实费用类别或出款账户。 */
        public Key {
            if (role == null || selector == null || selector.length() > 128
                    || ((role == Role.EXPENSE || role == Role.BANK) ? StringUtils.isBlank(selector) : !selector.isEmpty())) throw invalid();
        }
    }

    /**
     * 同一法人和本位币的必要科目集合。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID legalEntityId, String currency, List<Key> keys,
                   @JsonInclude(JsonInclude.Include.NON_NULL) ManagedAccountMapping managedMapping) {
        /** 旧调用继续使用 ERP 管理的映射，未启用平台版本时不添加传输字段。 */
        public Request(UUID legalEntityId, String currency, List<Key> keys) { this(legalEntityId, currency, keys, null); }
        /** 排序保证同一集合的规范表示稳定，重复键直接拒绝。 */
        public Request {
            if (legalEntityId == null || CollectionUtils.isEmpty(keys) || keys.size() > MAX_KEYS || keys.stream().anyMatch(java.util.Objects::isNull)
                    || keys.stream().distinct().count() != keys.size()) throw invalid();
            Money.zero(currency);
            keys = keys.stream().sorted(keyOrder()).toList();
            if (managedMapping != null) managedMapping.requireScope(legalEntityId, currency, keys);
        }
    }

    /**
     * 企业维护的科目代码；成本中心、项目和员工辅助核算由原业务行携带。
     * @author owlzhangfq@gmail.com
     */
    record Entry(Key key, String accountCode) {
        /** 每个用途只能映射到一个明确科目。 */
        public Entry { if (key == null || invalidText(accountCode)) throw invalid(); }
    }

    /**
     * 本次查询完整匹配的不可变科目映射，不接受部分成功。
     * @author owlzhangfq@gmail.com
     */
    record Mapping(Request request, String sourceVersion, Instant observedAt, Instant validUntil, List<Entry> entries) {
        /** 恢复持久化事实时也验证缺项、重复项和多余项。 */
        public Mapping {
            if (request == null || invalidText(sourceVersion) || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)
                    || entries == null || entries.size() != request.keys().size() || entries.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            entries = entries.stream().sorted(Comparator.comparing(Entry::key, keyOrder())).toList();
            if (!entries.stream().map(Entry::key).toList().equals(request.keys())) throw invalid();
            if (request.managedMapping() != null && !entries.equals(request.managedMapping().entries())) throw invalid();
        }
        /** 本次查询必须原法人、原币种、原必要科目，且仍在时效内。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && !observedAt.isAfter(now) && validUntil.isAfter(now);
        }
        /** 凭证生成只能使用本次已查明的科目。 */
        public String account(Key key) { return entries.stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow(AccountMappingPort::invalid).accountCode(); }
    }

    /** 配置、请求与回执采用同一排序，规范摘要不依赖输入集合顺序。 */
    static Comparator<Key> keyOrder() { return Comparator.comparing((Key key) -> key.role().name()).thenComparing(Key::selector); }
    private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
    private static DomainException invalid() { return new DomainException("INVALID_ACCOUNT_MAPPING", "Account mapping must cover exactly the requested legal entity, currency and accounting roles"); }
}
