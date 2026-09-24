# 企业 OIDC 登录

本阶段实现可配置的标准 OIDC 登录和平台会话。真实企业身份服务、组织目录、审批人同步、多实例会话和账号停用通知仍需接入，不能把本地协议夹具验收当成企业上线验收。

## 调用链和职责

登录页 → `GET /api/v1/auth/options` → 同源授权入口 → 身份服务 → 固定回调 → Spring Security 协议验证 → `OidcActorMapper` 显式映射 → 服务端会话 → `BearerAuthFilter` 设置 `CurrentActor` → 原申请/任务应用服务复核资源权限。

协议、Cookie、CSRF 和身份声明属于接入层。申请聚合、审批状态、Flowable 适配器及事务内的幂等记录没有变更，未新增数据库迁移。`AuthService` 继续只提供演示身份和演示审批人目录；OIDC 登录不会自动创建租户、组织或审批人。

## 配置

使用同源反向代理，前端和 `/api` 共用一个 HTTPS Origin。`VITE_API_BASE` 使用默认 `/api/v1`。在身份服务注册机密客户端，启用授权码、PKCE S256、`client_secret_basic` 和 RS256 签名 ID Token，回调必须准确注册为：

```text
https://flow.example/api/v1/auth/oidc/callback/enterprise
```

部署配置示例（地址、客户端和映射需替换为企业已确认值，密钥通过受控环境注入）：

```yaml
agentflow:
  web:
    allowed-origin: https://flow.example
  auth:
    demo-enabled: false
    oidc:
      enabled: true
      issuer: https://identity.example/realms/enterprise
      client-id: agentflow
      client-secret: ${AGENTFLOW_OIDC_CLIENT_SECRET}
      tenant-claim: tenant
      roles-claim: roles
      tenant-mappings:
        corporate-tenant: enterprise-tenant
      role-mappings:
        flow-employee: [EMPLOYEE]
        flow-approver: [EMPLOYEE, APPROVER]
        flow-administrator: [EMPLOYEE, APPROVER, PROCESS_ADMIN, ADMIN]
      allow-insecure-loopback: false
server:
  servlet:
    session:
      timeout: 30m
```

- issuer 必须与发现文档及签名 Token 的 `iss` 一致；配置和发现端点均要求 HTTPS。发现请求连接超时 5 秒、读取超时 10 秒，拒绝 HTTP 重定向。
- 仅本地测试可显式设置 `allow-insecure-loopback: true`，只接受 localhost、127.0.0.1、::1 的 HTTP。`prod` / `production` profile 禁止此开关。
- 演示认证与 OIDC 不得同时启用；没有明确租户映射和角色映射时启动失败，不降级到演示账号。
- 租户声明必须是顶层字符串，角色声明必须是顶层字符串数组。仅读取经过协议验证的签名 ID Token，不调用 UserInfo，也不把用户名或邮箱作为身份替代值。
- 平台用户标识使用稳定 `sub`；更换 issuer 或改变 sub 生成策略前必须规划既有申请/审计主体的身份迁移，本阶段不自动迁移身份。
- 未映射租户、没有任何已映射角色、非法声明和过期 Token 均拒绝登录。外部 `ADMIN` 字符串不会自动授予平台管理员。

## 会话和浏览器操作

授权请求由 Spring Security 生成 state、nonce 和 PKCE，回调固定取部署配置，不取 Host、转发头或用户提供的返回地址。成功回到固定首页，失败只显示通用提示，不向浏览器暴露协议异常和令牌。

浏览器持有 `AGENTFLOW_SESSION`：HttpOnly、SameSite=Lax、Path=/，HTTPS 部署强制 Secure。登录轮换会话标识。平台不持久化 access token 或 refresh token，也不把 OIDC 令牌写入 localStorage 或 URL。身份上下文保留 ID Token；会话位于服务端内存，后端重启后需要重新登录。

请求同时受会话空闲过期和 ID Token 到期约束，到期后不自动刷新权限。身份服务的全局退出、账号禁用回调和即时权限撤销尚未接入。

所有企业模式的写请求需要 `GET /api/v1/auth/options` 返回的 `X-CSRF-TOKEN`。业务写请求还携带 `X-AgentFlow-Actor`，值为 `encodeURIComponent(JSON.stringify([tenantId, userId]))`；该请求头只核对页面身份，服务端身份仍来自已验证会话。带该头的读请求同样核对身份，防止其他标签切换账号后旧页面读取或提交另一账号的数据。`/auth/me` 用于读取实际身份。

退出调用 `POST /api/v1/auth/logout`，携带 CSRF，销毁平台会话并返回 204；它不退出 IdP 全局会话。退出请求失败时页面保持登录状态并提示失败。

会话失效时原页面保留草稿、原请求正文和幂等键。点击“重新登录”打开新窗口，完成原账号登录后回到旧页面点击“恢复当前会话”；确认同租户、同 sub 后才恢复。不同账号不会接管旧页面。随后由用户主动恢复原操作，不自动继续提交或批准。CSRF_INVALID 与 401 均保留原请求恢复记录，避免响应丢失后生成新键重复写入。

## 验证范围

`OidcAuthenticationIntegrationTest` 通过本地 HTTP 身份夹具进行真实授权码交换、PKCE、RSA 签名和 JWKS 验证，覆盖签名、issuer、audience、nonce、过期、未映射身份、state、回调重放、会话轮换、CSRF、退出、跨账号请求绑定、原申请权限/租户隔离及作废幂等。测试可在 H2 或明确指定的隔离 PostgreSQL 上运行。

`OidcIdentityPolicyTest` 覆盖配置拒绝边界、声明类型、角色白名单和 HTTPS Cookie 属性。前端测试覆盖令牌与会话模式隔离、CSRF 更新和原键恢复。浏览器验收记录与执行结果以阶段 65 开发/验收记录为准。

协议夹具仅在 test classpath 中，可用于本机浏览器测试；它自动返回测试身份，不是可部署的身份服务，也不包含在发布 jar 中。

## 尚未完成

- 真实企业身份供应商及客户端联调，组织权威来源、目录同步和动态审批人解析。
- 多实例会话共享、后端重启后保留登录、全局退出与即时停用通知。
- 生产 HTTPS 代理、真实浏览器/设备与企业 SLO 验收。
- 平台其余财务、Agent、附件、SLA 与初始化能力。

## 协议依据

- [Spring Security OAuth2 Login 配置](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/login/advanced.html)
- [Spring Security 授权码与 PKCE](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/client/authorization-grants.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html)

工程实际依赖 Spring Security 6.5.5；在线 6.5 文档可能展示后续补丁版本，实际 API 已通过编译及协议测试验证。
