# 外部通知投递

当前增量实现 SMTP 邮件后台、部署收件绑定、固定目的地、有限重试和结果未知保护。个人投递查询、人工恢复的公开接口与页面、实际运行库升级／重启验收、企业 IM、超时升级仍在接续，不能据此把通知工作包标为完成。

## 调用链与职责

审批、任务、评论和抄送继续调用 `InboxRepository.append`。首次站内事件写入后，`NotificationDispatchPlanner` 读取接收人的当前偏好，并在同一事务冻结原渠道代次及可用部署绑定的标识与摘要。没有绑定也会保留意向，便于后续报告未配置；以后新增绑定不能补发该条旧意向。

`NotificationDeliveryProgress` 负责发送状态、次数、租约与允许的状态变化。`NotificationDeliveryService` 编排当前组织状态、本人偏好、原消息归属和绑定复核；`JdbcNotificationDeliveryStore` 只保存队列及历史。后台先通过服务短事务记录开始发送，再在事务外调用 SMTP，最后用另一短事务保存受理或失败事实。

服务按组织目录、个人偏好、投递的顺序加锁，与关闭设置的锁顺序一致。入队不同时锁住多个接收人的偏好，避免审批通知扇出时出现锁顺序交叉。并发入队读到的旧代次会在发送前再次被拒绝。

启用的本地组织目录决定人员是否仍有效；没有组织目录时只允许明确启用的演示账号。通知资格不要求审批角色，普通员工和出纳也可接收自己的提醒。后台不伪造 OIDC 角色或业务读取授权。

## 外发内容与固定绑定

邮件只有固定主题、通用消息提醒和部署方固定的登录地址。标题、业务编号、表单、评论、审批状态、附件和金额都不复制。登录后仍通过消息中心及业务原入口检查当前权限。

每个租户、稳定主体和渠道只能配置一个绑定。SMTP 服务器也归属租户，不能引用其他租户服务器。绑定摘要覆盖收件账号、服务器地址、端口、发件地址、认证账号、传输安全模式和登录链接；密码轮换不改变目的地身份。地址或服务器身份变化后，旧投递不得转投新配置。配置对象的字符串表示不输出地址或凭据，数据库仅保存绑定标识与摘要。

普通用户的偏好接口不接受邮件地址、服务器或身份覆盖。收件地址必须是一个明确的裸邮箱地址，不接受显示名、组、多个地址或换行头部。

## 发送事实与恢复边界

| 状态 | 含义 | 自动后续 |
| --- | --- | --- |
| `PENDING` | 等待当前资格和固定绑定检查 | 到期后领取 |
| `IN_FLIGHT` | 已提交开始发送事实，正在执行或等待回执保存 | 60 秒租约过期进入未知 |
| `RETRY_WAIT` | 连接前失败或 SMTP 明确临时拒绝 | 最多三次，间隔 30、120 秒 |
| `ACCEPTED` | SMTP 服务器已接受邮件 | 不自动重复发送 |
| `FAILED` | 明确永久拒绝、绑定不可用或安全重试次数耗尽 | 等待明确人工处理 |
| `UNKNOWN` | 发送后断连、超时或领取租约到期 | 禁止自动重发 |
| `SUPPRESSED` | 同意撤销、人员停用、消息归属不符，或旧无绑定意向被升级抑制 | 不复活 |

SMTP 受理后仍可能发生后续递送失败，不能将 `ACCEPTED` 展示为“已送达”。最终 DATA 回执丢失可能导致发送者无法判断是否已经受理，重复发送可能形成重复邮件，参见 [RFC 5321 第 6.1 节](https://www.rfc-editor.org/rfc/rfc5321.html#section-6.1)。原投递的 `Message-ID` 保持不变，只用于追踪，不能承诺远端去重。

人工重试的服务端编排保持原编号、原同意代次和原绑定，要求当前版本。对 `UNKNOWN` 必须明确接受可能重复；已受理、已抑制、当前同意撤销或目的地变化均拒绝重试。原因与操作人追加到原投递历史。**这一增量尚未开放上述人工操作的 HTTP 接口和页面。**

关闭偏好会抑制尚未开始的 `PENDING`、`RETRY_WAIT`，但不能召回已经开始的网络操作。进行中的真实受理回执仍可保存；如果该次结果需要重试，下一次开始前会复核并抑制。失效租约的迟到回执不能覆盖随后记录的未知或人工处理结果。

## 部署配置

默认 `agentflow.notifications.delivery-worker-enabled=false`，不自动外发。先配置服务器、明确收件绑定和应用登录地址，再显式开启轮询。个人邮件偏好也必须开启，且不会补发之前的站内消息。

```yaml
agentflow:
  notifications:
    delivery-worker-enabled: false
    public-url: https://approval.example.invalid/
    smtp-servers:
      company-mail:
        tenant-id: example-tenant
        host: smtp.example.invalid
        port: 587
        security: STARTTLS
        username: ${NOTIFICATION_SMTP_USERNAME}
        password: ${NOTIFICATION_SMTP_PASSWORD}
        from: notify@example.invalid
        enabled: true
    bindings:
      employee-mail:
        tenant-id: example-tenant
        recipient: stable-identity-subject
        channel: EMAIL
        server-id: company-mail
        address: employee@example.invalid
        enabled: true
```

生产允许 `STARTTLS` 或直接 `TLS`，使用 JVM 信任库并检查服务器证书主机名；STARTTLS 不可用时拒绝回退到明文。连接、读、写分别设置 2、3、3 秒超时，遵循 [Angus SMTP 属性说明](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)。这些是单项 I/O 超时，不是整次投递的总耗时承诺。

本地协议测试可同时启用演示认证和 `allow-insecure-in-demo=true`，使用 `DEMO_PLAIN`，只允许字面回环地址 `127.0.0.1` 或 `::1`。生产认证配置下不接受该模式。当前没有企业 IM 发送器，不能用邮件服务器冒充 IM 渠道；此类配置会在启动时拒绝。

## 数据升级与验收

V93 在原意向表追加固定绑定、版本、次数、租约和错误分类，并新增 `notification_delivery_event`。V92 的旧 `PENDING` 没有入队时的绑定，升级时标记为 `SUPPRESSED / BINDING_NOT_CAPTURED`；原已抑制意向保持抑制。升级不会推断旧消息的收件账号，也不改写站内消息和偏好。

8 项领域、61 项 H2 服务端范围和 16 项 PostgreSQL 验证通过；PostgreSQL 包含相同的 9 项投递集成、1 项非空迁移及 6 项偏好回归，不与 H2 相加。真实回环 SMTP 覆盖受理、收件拒绝、最终 DATA 拒绝、收完数据后断连或超时、认证拒绝和禁止 STARTTLS 降级；并发领取、当前同意与归属复核、原租约隔离、本人恢复、历史回滚也已验证。首次迁移夹具曾因 H2 schema 大小写失败，改用明确引用的 schema 和 JDBC `setSchema` 后范围重新通过。

本次没有修改前端或 API 契约，也没有升级既有长期验收运行库。真实企业 SMTP、TLS 服务器证书、实际最终递送和完整页面／运行环境仍单独验收。证据见[后台验证](evidence/notification-delivery-backend-20261001.json)，完整平台仍以 [当前剩余工作](remaining-local-work.md) 为准。
