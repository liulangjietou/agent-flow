# F05 事前额度控制实施计划

> 使用 `superpowers:executing-plans` 在当前工作区逐项实现，TDD 与一次最终独立审查。用户明确授权本项目按推荐持续开发；不重复请求设计、计划及执行方式批准。

**Goal:** 类别控制固定到事前批准额度，STRICT 阻断、TOLERANCE 超阈值追加独立审批、NONE 保留真实账本；历史和借款限制保持。

**Architecture:** 已有类别修订和事前轮次拥有控制来源，额度聚合拥有金额不变量；提交服务冻结跨聚合判断，流程策略负责必经例外审批。共享账本仅新增真实需要的参考上限语义，业务聚合限制使用范围。

**Tech Stack:** Java 17、Spring/JDBC/Flyway、H2/PostgreSQL、Vue 3/TypeScript，无新依赖。

**Spec:** [事前额度控制](../../expense-prior-controls.md)。

## Global Constraints

- 当前分支 `codex/governance-identifier-integration`、工作区 `/fyoung/tmp/agentflow-governance-identifier-integration`；开始基线 `7f16db15`（F16 已完成独立审查）。
- 原主工作区和 PID 87821 保留；临时文件只能写 `/fyoung/tmp`。一次一个 Maven，期间不编辑 Java、测试和资源。
- 中文注释、英文日志、public 方法注释和新增类型 `@author owlzhangfq@gmail.com`；JSON 使用 `io.agentflow.common.JsonUtil`。
- 以风险匹配范围回归，不重复已通过且无新变更的整套测试；最终项目门禁仍由 V02/E15 承担。
- 本地优先，V02/E15 前不推送或创建 PR；浏览器/PG 不重试或绕过既有环境限制。
- 旧缺字段保持原硬上限及 JSON 字节语义；不伪造企业比例、类别或无限大额度。

## Review Focus

1. 历史正容差仍是原硬上限，而非新软控制；Task 1/2 覆盖旧 JSON 和恢复一致性。
2. 同单多行及并发跨单共享一份阈值，最后一笔不能复用过期预检；Task 1/3 覆盖最终总量和锁内版本。
3. NONE 仍受关闭、来源行、租户、法人、币种、类别及旧核销身份约束；Task 1/3 覆盖解除上限不能解除这些限制。
4. 旧流程没有例外字段/节点时，超容差必须整体回滚，不能只增加提示；Task 3 覆盖所有可完成路径及真实引擎节点。
5. 旧类别改版和财务核减不能改写已批准控制或原审批依据；Task 2/3/5 覆盖在审、重新提交和恢复。

---

### Task 1: 控制值与金额不变量

**Files:**
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpensePriorControl.java`
- Modify: `agentflow-domain/src/main/java/io/agentflow/finance/ReservedAmount.java`
- Modify: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseRequest.java`, `EmployeeAdvance.java`
- Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpenseResourcesTest.java`
- Create Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpensePriorControlTest.java`

**Interfaces:** `ExpensePriorControl(Mode mode, BigDecimal toleranceFraction)`；STRICT/NONE 不接受比例，TOLERANCE 要求显式 0–1、最多六位小数。嵌套 `Snapshot(String categoryCode, long categoryRevision, ExpensePriorControl control)`。`ReservedAmount` 增加可空 `Ceiling ceiling`，null/HARD 均硬限制，`REFERENCE_ONLY` 只跟踪真实用量；`availableWithoutCeiling(Money reference)`、`hardLimit()`、`exceeded()`。所有既有构造保留 null，所有状态变更保留 ceiling。`ExpenseRequest.ApprovedLine` 末尾增加可空 `control`，旧四参数构造保留；新宽松模式选择参考账本，恢复交叉验证账本与原模式。`EmployeeAdvance.restore` 拒绝参考账本。

- [x] 从现有资源测试增加“参考账本不能通过借款恢复”和模式序列化用例，先观察真实断言失败；新类型测试可用 Jackson 树输入避免只得到编译失败。
- [x] 实现三个模式、上限语义及聚合防降级，保留历史缺字段原 JSON。
- [x] 覆盖精度边界、累计占用、关闭/核销/释放/冲回/核减、旧正容差仍硬限制及错误组合。
- [x] 运行领域匹配范围并本地提交，记录源文件与结果摘要。

### Task 2: 类别配置与事前批准冻结

**Files:**
- Modify: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseCategoryCatalog.java`, `ExpensePlanRound.java`, `ExpensePlan.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/expense/ExpensePolicyConfiguration.java`, `ExpensePlanCheckEvaluator.java`, `ExpensePlanCheckService.java`, `ExpensePlanSubmissionService.java`
- Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpenseConfigurationTest.java`, `ExpensePlanTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/expense/ExpenseConfigurationApiTest.java`, `ExpensePlanIntegrationTest.java`
- Modify: `agentflow-server/src/main/resources/api/openapi.json`

**Interfaces:** 类别末尾可空 `ExpensePriorControl priorControl`；`FrozenLine` 末尾可空 `ExpensePriorControl.Snapshot priorControl`。预检从同一类别修订读取配置并逐行固定，提交使用原证据，批准将同一快照传入 `ApprovedLine`。旧构造和旧 JSON 保留缺字段，不将旧非零容差改为软模式。

- [x] 真实配置及计划接口先复现模式不接收/批准恒零的问题。
- [x] 同一类别修订与控制原子读取、未提交失效、批准后冻结；旧仓储上下文/修订字节不改。
- [x] 更新相关 OpenAPI，验证非空旧库与原批准额度继续读取和使用；范围通过后本地提交。

### Task 3: 超容差依据与独立追加审批

**Files:**
- Modify: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseSubmissionResources.java`, `ExpenseFormContract.java`, `ExpenseProcessPolicy.java`
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpensePriorControlAssessment.java`, `ExpensePriorApprovalPolicy.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSubmissionService.java`, `ExpensePrecheckEvaluator.java`, `ExpensePrecheckService.java`
- Create: `agentflow-server/src/main/java/io/agentflow/expense/JdbcExpensePriorControlRepository.java`, `ExpensePriorControlQuery.java`
- Create migration: `agentflow-server/src/main/resources/db/migration/V118__expense_prior_control.sql`（执行前复核编号）
- Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpenseSubmissionResourcesTest.java`, 新 `ExpensePriorApprovalPolicyTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/expense/ExpenseSubmissionIntegrationTest.java`, 新 `ExpensePriorControlIntegrationTest.java`

**Interfaces:** `ExpenseSubmissionResources.Plan` 追加原轮次控制评估列表，保留三参数旧构造。每个评估固定来源请求、行、版本、类别控制、阈值、净核销、其他占用、本轮用量、总量及超出额。新增服务端布尔 `priorRequestOverTolerance`，旧四字段表单仍可读取；新职责 `PRIOR_REQUEST_REVIEW`，专用策略验证超容差必经独立节点且在财务前完成。缺说明/缺控制字段/缺合法必经节点直接阻断，提交与资源更新/依据落库同事务。

- [x] 先用真实预检/提交证明累计超容差缺说明、缺追加节点或并发过期不能静默提交。
- [x] 实现类别一致性、最终累计判断、轮次依据持久化与审批路径门禁；所有额外审批沿用现有字段权限和人员规则。
- [x] 实际引擎验证阈值内原路径、超阈值先额外审批、不能由普通超标/签收/财务节点替代；退回重提及核减不改旧依据。
- [x] 核对旧定义、旧在审、并发占用、关闭和原预算操作，运行匹配范围并本地提交。

### Task 4: 配置、填报及审批页面

**Files:**
- Modify: `agentflow-web/src/expenseConfiguration.ts`, `expenseConfigurationDrafts.ts`, `expenseConfigurationRead.ts`
- Modify: `agentflow-web/src/components/ExpenseConfigurationManager.vue`, `ExpensePlanLines.vue`, `ExpensePlanDetail.vue`, `ExpenseLineEditor.vue`, `DefinitionExpenseStage.vue`
- Modify: `agentflow-web/src/expenses.ts`, `expensePlan.ts`, `expenseDraft.ts`, `api.ts`, `portableTemplate.ts`
- Modify: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseWorkspaceQuery.java` 及受控原轮次查询入口
- Modify: `agentflow-server/src/main/resources/process-template-examples/employee-finance.json` 及费用模板（先定位实际模板文件）
- Test: `agentflow-web/tests/expense-configuration.test.mjs`, `expense-plan.test.mjs`，新增 `expense-prior-controls.test.mjs`
- Modify: `agentflow-server/src/main/resources/api/openapi.json` 与对应契约检查

- [x] 先复现配置模式、阈值/无上限展示、原轮次依据和新审批职责未接通。
- [x] 管理页明确比例；选择器区分硬上限与参考余额；超容差逐行说明、金额依据和新增节点可配置且受控读取。
- [x] 升级模板但保留旧版本、下载/导入及模拟路径；身份/轮次切换和原键恢复保持。
- [x] 范围组件测试、类型检查、构建和 OpenAPI 通过，本地提交。

### Task 5: 固定安装包与恢复验收

**Files:**
- Create: `scripts/check-expense-prior-controls.py`
- Create: `agentflow-web/scripts/check-expense-prior-controls-runtime.mjs`
- Modify: 本计划、规范、未完成台账及 `docs/evidence/expense-prior-controls-*.json`

- [x] 以固定 V117 基线公开 API 建立旧额度与在审单，再升级新包核对所有旧列及原证据。
- [x] 三模式真实 HTTP、同单多行/并发、实际追加审批、关闭/核减/跨轮、原键恢复及权限验证。
- [x] 在执行中重启、独立数据库与接收方配套恢复后接续原流程，核对原资金/预算编号不重发；真实前端解析与各版契约通过。
- [ ] 归档源码、固定包和失败/成功证据，执行一次全范围独立审查；浏览器/PG 未验收仍 OPEN。

## 初始决策

- Ruling: 用户已明确授权按推荐连续开发，覆盖重复设计/计划审批；使用 inline 执行，最后一个独立审查。
- Ruling: 类别保存沿用现有生效修订，不引入第二套配置发布机制；计划冻结原类别修订，配置变化只影响未提交预检及后续计划。
- Ruling: 扩展既有金额账本的明确参考语义，避免复制完整冲回/核减实现；借款恢复必须显式拒绝参考模式。若漏校验会削弱借款约束，Task 1 的失败用例作为门禁。
- Ruling: 历史非零容差保持硬上限；只带完整新控制快照的批准行使用 TOLERANCE/NONE，不按数字猜测新模式。
