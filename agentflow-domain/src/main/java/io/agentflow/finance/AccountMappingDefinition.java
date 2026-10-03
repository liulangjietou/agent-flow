package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseCategoryCatalog;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 一个法人及币种的科目配置；这里只描述映射意图，科目可用性仍由 ERP 校验。
 * @author owlzhangfq@gmail.com
 */
public record AccountMappingDefinition(String name, UUID legalEntityId, String currency, List<AccountMappingPort.Entry> entries) {
    public static final int MAX_ENTRIES = 4096;

    /** 草稿可以暂时为空，重复用途和含糊的科目标识不能进入任何修订。 */
    public AccountMappingDefinition {
        if (invalidText(name, 128) || legalEntityId == null || entries == null || entries.size() > MAX_ENTRIES
                || entries.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        Money.zero(currency);
        if (entries.stream().map(AccountMappingPort.Entry::key).distinct().count() != entries.size()
                || entries.stream().anyMatch(entry -> invalidText(entry.accountCode(), 128)
                || !entry.key().selector().equals(entry.key().selector().strip())
                || entry.key().selector().chars().anyMatch(Character::isISOControl))) throw invalid();
        entries = entries.stream().sorted(Comparator.comparing(AccountMappingPort.Entry::key, AccountMappingPort.keyOrder())).toList();
    }

    /** 发布不能包含未知或已停用的费用类别；其他会计用途不虚构费用类别。 */
    public void requirePublishable(ExpenseCategoryCatalog categories) {
        var active = categories.activeCategories().stream().map(ExpenseCategoryCatalog.Category::code).collect(java.util.stream.Collectors.toSet());
        if (entries.isEmpty() || entries.stream().anyMatch(entry -> entry.key().role() == AccountMappingPort.Role.EXPENSE
                && !active.contains(entry.key().selector()))) {
            throw new DomainException("ACCOUNT_MAPPING_NOT_PUBLISHABLE", "Account mappings require entries and active expense categories");
        }
    }

    /** 只截取本次凭证需要的键，缺一项即阻断，不能补用旧版本或默认科目。 */
    public List<AccountMappingPort.Entry> select(AccountMappingPort.Request request) {
        if (!legalEntityId.equals(request.legalEntityId()) || !currency.equals(request.currency())) {
            throw new DomainException("ACCOUNT_MAPPING_SCOPE_MISMATCH", "Account mapping legal entity and currency must match the voucher source");
        }
        var selected = entries.stream().filter(entry -> request.keys().contains(entry.key())).toList();
        if (selected.size() != request.keys().size()) {
            throw new DomainException("ACCOUNT_MAPPING_UNAVAILABLE", "Published account mapping does not cover every required accounting key");
        }
        return selected;
    }

    /** 摘要覆盖完整发布正文，按 UTF-8 长度编码，字段分隔符和输入顺序不影响身份。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-account-mapping-definition-1", name, legalEntityId.toString(), currency, Integer.toString(entries.size()));
            for (var entry : entries) add(digest, entry.key().role().name(), entry.key().selector(), entry.accountCode());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void add(MessageDigest digest, String... values) {
        for (var value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() { return new DomainException("INVALID_ACCOUNT_MAPPING_DEFINITION", "Account mapping definition requires a fixed scope and unique accounting keys"); }
}
