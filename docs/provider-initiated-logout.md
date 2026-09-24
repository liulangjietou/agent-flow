# 主动退出企业账号

平台同时提供本地退出和 OIDC RP-Initiated Logout。真实企业身份服务的行为仍需部署联调，不能以本地协议夹具代替验收。

## 启用与用户操作

先配置企业 OIDC 登录。可信发现文档提供 `end_session_endpoint` 时，工作台退出窗口显示“仅退出平台”“同时退出企业账号”和“取消”。未提供该元数据时保持原有本地退出行为。

退出端点必须符合现有 OIDC URL 策略：HTTPS、无用户信息、查询串或 fragment；仅显式启用本机测试时允许回环 HTTP。无效元数据会使启动失败，不忽略错误而展示虚假的可用入口。退出端点可以使用可信发现文档声明的不同域名，不根据浏览器请求或任意返回参数推测。

向 IdP 登记 `post_logout_redirect_uri`：配置的 `agentflow.web.allowed-origin` 加 `/`。例如 `https://flow.example/`，必须和平台注册信息准确匹配。平台不使用 Host、Forwarded 或 returnUrl 生成地址。

“仅退出平台”保持 `POST /api/v1/auth/logout` 的原有 204 行为。“同时退出企业账号”会先提示可能影响其他企业应用，执行未保存设计保护，读取最新 CSRF 并核对当前账号，再以浏览器表单提交：

```text
POST /api/v1/auth/oidc/logout/enterprise
Content-Type: application/x-www-form-urlencoded

_csrf=<当前会话防伪令牌>&actor=<JSON 数组 [租户,用户]>
```

具体表单参数名来自 `GET /api/v1/auth/options` 的 `csrfParameter`。同一响应仅在支持时提供 `providerLogoutUrl`。业务前端不读取或持久化身份令牌。

## 认证与协议边界

服务端在清理会话前拒绝 GET（405）、缺失/错误 CSRF（403）、匿名会话（401）、不支持能力、页面账号不一致、配置变更前的 issuer/client 会话（409）。错误响应不包含身份令牌。退出业务不进入审批、财务或流程领域事务。

通过校验后，Spring Security 销毁平台共享会话和 Cookie，再使用 `OidcClientInitiatedLogoutSuccessHandler` / `FormPostRedirectStrategy` 输出自动提交的 HTML 表单。表单向可信身份源 POST `id_token_hint` 和固定 `post_logout_redirect_uri`，参数经过 HTML 转义，脚本使用 CSP nonce；响应禁止缓存并设置 `Referrer-Policy: no-referrer`。身份令牌不进入浏览器地址栏或业务 JSON 响应。

本地会话清理不依赖身份源在线。进入身份服务后，它可能要求用户确认、拒绝或因网络不可达而失败；这些情况不能表示全局退出成功。返回平台只显示登录页，不根据可伪造的回跳参数认定身份源已完成注销。返回地址不执行业务动作，因此没有额外注销完成状态或回调授权。

其他平台会话是否失效取决于 IdP 的注销通知；需要时同时配置[后通道注销](oidc-backchannel-logout.md)。此操作不保证任意其他应用都支持全局注销，也不替代企业账号停用、组织同步或权限撤销。

## 验证

`OidcProviderLogoutIntegrationTest` 使用真实 RSA/JWKS、授权码、两个 HTTP 实例和共享数据库，校验 CSRF、账号绑定、固定返回地址、平台会话失效以及身份服务实际接收 POST。默认 H2，可通过 `agentflow.provider-logout-test.jdbc-url`、`jdbc-driver`、`jdbc-user`、`jdbc-password` 在独立 PostgreSQL 上运行。

协议依据：[OpenID Connect RP-Initiated Logout 1.0](https://openid.net/specs/openid-connect-rpinitiated-1_0.html)、[Spring Security OIDC Logout](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html)。
