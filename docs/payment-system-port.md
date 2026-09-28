# 支付端口与原交易查询

更新：2026-09-28。本阶段实现借款与报销共同使用的领域付款命令、外部事实契约和真实 HTTP 适配器。尚未开放付款执行 API，也未生成付款授权、调用银行或创建已放款余额；借款申请、凭证、授权、持久执行、回调与结算继续实施。

## 调用链与归属

调用方将是财务应用服务持久执行器，先在本地事务核对已批准的申请、业务版本、已入账凭证、付款账户和岗位权限，再持久化命令。事务外调用 `PaymentSystemPort`，由 `GatewayPaymentSystem` 经已有 `FinanceGatewayClient` 访问当前租户明确配置的财务系统。账户主数据由外部系统维护，页面不接受完整银行账号。

`PaymentCommand` 持有原授权号、租户、用途、申请与业务绑定、金额、出款账户引用、本人收款账户快照、凭证引用及授权/执行身份。借款和费用付款是两个实际用途；金额必须大于零，零应付报销由结算流程处理。申请人、授权人和执行人三者分离，身份是否具备实际岗位权限仍由应用入口验证，构造一份命令不等于获得付款权限。

授权有效区间为 `[authorizedAt, expiresAt)`。过期时适配器在发送前抛出 `PAYMENT_AUTHORIZATION_EXPIRED`，没有对外请求，也不伪造外部失败回执。已发送交易过期后仍可查询；异步资金系统在授权期内受理而后续完成的到账结果不能因查询发生在到期后被丢弃。

## 固定外部协议

沿用 [财务网关](finance-gateway.md) 的租户配置、HTTPS/Bearer、2 MiB 响应上限、严格 JSON、无重定向、事务外执行和无自动重试。新增两条固定路径：

| 路径 | 请求编号与请求体 | 行为 |
| --- | --- | --- |
| `payment-command` | `requestId` 和 `Idempotency-Key` 均为持久授权 UUID；`data={command,commandDigest}` | 首次执行或经后续明确恢复决策重发完全相同的命令 |
| `payment-query` | 每次新的传输 UUID；`data={authorizationId,commandDigest}`，没有幂等头 | 查询原授权的权威事实，不隐式支付或创建另一交易 |

两者必须传入持久化的目标摘要；凭据轮换不改变目标，端点变化返回 `TARGET_CHANGED`。通用 `read` 不允许预算或支付命令与交易查询，避免绕过原目标绑定。命令摘要不同但授权号相同，外部系统必须拒绝，不得返回另一个命令的成功结果。外部系统须在执行支付前持久化编号、摘要和状态，并长期支持原交易查询；缓存未命中不能作为权威 `NOT_FOUND`。

响应信封仍为 `contractVersion, tenantId, requestId, outcome, data`。外层 `SUCCESS` 只表示得到有效事实。不得用无原授权绑定的外层 `REJECTED` 声称支付失败。`data` 为 `PaymentObservation`：

- 固定 `authorizationId`、`commandDigest`；`revision` 必填，实际交易从 1 开始递增；`observedAt` 为已发生的事实查询时刻。
- `PENDING`：实际 `paymentReference`，不携带到账金额或回单。
- `SUCCEEDED`：实际交易引用、与命令完全相同的 `paidAmount`、账户摘要、`completedAt` 和 `receiptReference`。错币种、部分金额、另一账户及未来完成时间均不被接受为成功。
- `FAILED`：实际交易引用以及封闭失败分类；不得夹带到账事实。网络超时、连接失败和非法响应属于结果未知，不属于此状态。
- `REVERSED`：实际交易、退回金额、账户、完成时刻和回单，明确区别于到账。后续本地状态机必须进入对账，不能直接删除已形成的借款或核销。
- `NOT_FOUND`：只允许只读查询返回，`revision=0`，没有交易引用、到账或失败事实。是否重试由持久执行器决定，适配器不自动重发。

`failure` 只允许 `ACCOUNT_UNAVAILABLE`、`DEBIT_ACCOUNT_UNAVAILABLE`、`INSUFFICIENT_FUNDS`、`AUTHORIZATION_EXPIRED`、`APPROVAL_CHANGED`、`ACCOUNT_CHANGED`、`VOUCHER_UNAVAILABLE`、`PAYMENT_REJECTED`。同一交易的乱序、重复和相互矛盾的版本由后续持久状态机处理，适配器本身只校验单次事实，不宣称已实现回调顺序控制。

## 摘要编码

按下列顺序依次将字符串编码为 UTF-8，在每个字符串之前写入 4 字节大端有符号整数的字节长度，最后计算 SHA-256 小写十六进制。所有字段必填，无 null 编码，金额按两位小数规范化，UUID 为规范小写，时刻为 `Instant.toString()` 的 UTC 表示：

```
agentflow-payment-command-1
id, tenantId, purpose
binding.businessId, binding.applicationId, binding.roundNo, binding.applicationVersion, binding.businessVersion
amount.value, amount.currency, debitAccountReference
payee.legalEntityId, payee.employeeId, payee.accountReference, payee.maskedAccount, payee.accountDigest, payee.sourceVersion
voucherReference
authorization.authorizedBy, authorization.executedBy, authorization.authorizedAt, authorization.expiresAt
```

JSON 属性顺序不影响摘要；有效重发仍使用原对象得到的完整相同 JSON 字节。领域测试包含由 Python 独立生成的中文 UTF-8 向量：`34eb42a5e347d0e73bf068611838c69af2162fae5a9c74f9f93fae0be95250c0`。

## 验证与尚未接通的部分

支付领域 5 项、真实回环 HTTP 7 项通过；包含已有预算、财务主数据和网关配置的范围回归共 53 项通过，范围为包含关系。金额字符串、完整命令字节、原幂等号、凭据轮换、目标变化、过期禁止发送但允许查询、事务禁止、非法回执以及超时只发送一次后查询均有证据。

新增负例发现通用读取仍可调用交易查询。原回归只覆盖写命令禁止通用读取，没有覆盖查询必须绑定原目标；预算单点负例先失败 1 项，随后将四项预算/支付命令与查询一并限制到专用入口，范围回归通过。见 [支付端口证据](evidence/payment-system-port-20260928.json)。

本阶段未迁移数据库、未调整现有审批路由、未在批准动作中自动付款。后续实现借款申请和付款编排时，必须继续验证授权角色、原始业务版本、账户复查、会计期间与凭证、执行租约、权威查询、重复/乱序回调、原子生成借款余额及实际页面操作。
