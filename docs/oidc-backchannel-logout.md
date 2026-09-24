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
| 200，空正文 | 有效注销顺序已持久化；重复通知、当前没有匹配登录也成功 |
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

V22 建立 `AF_OIDC_LOGOUT_SCOPE`；V23 增加数据库顺序、通知去重表和作用域顺序列，不改写旧迁移。存储只包含作用域 / 通知 ID 的 SHA-256、计数和签发时间，不保存原始 JWT、Cookie、用户或身份源会话标识。

授权请求发出前读取数据库已提交的注销顺序，并放入服务端原授权请求，随共享会话跨实例恢复。回调成功时将其绑定登录会话。每次企业业务认证查询最多三个作用域主键；任一注销顺序晚于登录基线即拒绝，销毁当前 HTTP 会话。先开始、后返回的授权回调同样被拒绝。授权请求基线不取 URL、请求头或其他客户端值。

注销通知在一个短事务中锁定全局顺序行，按 issuer / client-id / jti 去重，推进顺序并更新匹配作用域。重复通知不再推进顺序，所以不影响注销后重新发起的登录；签发时间不同的独立通知仍按实际接收提交顺序生效。`iat` 只用于协议时效和记录，不用来判断登录是否早于注销，避免可接受的时钟差或同秒签发导致漏注销。

业务请求不缓存旧的允许结果。数据库不可用时返回 503，不能继续读取受保护业务；通知事务失败不会留下已经处理的去重记录，身份源重试可以正常执行。已完成认证、进入业务处理的请求不被回滚；该机制不改变已提交审批、资金或审计事实。

升级 V23 或开启功能后，缺少登录顺序的旧会话与进行中的旧授权请求须重新登录。必须先停止所有旧实例接收流量，再开放新版实例；不能混用只检查签发时间的旧实现。V23 之前没有保存通知去重标识，升级后仍有效的旧通知首次到达会再处理一次，之后才按通知 ID 去重。

注销作用域和通知去重记录当前长期保留并纳入数据库备份，没有自动清理或运维删除入口。不能仅根据 Logout Token 的 `exp` 删除作用域，因为通知有效期结束不等于所有旧登录或延迟回调都已失效。生产容量和留存治理需另行验收；不要将数据库恢复到注销前的旧快照后直接开放旧会话。

## 验证和未完成范围

`OidcBackchannelIntegrationTest` 使用真实 RSA/JWKS/授权码和两个独立 HTTP 实例，覆盖用户与会话范围、重启、重复乱序并发、延迟回调、错误声明及存储故障。通过 `agentflow.backchannel-test.jdbc-*` 可在隔离 PostgreSQL 上运行同一组验证；默认使用独立 H2。

真实企业 IdP 联调、生产代理和集群验收、主动跳转 IdP 的全局退出、上游权限同步仍需单独完成。不能把本地协议夹具的成功表述为企业账号治理已经全部接通。
