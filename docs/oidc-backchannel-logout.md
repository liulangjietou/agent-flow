# 身份源发起的后通道注销

企业身份源可以发送签名 Logout Token，使对应企业登录在所有平台实例上失效。平台不需要回调自身 HTTP 地址，也不扫描、反序列化所有会话。身份源账号禁用、权限撤销只有在身份源确实发送该通知时才会触发此链路；本功能不是组织同步或账号状态轮询。

## 配置与协议入口

先完成 [OIDC 配置](enterprise-oidc.md)，关闭演示认证，并在所有实例上启用 [JDBC 共享会话](shared-enterprise-sessions.md)。再设置：

```yaml
agentflow:
  auth:
    backchannel:
      enabled: true
```

等价环境变量为 `AGENTFLOW_OIDC_BACKCHANNEL_LOGOUT=true`，默认关闭。未同时启用 OIDC 和共享会话，或仍启用演示认证时启动失败。所有实例必须使用同一可信 issuer、client-id、开关与数据库；不能让未启用检查的旧实例继续接收流量。

在企业 IdP 的机密客户端登记后通道注销地址：

```text
https://flow.example/api/v1/auth/oidc/backchannel-logout/enterprise
```

身份源使用 `POST`、`Content-Type: application/x-www-form-urlencoded`，正文只提供一个 `logout_token`。请求体上限 32 KiB；额外表单字段忽略。该协议入口无需浏览器 Cookie、CSRF、`X-AgentFlow-Actor` 或业务幂等键，其他业务写接口继续原有保护。入口由安全过滤器实现，和 OIDC 授权/回调一样在本文维护协议契约，不计入业务 MVC OpenAPI 操作数。

| 响应 | 含义 |
|---|---|
| 200，空正文 | 有效注销水位已持久化；重复通知、当前没有匹配登录也成功 |
| 400，`{"error":"invalid_logout_token"}` | 格式、签名或协议声明无效 |
| 405，`Allow: POST` | 使用了错误的 HTTP 方法 |
| 503，`{"error":"logout_unavailable"}` | 公钥获取或共享存储不可用，身份源应重试 |

所有入口响应使用 `Cache-Control: no-store`。开关关闭时入口不开放，不返回上述成功响应。

## 验证与生效范围

密码学验证由 Spring Security `NimbusJwtDecoder` 执行，仅信任当前客户端发现配置中的 JWKS 和 RS256，拒绝 `none` 和其他算法。JWKS 请求连接限时 5 秒、读取限时 10 秒，不跟随重定向。`typ` 接受缺省、`JWT`、`logout+jwt`，兼容没有显式类型的现有 IdP。

按 [OpenID Connect Back-Channel Logout 1.0（含 errata set 1）](https://openid.net/specs/openid-connect-backchannel-1_0.html) 校验 `iss`、`aud`、`iat`、`exp`、`jti` 和 `events`；事件必须包含 `http://schemas.openid.net/event/backchannel-logout` 对象，禁止出现 `nonce`，`sub` 和 `sid` 至少提供一个。原始声明先检查类型和空值，再验签及检查可信值，避免类型转换掩盖错误。

通知 `iat` 最多向前容忍 60 秒、向后接受 5 分钟；`exp` 必须晚于 `iat`，过期检查容忍 60 秒时钟差。节点与 IdP 需要同步时钟。

- 只有 `sid`：注销当前 issuer / client-id 下该身份源会话的登录。
- 只有 `sub`：注销当前 issuer / client-id 下该用户的全部旧登录。
- 同时提供：两个标识都匹配才注销。
- 身份源同一个 `sub` 在平台映射到多个租户时，用户级注销会影响这些登录；不能通过通知指定平台租户或扩大至其他 issuer / client-id。

## 持久化与并发边界

V22 新增 `AF_OIDC_LOGOUT_SCOPE`。表内只保存作用域 SHA-256 和最后注销的 `iat`，不保存 JWT、Cookie、原始用户或会话标识。每个作用域只保留最大时间，因此重复、乱序和并发投递不会回退水位，也不会注销签发时间晚于原通知的新登录。

登录回调和每次企业业务请求都查询最多三个主键。ID Token 的 `iat` 小于或等于对应水位时拒绝认证；已有 HTTP 会话在首次后续认证请求时销毁，未再访问的会话由 Spring Session 原有空闲清理回收。注销通知成功后不缓存旧的允许结果。数据库不可用时业务认证返回 503，不能继续读取受保护业务。

先到达的注销通知也能阻止携带旧 ID Token 的延迟回调重新建立可用登录。由于 JWT 时间精度为秒，同秒的新旧登录无法可靠区分，平台保守拒绝，需取得签发时间更晚的新 ID Token。已经完成认证、进入业务处理的请求不被回滚；该机制不改变已提交审批、资金或审计事实。

水位不能按 Logout Token 的 `exp` 删除：通知有效期结束不意味着旧登录全部到期。当前不自动删除这些小型认证记录，纳入共享数据库备份。未来清理需证明对应旧登录及延迟回调全部失效；本阶段没有提供运维删除入口。不要将数据库恢复到注销前的旧快照后直接开放旧会话。

## 验证和未完成范围

`OidcBackchannelIntegrationTest` 使用真实 RSA/JWKS/授权码和两个独立 HTTP 实例，覆盖用户与会话范围、重启、重复乱序并发、延迟回调、错误声明及存储故障。通过 `agentflow.backchannel-test.jdbc-*` 可在隔离 PostgreSQL 上运行同一组验证；默认使用独立 H2。

真实企业 IdP 联调、生产代理和集群验收、主动跳转 IdP 的全局退出、上游权限同步仍需单独完成。不能把本地协议夹具的成功表述为企业账号治理已经全部接通。
