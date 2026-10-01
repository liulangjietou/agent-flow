# 企业微信应用通知

本地接入企业微信自建应用作为 `ENTERPRISE_IM` 的参考发送器，沿用原消息队列、个人偏好、目的地冻结和本人恢复接口。真实企业的应用可见范围、账号绑定、出口 IP、凭据及最终递送仍需独立验收。适配器不把协议夹具受理记作真实企业送达。

## 调用链和职责

业务入口继续通过 `InboxRepository.append` 写入站内消息；`NotificationDispatchPlanner` 在同一事务记录接收人的原渠道代次、绑定标识和摘要。`NotificationDeliveryService` 在领取前复核当前人员、原同意、消息归属和原绑定。`NotificationDeliveryWorker` 在事务外选择 SMTP 或企业微信发送，再通过原短事务保存结果。

企业微信配置及单人地址由 `NotificationDestinations` 在启动时校验。`WeComNotificationTransport` 只负责应用令牌缓存、HTTP 协议和回执分类；发送状态、有限重试和未知停发仍归领域对象 `NotificationDeliveryProgress`。没有新表或迁移，数据库版本保持 V93。邮件的原摘要编码顺序保持，升级不能把原邮件队列变成目的地已变更。

## 明确的企业和个人绑定

```yaml
agentflow:
  notifications:
    delivery-worker-enabled: false
    public-url: https://approval.example.invalid/
    wecom-apps:
      company-approval:
        tenant-id: example-tenant
        corp-id: ${AGENTFLOW_WECOM_CORP_ID}
        agent-id: ${AGENTFLOW_WECOM_AGENT_ID}
        secret: ${AGENTFLOW_WECOM_SECRET}
        enabled: true
    bindings:
      employee-im:
        tenant-id: example-tenant
        recipient: stable-oidc-subject
        channel: ENTERPRISE_IM
        server-id: company-approval
        address: Employee001
        enabled: true
```

企业绑定由部署方管理，普通用户只能修改自己的渠道开关。`server-id` 对邮件引用 SMTP 服务器，对企业 IM 引用同租户自建应用。收件账号遵循官方 UserID 字符范围，长度 1–64、首字符为字母或数字，禁止群发标记、部门、标签和分隔多个用户。规则来源：[创建成员](https://developer.work.weixin.qq.com/document/path/90195)。

摘要固定租户、稳定主体、渠道、绑定标识、UserID、应用配置标识、企业 ID、应用 ID、API 入口和登录链接。密钥轮换不会改变目的地；更换其他身份信息后，旧消息无法转投。旧无绑定意向不能在补配置后追溯发送。配置和缓存对象的字符串表示不包含凭据或地址，数据库和本人接口不保存令牌、企业返回正文及远端消息标识。

生产 API 入口固定 `https://qyapi.weixin.qq.com`。不接受其他域名、用户信息、路径、查询串或重定向。协议测试只有同时开启 demo 和 `allow-insecure-in-demo` 时，才接受带端口的 `http://127.0.0.1` 字面回环入口。不能用该例外连接外部企业服务。

## 最小内容及令牌

SMTP 和企业 IM 共用通用提醒正文与固定登录链接，发送器不接收申请标题、编号、金额、表单、评论或附件。企业微信请求只指定一个 `touser`，不启用 ID 转译或按相同正文合并消息；不同站内事件不能因为提醒文字相同而被吞掉。完整正文按 UTF-8 限制在 2,048 字节内，启动时发现链接过长即拒绝配置。协议依据：[发送应用消息](https://developer.work.weixin.qq.com/document/path/90236)。

令牌缓存区分企业、应用及凭据，同一应用并发首次发送只获取一次。缓存按响应有效期提前刷新，不持久化到数据库。收到明确的 `40014`／`42001` 拒绝时失效原缓存，同次发送最多刷新一次；第二次仍无效即停止。没有收到明确拒绝时不通过刷新令牌重发。依据：[获取 access_token](https://developer.work.weixin.qq.com/document/path/91039)及[全局错误码](https://developer.work.weixin.qq.com/document/path/90313)。

## 回执和恢复

| 情况 | 记录 | 后续 |
| --- | --- | --- |
| 获取令牌失败或其响应损坏，尚未调用发送接口 | `IM_TOKEN_UNAVAILABLE` | 使用原队列有限重试 |
| 凭据或刷新后的令牌仍被明确拒绝 | `IM_AUTH_FAILED` | 核实配置后本人恢复 |
| 服务繁忙或明确限流 | `IM_TEMPORARY_REJECTION` | 原队列最多三次，间隔 30、120 秒 |
| 原用户非法、无应用范围或无许可 | `IM_RECIPIENT_REJECTED` | 核实原绑定与权限后本人恢复 |
| 其他明确非零业务拒绝 | `IM_PERMANENT_REJECTION` | 人工核实 |
| 提交后超时、非成功 HTTP、损坏／超限／不相符响应 | `IM_RESULT_UNKNOWN` | 停止自动发送，恢复必须接受可能重复 |
| 完整成功回执，包含消息 ID 且没有无效收件人 | `ACCEPTED` | 仅表示服务器受理，不代表阅读或最终递送 |

成功码不能掩盖无效收件人列表。这里只发送一个用户：拒绝列表对应原用户时记录明确失败，若列出其他对象或字段格式损坏则保留未知。HTTP 不跟随重定向；单次完整请求等待最多 4 秒，响应体接收时限制 16 KiB，慢响应体也计入时限。JSON 通过 `JsonUtil.readStrict` 验证完整文档，拒绝尾随对象、重复键及非法 UTF-8。

人工恢复、同意撤销、旧代次拒绝、租约到期及迟到回执隔离均沿用[外部通知投递](notification-delivery.md)。本人页面继续显示通用“企业 IM”渠道和固定中文错误说明；管理员不能查看或恢复他人的记录。

## 当前验收边界

本轮先验证本地后台、真实回环 HTTP、持久队列、公开接口和现有页面契约。独立运行实例、真实浏览器、在途记录重启接续及企业环境联调分别记录进度，不能由单元测试推定完成。完整剩余范围以[当前清单](remaining-local-work.md)为准。

后台范围证据见[验收记录](evidence/wecom-notifications-backend-20261001.json)：领域 5 项、H2 服务端去重 54 项、独立 PostgreSQL 27 项、前端 25 项及构建和 OpenAPI 通过。范围有重叠，不据此计算平台完成比例。尾随 JSON 的误受理先用失败用例复现，根因是普通 JSON 读取允许尾随内容；旧用例只覆盖字段缺失与类型，未覆盖完整文档边界，现由传输用例补齐。
