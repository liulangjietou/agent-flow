# 付款前账户依据

更新：2026-09-28。新增 `PaymentAccountsPort` 与真实 HTTP 适配器，供后续持久付款授权和出纳执行使用。该端口只读取账户依据；不生成付款授权、不执行支付、不创建借款到账余额。

## 查询范围和下游协议

调用方是付款准备与执行服务，先从已保存授权取得原租户、法人、币种、申请人和财务目标，并从认证身份确定出纳。事务外通过 `GatewayPaymentAccounts` 读取两类事实：

| 财务网关路径 | 请求 `data` | 返回 `data` |
| --- | --- | --- |
| `debit-accounts` | `legalEntityId, currency, cashierId` | `request, sourceVersion, observedAt, validUntil, accounts` |
| `employee-account` | `legalEntityId, employeeId` | 既有 `snapshot, validUntil`，精确匹配原申请人和法人 |

每次读取有新的传输请求号，不带副作用幂等头。二者均必须传原目标摘要；配置目的地改变时返回 `TARGET_CHANGED`，不去另一个资金系统取证。凭据轮换仍沿用原目标。通用 `read` 禁止出款目录，新专用入口只允许上述两个只读操作；预算和付款命令不能经此入口执行。

目录中每个账户包含稳定 `reference`、`displayName`、`maskedAccount`、`currency`、`sourceVersion`。引用须由资金系统维护且不能改指另一真实银行账户；关闭旧账户后应禁用旧引用并使用新引用。平台不保存或接收完整银行卡号，也不构造企业默认出款账户。

目录精确绑定法人、币种及出纳，最多 200 个唯一引用。所有账户必须同币种；掩码最多显示首尾各四位且必须包含遮罩。目录在 `[observedAt, validUntil)` 内可用，未来事实、过期、重复、缺字段、超限或额外字段均被拒绝。空目录保持为空，不能代替业务拒绝或自动选择默认账户。

业务拒绝只允许 `LEGAL_ENTITY_UNAVAILABLE`、`CASHIER_UNAVAILABLE`、`DEBIT_ACCOUNT_UNAVAILABLE`。网络、认证、超时、协议错误与未配置仍使用既有不可用分类。外部系统仍须在实际接收付款时原子复核账户、原授权和凭证，短时有效的只读依据不能消除跨系统变更窗口。

## 验证和后续

新增领域 3 项、真实回环 HTTP 5 项通过；包含付款命令、支付适配器、会计适配器和网关配置的范围回归共 57 项通过。覆盖错误出纳／法人／币种、空目录、到期边界、重复引用、完整卡号、目标变化、事务内禁止网络调用以及只读入口防止写命令旁路。

此阶段没有数据库迁移或对外业务 API；付款授权、账户复查持久任务、出纳执行及资金结算继续开发。合成回环服务不代表企业资金系统接入。测试日志：`/fyoung/tmp/agentflow-remaining-20260928/payment-accounts-tests.log`。
