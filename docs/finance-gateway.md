# 财务网关

当前实现六项真实 HTTP 只读端口：员工财务目录、本人收款账户、法人汇率、费用与税务制度判定、发票原件查验和预算预检。调用链为财务应用服务 → 领域端口 → 网关适配器 → 企业事实源。目录 API、[持久验票任务](invoice-verification.md) 和[费用提交预检](expense-precheck.md) 已调用对应端口；另已实现[预算变更与查询契约](budget-operations.md)及适配器；预算台账和后台执行已持久化；正式提交已实现；另已实现 [支付命令与原交易查询适配器](payment-system-port.md)，并已实现 [会计期间、科目映射与凭证端口](accounting-ports.md)。结算队列、付款授权和真实企业联调继续实施。

## 部署配置

默认 `agentflow.finance-gateway.enabled=false`。启用后，每个租户必须有自己的目标，不存在跨租户默认回退。示例中的主机、租户和凭据均为配置占位符，不能作为企业联调证据：

```yaml
agentflow:
  finance-gateway:
    enabled: true
    tenants:
      tenant-a:
        endpoint: https://finance.example.invalid/agentflow/finance
        token: ${TENANT_A_FINANCE_TOKEN}
        timeout-seconds: 15
```

`endpoint` 是财务端口相对路径的共同父路径。只允许 HTTPS，或字面回环地址 `127.0.0.1` / `[::1]` 的 HTTP。禁止用户名、查询参数、片段和编码路径；路径仅接受字母、数字、`/`、`_`、`-`。不跟随重定向。超时范围 1–60 秒，覆盖响应体接收完成；连接超时另有 3 秒上限。

企业目标必须配置 Bearer 凭据。仅本机合成夹具可以显式设置 `allow-unauthenticated-loopback: true` 后省略凭据。该开关对 HTTPS 或非回环目标无效。配置错误在启用时阻止启动，缺少某租户配置在调用时返回 `NOT_CONFIGURED`。原始响应正文、令牌和原件均不写日志。

## 固定协议

六个端口均使用 `POST` 和 `application/json`。POST 只承载只读查询；企业实现不得据此冻结预算、扣款、记账或修改票据归属。平台每次读取生成新的 `requestId`，不自动重试。请求统一为：

```json
{
  "contractVersion": 1,
  "tenantId": "tenant-a",
  "requestId": "7b87b6e9-5262-40e2-a59d-b4b31f8c49d6",
  "data": { "employeeId": "employee-1" }
}
```

成功响应必须为 HTTP 200，完整回传相同租户、请求编号和协议版本，且只包含以下五个顶层属性：

```json
{
  "contractVersion": 1,
  "tenantId": "tenant-a",
  "requestId": "7b87b6e9-5262-40e2-a59d-b4b31f8c49d6",
  "outcome": "SUCCESS",
  "data": {}
}
```

此处空 `data` 仅表示结构位置，实际必须满足对应结果类型。业务拒绝同样使用 HTTP 200，将 `outcome` 设为 `REJECTED`，以单个 `reason` 替换 `data`。不得同时返回两者或附加远端说明。401/403 属于接入认证失败，其他非 200 属于依赖失败；它们不能变成业务批准或业务拒绝。

响应体接收过程中限制为 2 MiB，分块传输也适用。拒绝重复属性、尾随第二个 JSON、未知属性、标量宽松转换和数值枚举。金额一律为 `{"value":"100.00","currency":"CNY"}`，不能传 JSON 浮点数。时间使用 ISO 8601 UTC 时刻或明确日期；失效响应不接受为当前事实。

## 各端口的数据

| 相对路径 | `data` 请求 | `data` 成功类型 | 与本次请求的核对 |
| --- | --- | --- | --- |
| `catalog` | `employeeId` | `FinanceCatalog` | 员工相同、有效期未结束；目录内部无重复键和孤立成本对象 |
| `employee-account` | `employeeId`, `legalEntityId` | `EmployeeAccountPort.Account` | 员工、法人相同，有效期未结束 |
| `exchange-rate` | `legalEntityId`, `fromCurrency`, `toCurrency`, `rateDate` | `ExpenseExchangeRate` | 同一币种对、同一日期，显式来源；同币种汇率只能为 1 |
| `expense-policy` | `ExpensePolicyPort.Request` | `ExpensePolicyPort.Assessment` | 制度核算额等于该行按传入汇率折算值；可抵扣税额不超过折算申报税额；有效期未结束 |
| `invoice-verification` | `InvoiceVerificationPort.Request` | `Invoice.VerifiedFacts` | 法人、原件摘要相同；已到查验时刻且尚未过期 |
| `budget-precheck` | `BudgetPrecheckPort.Request` | `BudgetPrecheckPort.Assessment` | 完整请求逐项相同，查验时间已到且有效期未结束；不能只匹配总金额 |

`FinanceCatalog` 的完整公开结构见 OpenAPI `FinanceCatalog`。类别计量单位使用 `ITEM`、`DAY`、`NIGHT`、`KILOMETER`、`PERSON`。城市、类别和成本对象代码均是企业稳定标识，展示名称不能替代标识。法人包含本位币、纸质签收要求、来源版本和明确的 `timeZone`。时区须是有效 ZoneId，如 `Asia/Shanghai`；目录适配器须提供该字段，缺失时不使用服务器时区补齐。平台支持两位精度币种，不能向目录返回当前核算模型不支持的币种。

账户结果包含 `snapshot` 和 `validUntil`。快照字段为 `legalEntityId`、`employeeId`、`accountReference`、`maskedAccount`、`accountDigest`、`sourceVersion`。`accountDigest` 必须为 64 位小写 SHA-256 十六进制；完整账号只保留在资金主数据。掩码仅允许数字、`*`、`•`、`x/X`、空格及连字符，须有至少两个连续掩码字符，最多显示 8 位数字，单段最多 4 位，例如 `6222 **** **** 1234`。账户引用和摘要不通过报销详情返回页面。

制度请求含员工、法人、报销种类、完整 `ExpenseLine`、已选 `ExpenseExchangeRate` 及逐张 `InvoiceEvidence(invoiceId, facts)`；票据必须完整对应本行的本地发票标识，不得多出、漏掉或跨法人。制度结果含 `policy`、本位币 `deductibleTax`、`priorRequestRequired`、`validUntil`。`policy` 保留制度 ID/版本、核算额、制度允许额、`WITHIN_LIMIT` / `REQUIRES_EXCEPTION` / `DENIED`、税务规则引用及证据引用。超标不是已获特批，后续审批仍然必需。

验票请求含 `employeeId`、`legalEntityId`、`originalFileId`、`originalDigest`、`mediaType` 和 Base64 编码的 `original`。实际字节必须从服务器原件存储读取，并与 SHA-256 摘要相符；最大 20 MiB，支持 PDF、OFD、PNG、JPEG。接口不接受文件下载 URL。成功结果保留规范票号、法人、含税金额、税额、开票日期、相同原件摘要、查验引用、查验时刻及有效期。数电票使用 `DIGITAL` 和二十位 `number`，`code` 为 null 或省略；传统票使用 `TRADITIONAL`、`code` 和 `number`。真实票种、真实性及买方校验责任在企业查验服务。

预算预检请求含 `reportId`、`roundNo`、`financialVersion`（待提交财务版本）、`employeeId`、`legalEntityId`、`baseCurrency`、`accountingDate` 和 `allocations`。每项分摊为 `expenseLineNo`、`allocationNo`（均从 1 开始）、`categoryCode` 及 `cost: {costCenter, projectCode, amount}`。费用类别到真实预算科目的映射由企业预算服务提供，本地不编造科目或额度。上限为 200 行费用、每行 50 个分摊，同一位置不能重复，所有金额必须为法人本位币。

预算基数采用本位币核定含税分摊，不减可抵扣税额或借款冲销额。分摊已经过按行汇兑和整分平衡，不再汇兑。第 N 轮重提时，企业预检可把同租户、同报销单的第 N−1 轮现存冻结作为可替换额度；不能增加其他单据或已消耗预算的可用量。预检本身不得释放、替换或冻结额度。

预算成功返回 `request`（完整回传已核对的请求）、`reference`、`checkedAt`、`validUntil`，表示该份请求在检查时具有可用预算。总额相同但员工、法人、轮次、财务版本、期间、费用类别、成本中心或项目不同，均判为响应无效。此证据不是冻结凭证；后续提交必须另行幂等冻结，外部并发消耗仍可能导致冻结不足。

## 结果分类和事务边界

业务拒绝只允许与当前操作相关的封闭代码：

| 操作 | 允许的拒绝 |
| --- | --- |
| 目录 | `EMPLOYEE_UNAVAILABLE` |
| 账户 | `EMPLOYEE_UNAVAILABLE`, `ACCOUNT_UNAVAILABLE`, `LEGAL_ENTITY_UNAVAILABLE` |
| 汇率 | `RATE_UNAVAILABLE`, `LEGAL_ENTITY_UNAVAILABLE` |
| 制度 | `POLICY_NOT_FOUND`, `EXPENSE_PROHIBITED`, `PRIOR_REQUEST_REQUIRED`, `COST_OBJECT_UNAVAILABLE`, `LEGAL_ENTITY_UNAVAILABLE`, `EMPLOYEE_UNAVAILABLE` |
| 验票 | `INVOICE_INVALID`, `INVOICE_CANCELLED`, `INVOICE_BUYER_MISMATCH`, `LEGAL_ENTITY_UNAVAILABLE` |
| 预算预检 | `BUDGET_INSUFFICIENT`, `BUDGET_POLICY_UNAVAILABLE`, `ACCOUNTING_PERIOD_CLOSED`, `COST_OBJECT_UNAVAILABLE`, `LEGAL_ENTITY_UNAVAILABLE`, `EMPLOYEE_UNAVAILABLE` |

依赖失败分类为 `NOT_CONFIGURED`、`TARGET_CHANGED`（排队后的原目标已变化）、`TIMEOUT`、`CONNECTION`、`AUTHENTICATION`、`REMOTE_FAILURE`、`INVALID_RESPONSE`、`RESPONSE_TOO_LARGE`。只读 API 映射为 HTTP 503；业务拒绝映射为 HTTP 422。预检工作流应分别收集两类结果，不得将外部不可用持久化成“发票无效”或“已查验”。

客户端拒绝在真实数据库事务中调用。当前 `IdempotencyExecutor` 会把写入操作包在事务中，因此后续预检用例须使用独立的事务外执行阶段，返回与单据版本绑定的结果；提交在本地短事务中复核版本和有效期。不能在幂等成功回放前强制重新调用外部依赖。

## 本地证据

`FinanceGatewayClientTest` 使用真实回环 HTTP 和合成财务事实，验证成功、租户与编号错配、员工/法人错配、账户掩码、有效期、金额/税额错配、原件字节摘要、业务拒绝、重定向、分块超限、响应体超时及事务禁止。测试包含真实请求头和正文检查，不代表企业服务已经接入。

`FinanceCatalogControllerTest` 验证当前主体、防查询参数覆盖、无缓存和错误码；`OpenApiContractTest` 对照实际路由和公开记录字段。原有报销草稿回归继续验证精确金额 JSON、敏感字段授权和幂等。详见 `evidence/finance-gateway-20260928.json`。

预算只读端口另通过领域/核算/契约 23 项、实际 HTTP 网关/验票/目录 28 项；此范围包括原有回归。没有新增预算写入 API、冻结状态或凭证/支付行为。证据见 `evidence/budget-precheck-port-20260928.json`。

预算变更端口的固定幂等号、原操作查询、完整金额摘要和原子调整契约另见 [预算操作](budget-operations.md)。该扩展不改变上述六项只读操作的无副作用要求。

还款查询增加固定原目标的 `advance-repayment` 只读操作，回传同一借款的已收资金和已过账员工借款贷方分录。查询、财务确认、预留保护及外部收款撤销规则见 [借款还款与结清](advance-repayments.md)。该端口不发起扣款、不推送新凭证，金额仅来自外部已完成事实。
