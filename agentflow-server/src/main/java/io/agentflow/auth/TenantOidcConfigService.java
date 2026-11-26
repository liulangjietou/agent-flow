package io.agentflow.auth;

import io.agentflow.auth.mapper.TenantOidcProviderMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.SystemRole;
import io.agentflow.mybatis.SqlRow;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 多租户动态 OIDC 身份提供方配置管理服务：支持租户管理员在线配置与切换，响应始终脱敏 clientSecret。
 * @author owlzhangfq@gmail.com
 */
@Service
public class TenantOidcConfigService {
    private static final Logger LOG = LoggerFactory.getLogger(TenantOidcConfigService.class);
    private final TenantOidcProviderMapper mapper;

    /** 构造多租户 OIDC 配置服务。 */
    public TenantOidcConfigService(TenantOidcProviderMapper mapper) {
        this.mapper = mapper;
    }

    /** 列出本租户全部 OIDC 提供方配置（密钥仅返回已配置标志）。 */
    @Transactional(readOnly = true)
    public List<ProviderView> list(Actor admin) {
        admin.requireRole(SystemRole.ADMIN);
        return mapper.listByTenant(admin.tenantId()).stream().map(TenantOidcConfigService::toView).toList();
    }

    /** 新增或更新租户 OIDC 提供方配置，严格校验 HTTPS issuerUri 与合法 scope。 */
    @Transactional
    public ProviderView upsert(Actor admin, String providerKey, String displayName, String issuerUri,
                               String clientId, String clientSecret, String scopes, boolean enabled,
                               Long expectedVersion) {
        admin.requireRole(SystemRole.ADMIN);
        String key = validateKey(providerKey);
        String name = validateText(displayName, 128, "displayName");
        String normalizedIssuer = validateIssuer(issuerUri);
        String normalizedClientId = validateText(clientId, 256, "clientId");
        String normalizedScopes = StringUtils.isBlank(scopes) ? "openid profile email" : validateText(scopes, 512, "scopes");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        var existingRows = mapper.findByKey(admin.tenantId(), key);
        if (existingRows.isEmpty()) {
            String normalizedSecret = validateText(clientSecret, 1024, "clientSecret");
            UUID id = UUID.randomUUID();
            mapper.insert(
                    id.toString(), admin.tenantId(), key, name, normalizedIssuer, normalizedClientId,
                    normalizedSecret, normalizedScopes, enabled, admin.userId(), Timestamp.from(now));
            LOG.info("Tenant OIDC provider created, tenantId={}, providerKey={}", admin.tenantId(), key);
        } else {
            SqlRow existing = existingRows.get(0);
            long currentVersion = existing.getLong("version");
            long targetVersion = expectedVersion == null ? currentVersion : expectedVersion;
            String effectiveSecret = StringUtils.isBlank(clientSecret)
                    ? existing.getString("client_secret")
                    : validateText(clientSecret, 1024, "clientSecret");
            int updated = mapper.update(
                    admin.tenantId(), key, name, normalizedIssuer, normalizedClientId,
                    effectiveSecret, normalizedScopes, enabled, admin.userId(), targetVersion, Timestamp.from(now));
            if (updated != 1) {
                throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "OIDC provider configuration version changed");
            }
            LOG.info("Tenant OIDC provider updated, tenantId={}, providerKey={}", admin.tenantId(), key);
        }
        return toView(mapper.findByKey(admin.tenantId(), key).get(0));
    }

    /** 删除本租户指定的 OIDC 提供方配置。 */
    @Transactional
    public void delete(Actor admin, UUID id) {
        admin.requireRole(SystemRole.ADMIN);
        int deleted = mapper.deleteById(admin.tenantId(), id.toString());
        if (deleted != 1) {
            throw new DomainException(ErrorCode.NOT_FOUND, "OIDC provider configuration not found");
        }
        LOG.info("Tenant OIDC provider deleted, tenantId={}, providerId={}", admin.tenantId(), id);
    }

    private static ProviderView toView(SqlRow row) {
        return new ProviderView(
                UUID.fromString(row.getString("id")),
                row.getString("tenant_id"),
                row.getString("provider_key"),
                row.getString("display_name"),
                row.getString("issuer_uri"),
                row.getString("client_id"),
                StringUtils.isNotBlank(row.getString("client_secret")),
                row.getString("scopes"),
                Boolean.TRUE.equals(row.getBoolean("enabled")),
                row.getLong("version"),
                row.getString("updated_by"),
                row.getTimestamp("updated_at").toInstant());
    }

    private static String validateKey(String raw) {
        if (StringUtils.isBlank(raw) || !raw.trim().matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new DomainException(ErrorCode.INVALID_OIDC_PROVIDER,
                    "providerKey must match [a-z0-9][a-z0-9_-]{1,63}");
        }
        return raw.trim();
    }

    private static String validateIssuer(String raw) {
        if (StringUtils.isBlank(raw) || raw.trim().length() > 512) {
            throw new DomainException(ErrorCode.INVALID_OIDC_PROVIDER, "issuerUri is required");
        }
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || StringUtils.isBlank(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new DomainException(ErrorCode.INVALID_OIDC_PROVIDER,
                        "issuerUri must be a clean https URL without userinfo, query or fragment");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_OIDC_PROVIDER, "issuerUri is malformed");
        }
    }

    private static String validateText(String raw, int maxLength, String field) {
        if (StringUtils.isBlank(raw) || raw.trim().length() > maxLength
                || raw.chars().anyMatch(Character::isISOControl)) {
            throw new DomainException(ErrorCode.INVALID_OIDC_PROVIDER, field + " is invalid");
        }
        return raw.trim();
    }

    /**
     * 对外安全视图，禁止回传 clientSecret 原文。
     * @author owlzhangfq@gmail.com
     */
    public record ProviderView(
            UUID id, String tenantId, String providerKey, String displayName, String issuerUri,
            String clientId, boolean clientSecretConfigured, String scopes, boolean enabled,
            long version, String updatedBy, Instant updatedAt) { }
}
