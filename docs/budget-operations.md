# 预算操作契约与恢复

已实现 `BudgetSystemPort`、不可变命令、严格操作事实、V38 持久任务、预算台账和后台执行器，支持冻结、调整、释放、转实际占用及原操作查询。正式报销提交、审批守卫和企业预算系统联调继续实施；本地合成预算结果不能冒充真实企业冻结。

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

## 持久执行与台账

`BudgetOperationService.reserve` 必须加入提交或核减事务，从已保存的当前财务轮次计算完整分摊；本地登记中途失败时，报销版本、台账及任务一同回滚，外部没有调用。锁统一由报销仓储持有，顺序为申请、报销；预检和预算共用这一顺序。

任务的恢复路径为 `QUEUED → EXECUTING → APPLIED / REJECTED / UNKNOWN`。未知结果领取为 `QUERYING`，确认不存在后回到 `QUEUED`；查询到终态才记录真实结果。请求或查询租约过期时先记录 `UNKNOWN/LEASE_EXPIRED`。领取版本隔离迟到执行者，旧执行者不能覆盖后来查询恢复的结果。退避从 5 秒递增至最多 300 秒，并持久保存下一次时间，重启不重置。

同一单据只能有一个活动操作。台账在等待期间保留最后确认的金额，同时保存待定操作编号；`frozenFor` 只有在没有待定操作且完整财务位置匹配时才返回 true。调整失败保留旧预算，旧版本不能批准已核减或重提的新版本。释放和转实际占用必须引用最后确认的位置，完成后台账进入终态；未知操作先完成查询恢复，不能换新编号跳过。

任务和台账的终态写入同一事务，各自追加审计版本。V38 使用真实报销本人绑定、已有财务版本外键、活动操作唯一约束以及状态/调度字段约束。恢复读取同时核对 JSON 与索引列，不能容忍命令摘要或身份列不一致。

后台默认随财务网关启用；`AGENTFLOW_BUDGET_WORKER_ENABLED=false` 暂停领取并保留任务。`AGENTFLOW_BUDGET_LEASE_SECONDS` 默认 90，允许 15–300 秒。预算调度线程独立于审批、模型、验票和费用预检；网络等待不持有报销锁。

补充修复了旧轮次核减边界：原测试只覆盖已冻结轮次核减，没有覆盖“核减后补正，再对旧轮次核减”的顺序。新增用例先失败，随后让实体的 `requireFrozenRound` 同时保护核减和当前预算位置构造，避免用旧快照伪装新财务版本。相关领域回归 36 项、服务端回归 34 项通过；PostgreSQL 预算集成与升级 9 项通过，范围有重叠。当前持久执行证据见 `evidence/budget-operations-20260928.json`。
