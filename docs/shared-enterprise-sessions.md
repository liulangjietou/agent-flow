# 企业多实例共享会话

共享会话解决后端实例切换或重启导致登录丢失的问题。调用链为浏览器 Cookie → Spring Session JDBC → Spring Security → `BearerAuthFilter` → 原资源授权。会话存储属于认证基础设施，审批聚合、幂等和业务事务保持原边界。

## 启用

先配置 [企业 OIDC 登录](enterprise-oidc.md)，再设置：

```yaml
agentflow:
  auth:
    demo-enabled: false
    session:
      jdbc-enabled: true
server:
  servlet:
    session:
      timeout: 30m
```

也可设置 `AGENTFLOW_JDBC_SESSIONS=true`。默认关闭，只允许与企业 OIDC 一起启用；错误组合在启动时拒绝。超时必须是正整数秒，读取 `server.servlet.session.timeout`。`spring.session.*` 自动配置未启用，不能用它替代以上配置。

所有实例必须连接同一数据库，使用相同发布版本、OIDC 客户端、身份映射和外部 HTTPS origin。固定回调地址指向统一入口，负载均衡不要求会话粘滞。生产使用 PostgreSQL，H2 只承担本机兼容验证。切换会话存储模式会要求原内存会话重新登录，不尝试搬迁其他进程内存。

## 存储与失效规则

- V21 仅增加 `AF_HTTP_SESSION`、`AF_HTTP_SESSION_ATTRIBUTES` 及索引，由 Flyway 管理，Spring Session 不自行建表。旧业务表与记录保持不变。
- 使用与 Spring Boot 3.5.6 配套的 Spring Session JDBC 3.5.2。会话序列化由框架负责；数据库只供受信应用访问，备份与恢复同样包含身份上下文。升级时须验证序列化兼容，不承诺任意跨版本滚动升级。
- 数据包括登录协议中的 state、nonce、PKCE 临时上下文，登录后的 ID Token、平台身份与 CSRF；不保存 access token、refresh token 或客户端密钥，不向普通业务 API 暴露会话表。
- Cookie 保持 `AGENTFLOW_SESSION`、HttpOnly、SameSite=Lax、Path=/；HTTPS origin 强制 Secure，不从请求头扩展 Domain。会话标识按原始字符串传输，不对旧容器标识作 Base64 解码；旧标识查无会话后可重新登录。
- 空闲到期或 ID Token 到期均拒绝业务请求。当前部署的 issuer、client-id、租户映射或角色映射不再匹配时，会话失效并要求重新登录。
- 任一实例上的平台退出会删除共享会话，其他实例之后的请求不能继续使用该 Cookie。已经进入业务处理的请求不会被远程回滚。平台退出不等于退出 IdP 的其他应用或该账号所有设备。
- 框架按分钟清理过期会话，清理前的逻辑过期检查仍生效。会话存取使用独立事务；存储不可用时不回退到演示账号或本地登录。

系统自检增加“登录会话存储”：启用时实际查询两张表；未启用、查询失败与已查通分别展示，不把配置开关当作连通证据。查询不读取会话内容或令牌。

## 验证

`SharedSessionIntegrationTest` 启动两个独立 HTTP 服务和一个本地身份夹具，执行真实授权码、PKCE、签名 Token 交换。覆盖跨实例回调及标识轮换、CSRF 保护、退出同步、后端重启、策略变化失效、数据库会话过期、租户隔离与页面身份绑定。

默认使用独立 H2 内存库；专用 PostgreSQL 可通过以下系统属性指定：

```text
agentflow.session-test.jdbc-url
agentflow.session-test.jdbc-driver
agentflow.session-test.jdbc-user
agentflow.session-test.jdbc-password
```

也可使用 `AGENTFLOW_SESSION_TEST_URL`、`AGENTFLOW_SESSION_TEST_DRIVER`、`AGENTFLOW_SESSION_TEST_USERNAME`、`AGENTFLOW_SESSION_TEST_PASSWORD` 环境变量；密码应通过受控环境传入，避免写进启动参数。测试会修改该专用库的会话时间并创建流程、申请和租约记录，**不得指向业务数据库**。迁移测试验证旧定义逐字段保留；配置测试验证认证组合、超时和 HTTPS Cookie。既有 OIDC 集成同时验证默认不开启共享仓储，避免引入依赖改变原运行模式。

当前 10 项用例还包括并发创建/提交/批准只生成一份业务结果、单节点停止后重放响应并继续审批，以及 Webhook 租约竞争和迟到确认；两个真实 JVM 容器的部署证据见[多实例验收](multi-instance-deployment.md)。

## 尚未覆盖的生产目标

真实企业 IdP、组织同步、账号即时停用、IdP 全局退出及目标企业集群仍需单独验收。本地代理和双实例协议/执行验证，不代表完整平台已经生产可用。

依据：[Spring Boot 3.5 会话配置](https://docs.spring.io/spring-boot/3.5/reference/web/spring-session.html)、[Spring Session JDBC](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html)、[Cookie 属性](https://docs.spring.io/spring-session/reference/3.5/configuration/common.html)。在线文档可能展示后续补丁，工程使用的具体 API 以编译和运行验证为准。

企业身份源可以显式接入 [后通道注销](oidc-backchannel-logout.md)，通过签名通知使对应登录跨实例失效。真实企业身份源联调与生产验收仍需完成。
