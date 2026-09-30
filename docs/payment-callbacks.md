# 支付签名回调

回调只表示资金系统提示原交易可能发生变化。平台验签后持久接收，再由原员工付款或供应商付款工作器查询原授权号和命令摘要。接收回执、查询登记、银行事实及本地结算是不同事实。

## 调用链与职责

- 调用方：部署配置的资金网关发送 `payment.changed`；管理员检查收件箱并对需要处理的记录明确重试。
- 下游：原始字节验签 → 原申请及财务来源锁 → V74 收件箱与追加历史 → 原付款查询排队 → 现有事务外资金工作器 → 原付款领域的单调版本及争议处理。
- 影响场景：重复、过期、乱序和迟到投递，发送中回调，查无后的明确重发，旧授权结束后的矛盾银行结果，以及供应商 ERP 核销完成后的银行复查。
- 接入层负责认证和协议校验，收件箱负责持久恢复，付款实体负责状态迁移；回调服务不直接登记到账、改账户、释放占用、生成授权或覆盖审批结果。

## 签名与部署

默认关闭 `agentflow.payment-callbacks.enabled`。启用时，必须已配置同租户的 `agentflow.finance-gateway`，并配置 `agentflow.payment-callbacks.tenants.<tenant>.signing-secrets`。密钥格式为 `whsec_` 加标准 Base64，解码后为 32–64 字节。每个租户允许 1–2 把密钥，用于当前密钥与上一把密钥的轮换；从部署密钥存储注入，页面和接口不接受密钥覆盖。

协议使用 [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md) 的 `v1` HMAC-SHA256。正文不重新序列化，签名输入为原始字节：

```text
webhook-id + "." + webhook-timestamp + "." + raw_body
```

`POST /api/v1/integrations/payment/callbacks` 要求以下单值请求头：

| 头 | 约束 |
| --- | --- |
| `webhook-tenant` | 选择配置的租户密钥；必须与已签名正文中的 `tenantId` 一致 |
| `webhook-id` | 1–128 个字母、数字、下划线或短横线；同事件重试保持原值 |
| `webhook-timestamp` | Unix 秒；与平台接收时间相差不超过 300 秒，每次投递重新签名 |
| `webhook-signature` | `v1,<Base64 HMAC>`；允许最多 4 个空格分隔的版本签名，至少一个已配置密钥的 `v1` 匹配 |
| `Content-Type` | `application/json`，可显式声明 UTF-8 |

拒绝重复头、压缩体、非 UTF-8 字节、超过 8,192 字节的正文、未知字段、重复 JSON 属性、尾随 JSON、数值强制转换和错误业务身份。正文在验签前不进行 JSON 解析；签名采用常量时间比较。Bearer 和 OIDC 用户会话不能替代签名。只有精确接收 POST 路径免于用户会话及 CSRF；管理 GET 和重试 POST 仍使用原用户认证、角色和 CSRF 保护。

最小信号如下，其中 `EMPLOYEE` 同时涵盖员工借款和报销付款，`SUPPLIER` 指向原供应商银行指令：

```json
{
  "contractVersion": 1,
  "type": "payment.changed",
  "tenantId": "demo",
  "kind": "SUPPLIER",
  "authorizationId": "09ce297b-0811-4015-a135-eae7ed1cc2d1",
  "commandDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceRevision": 2
}
```

该示例摘要不是可用于实际验收的命令摘要。发送方必须回传原命令的真实授权号和摘要。信号中的外部版本仅用于识别已观察或乱序信号，不能覆盖银行查询结果。

## 接收与恢复

同租户事件号唯一；只有事件号、原始正文摘要、业务身份和固定目的地均一致才视为重复。重复投递返回相同接收编号，不再次排队查询；同事件号变更正文返回 409。密钥或请求时间戳不进入持久记录，合法密钥轮换后仍可重投原正文。

V74 新增 `payment_callback`、`payment_callback_revision`。员工或供应商来源通过租户内外键绑定；`query_version` 引用实际已保存的原付款修订。接收记录和历史、查询登记和处理完成分别原子提交；进程崩溃后继续处理原收件记录，不创建付款。

| 收件箱状态 | 含义 |
| --- | --- |
| `RECEIVED` | 已验签并持久接收，等待本地处理 |
| `WAITING` | 原网络执行未结束或本地处理暂时失败，保留原信号等待恢复 |
| `QUERY_QUEUED` | 已登记原号查询，保存实际付款排队版本；不代表查询已结束或资金已到账 |
| `REVIEW_REQUIRED` | 从未发送、目的地变化或本地处理连续失败，需要核对后明确恢复 |

正在银行发送或查询的领取不会被回调打断。已经人工点击查无重试但尚未外发的队列或账户检查，可被可信信号切回原号查询；旧检查领取因此失效，不能继续发送。此前从未发送过的指令进入待处理记录，回调不能授权其首次支付。

本地失败最多退避 10 次，间隔上限 300 秒；管理员须填写原因、携带期望版本和幂等键，才可重新处理原信号。单条读取异常不会中断本批其他记录。原目标变化后不能转发旧查询到新地址。

旧付款安全结束时仍须核对当时的最新付款版本；历史读取固定核验该结束修订，不要求它永远等于之后的银行查询版本。已经明确失败并结束的授权，迟到成功结果进入原交易争议，保留失败与成功两份事实，不恢复授权、不新增借款余额。

## 管理接口

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/integrations/payment/callbacks?limit=25&beforeId=...` | 当前租户 ADMIN 的有界收件箱，默认 25、最多 100 条 |
| `GET /api/v1/integrations/payment/callbacks/{id}` | 最多 50 次处理历史 |
| `POST /api/v1/integrations/payment/callbacks/{id}/retry` | `REVIEW_REQUIRED` 的原信号恢复；请求包含 `expectedVersion`、`reason` |

管理员只读运行元数据，不获得原金额、账户、命令、签名或申请字段。银行回调的 `202` 与管理员重试的 `200` 都不代表付款成功。

## 当前验收边界

已完成后台协议、持久队列、原付款查询、管理员 API 和迁移验证。验签及两个付款领域范围 46 项；H2、OIDC 与回环 HTTP 范围 101 项；PostgreSQL 实际数据库范围 66 项通过，测试范围有重叠。晚到回调导致历史结束读取失败的缺口先由失败用例复现，再修正其历史证据校验。单条读取异常阻断其他回调的边界也先复现再修复。

管理员页面、既有 V73 验收库的实际升级和浏览器验收继续实施。本地合成银行不代表真实企业资金系统已接入，整个项目仍有剩余工作。
