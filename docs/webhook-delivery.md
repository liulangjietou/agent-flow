# Webhook 可靠投递

当前版本可将提交、撤回、作废及任务操作、最终审批结论发送到部署方配置的接收服务。管理员从侧栏“集成投递”查看状态、尝试历史并主动重试。默认没有目的地，不发送 HTTP，也不补发启用前的历史审计。

## 配置与接入

仅部署配置可提供目的地；API 和页面不接受 URL、密钥、租户覆盖。最多 20 个目的地，每项精确绑定一个租户。生产模式始终要求 HTTPS，不跟随重定向；URL 禁止用户信息、查询参数和片段。企业内网服务可以由部署方明确配置，网络出口、DNS 和地址访问控制由部署环境限制；这不是面向普通用户开放的任意 URL 订阅服务。

```yaml
agentflow:
  webhooks:
    targets:
      erp:
        tenant-id: your-tenant
        label: 企业业务系统
        enabled: true
        url: https://receiver.example.org/agentflow/events
        signing-secret: ${ERP_WEBHOOK_SECRET}
```

密钥使用 `whsec_` 加标准 Base64 编码的 32–64 字节随机数据。每个接收端独立密钥，通过部署密钥管理分发，禁止放进 Git。配置在启动时校验并冻结，调整后重启；不要在命令行直接放密钥。页面只返回目的地标识、名称与启用状态。签名密钥不写入数据库、API 响应或业务日志。

如使用 Spring Boot 环境变量绑定，示例为 `AGENTFLOW_WEBHOOKS_TARGETS_ERP_TENANTID`、`AGENTFLOW_WEBHOOKS_TARGETS_ERP_SIGNINGSECRET`，URL、LABEL、ENABLED 同理。仅在演示认证开启且显式设置 `agentflow.webhooks.allow-insecure-http-in-demo=true` 时允许本地 HTTP 接收器；正式模式无法通过此开关允许 HTTP。

## 事件契约

| eventType | 触发条件 |
| --- | --- |
| ApplicationSubmitted | 一轮审批提交成功，包括重提 |
| ApplicationWithdrawn | 审批中申请撤回成功 |
| ApplicationCancelled | 允许作废的申请作废成功 |
| TaskActionAccepted | 实际任务动作成功，包括认领、释放、转办、委派、回交、同意、退回、驳回 |
| ApplicationApproved | 申请真实转为 APPROVED |
| ApplicationReturned | 申请真实转为 RETURNED |
| ApplicationRejected | 申请真实转为 REJECTED |

会签中部分成员同意只产生任务事件，全部同意才产生申请批准事件；任一驳回结束整轮。创建或修改草稿不外发。事件不包含表单正文、审批意见、附件或目的地凭据。

```json
{
  "eventId": "ddf4d655-3843-4ef1-b4f6-b53704aa9c74",
  "eventType": "ApplicationSubmitted",
  "tenantId": "your-tenant",
  "aggregateType": "Application",
  "aggregateId": "09e161c6-5997-453a-86d5-949691df157d",
  "aggregateVersion": 2,
  "occurredAt": "2026-09-23T17:00:00Z",
  "traceId": "ddf4d655-3843-4ef1-b4f6-b53704aa9c74",
  "payloadVersion": 1,
  "payload": {
    "applicationId": "09e161c6-5997-453a-86d5-949691df157d",
    "roundNo": 1,
    "actor": "alice",
    "action": "SUBMIT",
    "previousStatus": "DRAFT",
    "currentStatus": "IN_APPROVAL"
  }
}
```

任务事件的 `aggregateType=Task`、`aggregateId=taskId`，payload 额外包含 `taskId`。由任务动作产生的申请结论也包含该任务标识。`aggregateVersion` 当前均为关联申请版本。`traceId` 是源操作的审计事件 ID，用来关联同次操作的任务事件与申请结论，不是跨系统分布式追踪上下文。

正文和 eventId 入库后不再改变；每次重发使用新的发送时间戳。传输遵循 [Standard Webhooks 的对称签名格式](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)：

- `webhook-id`：eventId。
- `webhook-timestamp`：发送时的 Unix 秒数。
- `webhook-signature`：`v1,` 加 HMAC-SHA256 结果的标准 Base64。
- 签名输入为 `eventId.timestamp.` 的 UTF-8 字节后接原始 UTF-8 请求体，密钥先去掉 `whsec_` 再 Base64 解码。

接收端先校验时间窗口与原始字节签名，使用恒定时间比较，再核对 tenantId、事件 ID 与 payloadVersion，最后在自己的业务事务中原子提交去重记录及处理结果。不能解析再重组 JSON 后验签。重试可能晚于五分钟，时间窗口检查针对本次发送时间；eventId 去重记录应按业务追溯期持久保留，不能只缓存五分钟。

当前发送端每次只用一个签名密钥。轮换时接收方先同时接受新旧密钥，再更新发送端并重启，等待旧的在途请求完成后移除旧密钥。地址摘要不包含密钥，因此轮换不会改变事件目的地。

## 事务、并发与失败语义

DDD 的 `DeliveryProgress` 管理领取、确认、自动退避、租约过期及人工重试规则。`ApprovalWebhookEvents` 在两个审计适配器接收到操作事实后生成精简事件；`JdbcWebhookStore.append` 要求已经存在事务，和 Flowable、申请、轮次、审计共享数据源。任何入队失败都回滚整个审批动作；已经提交后的 HTTP 失败不修改审批状态。

V17 新增 `webhook_delivery`、`webhook_attempt`、`webhook_retry_request`。投递表兼作每个目的地的持久 outbox，不另建尚无第二种消费者的通用消息总线。每批最多领取 10 条，版本条件更新保证同一记录同一时刻只有一个有效租约。30 秒租约过期后可以重领，原尝试标记 OUTCOME_UNKNOWN；旧响应无法覆盖新租约。六次租约都失联会终止当前轮次，不无限重发。

| 状态 | 含义 |
| --- | --- |
| PENDING | 已排队，尚未开始本轮尝试 |
| IN_FLIGHT | 已领取，等待网络结果 |
| RETRY_WAIT | 可重试失败，等待到期 |
| DELIVERED | 接收服务返回 HTTP 2xx |
| FAILED | 不可自动重试，或本轮次数已耗尽 |

连接上限 2 秒、请求超时 5 秒、包含响应体读取在内的等待上限 6 秒。响应体丢弃，不记录外部响应内容。408、425、429、5xx 和网络异常可重试；3xx 与其他 4xx 停止本轮。初次发送后最多再自动尝试 5 次，间隔分别为 5 秒、30 秒、2 分钟、10 分钟、1 小时，按前一次确认失败的时间计算。当前未实现 Retry-After、自适应限流或全局熔断。

至少一次投递意味着重复是正常情况，网络超时、连接中断、租约失联都不证明接收方未收到。不保证到达顺序，也不承诺外部业务“恰好执行一次”。2xx 仅确认接收，不能据此认为外部入账、预算、支付已经完成。

目的地标识、租户与规范 URI 的摘要随事件保存。目的地移除或停用标记 TARGET_UNAVAILABLE，修改地址标记 TARGET_CHANGED，不把积压业务数据转发到新地址。人工重试同样拒绝上述情形；需要恢复原配置后按原目的地处理。没有面向新 URL 的历史迁移或自动补发入口。

## 管理页面与 API

所有接口要求认证租户内的 ADMIN，PROCESS_ADMIN 不自动获得权限。跨租户资源返回 404。成功响应 `Cache-Control: no-store`。

- `GET /api/v1/integrations/webhooks`：当前租户的目的地名称与状态。
- `GET /api/v1/integrations/webhooks/deliveries`：按 target、status、applicationId 精确筛选。默认 30、最多 100；cursor 绑定租户、账号、角色及原筛选，未知参数拒绝。
- `GET /api/v1/integrations/webhooks/deliveries/{id}`：摘要、最近 50 次尝试、最近 20 条人工请求；累计尝试次数另行披露，不把已加载数量当全库总数。
- `POST /api/v1/integrations/webhooks/deliveries/{id}/retry`：正文 `{"expectedVersion":3}`，必须使用 `Idempotency-Key`。仅 FAILED、DELIVERED、RETRY_WAIT 可以重新排队；PENDING、IN_FLIGHT 或版本变化返回 409。

人工重试开启新的最多六次尝试，累计尝试与人工记录不清零。同一幂等键回放原排队响应，不表示当前投递仍处于 PENDING。页面先显示确认说明；响应未知时全局保留原请求，用户主动“恢复上次操作”使用原键、原正文。退出再登录同一账号可继续恢复，但刷新或关闭整页会丢失仅在页面内存中的恢复槽，此时应先查投递现态。

列表为读取时的状态，不自动轮询；详情可主动刷新。分页不是数据库快照，状态变化期间筛选结果可能变化；刷新查询可取得最新记录。详情不暴露事件体、签名头、URL、密钥或租约 token。

## 本地接收示例与验收

`scripts/webhook_receiver.py` 使用标准库，默认仅监听 127.0.0.1，验证五分钟时窗与签名，SQLite 持久化 eventId 去重，并拒绝相同 ID 的不同正文。它只用于演示接入，不执行任何财务业务。

从受保护的部署环境提供 `AGENTFLOW_RECEIVER_SECRET`，与演示目标密钥一致，然后运行：

```sh
python3 -B scripts/webhook_receiver.py --database /fyoung/tmp/agentflow-receiver.sqlite --port 8091 --fail-first
python3 -B scripts/test_webhook_receiver.py
python3 -B scripts/check_webhooks.py http://127.0.0.1:8082 --exercise
```

`--fail-first` 对每个事件第一次返回 503，用于验证实际重试。联调脚本只允许显式独立本机入口，拒绝 8080 / 8180；会新增一份真实演示申请，执行批准并验证三种事件、自动重试、一次人工重试和幂等回放，保留数据。不要将此脚本指向正式环境。

当前范围尚不包含企业身份与组织接入、邮件/IM、预算和支付适配器、事件订阅条件、批量重放、投递数据归档、容量压测及生产告警；这些不能由当前模块的通过测试推断为已完成。
