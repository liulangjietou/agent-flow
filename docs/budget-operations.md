# 预算操作契约与恢复

新增 `BudgetSystemPort`、不可变 `BudgetCommand`、严格 `BudgetObservation` 与真实 HTTP 适配器，支持冻结、调整、释放、转实际占用及原操作查询。当前阶段完成契约和适配器；持久执行、正式提交、审批守卫和企业预算系统联调继续实施，不能把端口测试当作实际预算冻结。

## 业务与层次

调用方是后续费用提交或财务操作编排，下游是租户配置的企业预算系统。领域命令固定本次金额、成本分摊、期间和前一版本，适配器负责传输和事实核对。预算科目和可用额度属于外部事实源。HTTP 不得在数据库事务内执行。

`FREEZE` 首次登记，没有前置版本；成功结果为预算台账版本 1。`ADJUST` 带最近确认的版本及凭据，以完整新分摊原子替换旧冻结，覆盖重提和核减；失败保持旧冻结。`RELEASE` 全额释放，`CONSUME` 转实际占用，均引用最近确认的冻结。后三者成功版本必须为前置版本 + 1。外部幂等结果绑定操作，迟到请求不能覆盖新版本。

金额基数采用本位币核定含税分摊，不减可抵扣税额或借款冲销。实际命令使用 `BudgetPrecheckPort.Request.fromCurrent` 绑定冻结或核减后的财务版本；预检仍绑定冻结前的草稿版本，两者不可混用。

## 请求

沿用 [财务网关](finance-gateway.md) 的 HTTP 200、JSON、版本、租户及请求号响应协议，增加两个固定相对路径：

| 路径 | 请求 `data` | 幂等与副作用 |
| --- | --- | --- |
| `budget-command` | `{command, commandDigest}` | `requestId` 与 `Idempotency-Key` 都是固定 `command.id`；只执行该命令，不自动重试 |
| `budget-query` | `{operationId, commandDigest}` | 每次生成独立传输请求号，无幂等头；查询原操作，禁止创建或修改预算 |

`command` 包含 `id`、`tenantId`、`action`、`position`、`expected`。`position` 是完整 `BudgetPrecheckPort.Request`，包含单据、轮次、当前财务版本、员工、法人、本位币、记账日期及逐项分摊。`expected` 为 `{revision, reference}`，首次冻结为 null。相同命令重复执行必须返回原结果，不能重复冻结；相同编号不同摘要必须拒绝且不得覆盖已有命令。

排队时保存目标摘要，发送与查询时按配置再次匹配。改变目标返回 `TARGET_CHANGED`，不把旧操作发送到新预算系统。凭据轮换不改变目标身份。通用只读方法禁止执行预算命令。

## 摘要算法

`commandDigest` 为以下各值按顺序编码后的 SHA-256 小写十六进制。每个值先写 4 字节有符号大端整数长度，再写 UTF-8 字节；null 只写长度 -1。数字先转为十进制字符串；金额始终两位小数，UUID 为标准小写形式。没有额外分隔符或末尾换行。

1. 固定字符串 `agentflow-budget-command-1`，命令 ID、租户、操作名。
2. 单据 ID、轮次、财务版本、员工、法人 ID、本位币、ISO 日期、分摊数量。
3. 按原数组顺序写每项：费用行号、分摊序号、费用类别、成本中心、可空项目、金额值、金额币种。
4. 前置版本和前置凭据；没有前置状态时两者均为 null。

跨语言测试向量见 `BudgetCommandTest`：中文成本中心 `研发:中心`、空项目、CNY 100.00 的固定样本摘要为 `240aecd51a8aaa95bc378695ec620ceea69c4b7e8a7def36bd8d4b4a738fd82a`，由 Python 独立计算并由 Java 验证。

## 结果与恢复约束

响应外层 `outcome=SUCCESS` 仅表示取得了有效操作事实。`data` 必须包含原 `operationId`、相同 `commandDigest`、`status`，其他字段按状态互斥：

| 状态 | 其他字段 | 含义 |
| --- | --- | --- |
| `APPLIED` | `ledgerRevision`, `reference`, `appliedAt`，`rejection=null` | 已实际完成；版本必须匹配，生效时刻不能在未来 |
| `REJECTED` | `rejection`，上述三个生效字段均为 null | 本次操作确定未变更预算；不是依赖失败 |
| `PENDING` | 其他字段均为 null | 外部尚未取得终态 |
| `NOT_FOUND` | 其他字段均为 null | 仅查询可返回；必须来自权威操作台账，不能用缓存未命中代替 |

拒绝分类：`BUDGET_INSUFFICIENT`、`BUDGET_POLICY_UNAVAILABLE`、`ACCOUNTING_PERIOD_CLOSED`、`COST_OBJECT_UNAVAILABLE`、`LEGAL_ENTITY_UNAVAILABLE`、`EMPLOYEE_UNAVAILABLE`、`LEDGER_VERSION_CONFLICT`、`RESERVATION_FINALIZED`。预算不足只适用于冻结或调整。预算路径不接受外层通用 `REJECTED`，因为它没有绑定不可变命令。

超时、断连、错误响应及进程崩溃都可能发生在外部已执行之后，不能认定未执行。恢复先查原操作：`APPLIED` 保存真实结果，`REJECTED` 保存拒绝，`PENDING` 继续等待；权威 `NOT_FOUND` 才允许沿用完全相同的命令及幂等号重新发送。目的地变化时保留未知状态，不能换系统查询后认定不存在。

## 验证证据

`BudgetCommandTest` 3 项、`BudgetPrecheckPortTest` 3 项、`GatewayBudgetSystemTest` 6 项、原 `FinanceGatewayClientTest` 14 项通过。真实回环 HTTP 检查请求字节与幂等头、只读查询、超时后查询恢复、目标变化、事务禁止、编号/摘要/版本/状态错配与三种后续动作。全部使用合成预算事实，证据见 `evidence/budget-command-port-20260928.json`。
