# F09 柔性预算例外实施计划

> 使用 `superpowers:executing-plans` inline 执行。用户已授权按推荐连续完成本地开发，不重复询问方案、规则或逐任务许可；TDD，全部步骤后一次独立审查。

**Goal:** 外部明确允许柔性预算时进入原流程的独立预算负责人审批；人工同意后仍须真实预算确认，旧刚性、原键及历史语义保持。

**Spec:** [柔性预算例外](../../expense-budget-exceptions.md)。

**Architecture:** 外部预算端口给出可信政策和原命令凭据；费用轮次拥有例外状态；流程节点只编排真实人工决策与有依据的无需例外推进。复用预算金额台账、操作幂等及原申请锁，不复制副作用执行框架。

**Tech Stack:** Java 17、Spring/JDBC/Flyway、Flowable、Vue/TypeScript，无新依赖。

## Global Constraints

- 基线 `5e483d5b`（F05 本地验证与审查收尾完成），工作区 `/fyoung/tmp/agentflow-governance-identifier-integration`，分支 `codex/governance-identifier-integration`。
- 所有临时文件在 `/fyoung/tmp`；保留原工作区及 PID 87821。一次一个 Maven；执行期间不编辑 Java、测试和资源。
- 中文注释、英文日志，新增 Java 类型作者 `owlzhangfq@gmail.com`，JSON `io.agentflow.common.JsonUtil`。
- 不修改历史缺字段 JSON 的编码及 `BudgetCommand` 旧摘要；不伪造预算成功或真实企业政策。
- 浏览器/PG 不重试不绕过；V02/E15 前不推送或创建 PR。只执行风险匹配范围验证。

## Review Focus

1. 柔性能力、原拒绝操作和审批凭据必须同时匹配同一轮次/金额/目标，缺任何依据均不能授权；新字段不能使旧拒绝被解释为柔性。
2. 人工同意和新命令、审计、流程推进必须同事务；重复、并发、回滚和代理不能多发预算操作或伪造审批人。
3. 预算已受理但响应丢失时只查询原号，授权后的再次拒绝不能形成无限自动重试；财务守卫始终按本轮当前完整分摊核验真实冻结。
4. 明确预算职责是必经且独立的节点；后台无需例外推进有来源，不能绕过真实所需的人工审批，也不能推进暂停/过期/已撤回流程。
5. 历史摘要/JSON、旧发布定义、旧在审与旧冻结保持；新模板不能影响旧轮次或把管理员当敏感权限豁免。

### Task 1: 外部柔性依据与预算命令兼容

**Files:**
- Modify: `agentflow-domain/src/main/java/io/agentflow/finance/BudgetPrecheckPort.java`, `BudgetCommand.java`, `BudgetObservation.java`, `BudgetOperation.java`
- Create: 同包明确的预算柔性政策、例外凭据和人工授权值（按实际组合需要拆分，不预留通用扩展点）
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/GatewayBudgetPrecheck.java`, `GatewayBudgetSystem.java`
- Test: 已有预算领域/适配器测试及新增柔性凭据边界测试（先用 `rg --files` 确定现有测试名）

- [x] 固定旧命令 JSON/摘要及旧拒绝；先复现柔性政策、原命令凭据和授权绑定缺失。
- [x] 新字段成组校验，旧构造/缺字段保持；预算事实仍由实际 APPLIED 确定。
- [x] 覆盖错命令/错政策、无授权、摘要变化、重复恢复和普通预算回归后本地提交。

### Task 2: 原轮次预算例外聚合与持久化

**Files:**
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseBudgetReview.java`
- Create: `agentflow-server/src/main/java/io/agentflow/expense/JdbcExpenseBudgetReviewRepository.java`
- Create: `agentflow-server/src/main/resources/db/migration/V119__expense_budget_review.sql`
- 原提交绑定由仓储复核现有 `ExpensePrecheckEvidence`、正式控制与预算操作；`ExpenseSubmissionService` / `BudgetOperationService` 的实际接线随 Task 3 必经节点守卫一起完成。
- Test: 聚合状态/恢复及 JDBC 约束、投影和原操作身份集成

- [x] 先证明待结果、无需例外、人工待办、授权后外部待确认等状态不可混淆。
- [x] 冻结外部政策、正式提交预算号及原轮次；所有状态改变由聚合决定，存储复核来源。
- [x] 旧轮次不补造记录；唯一与外键约束、并发版本及事务回滚通过后本地提交。

### Task 3: 实际节点、预算结果与原键恢复

**Files:**
- Modify: `ExpenseProcessPolicy`, `DefinitionValidator`, `ExpenseSelfApprovalBindings/Snapshot`, `ExpenseApprovalService`, `ExpenseBudgetOutcomeHandler`
- Modify: `approval/process/FlowableTaskFacade.java`, `ApprovalCompletionService.java`
- Create: `ExpenseBudgetReviewService`、独立节点推进器、恢复 worker/调度（具体包按调用职责确定）
- Modify: `BudgetOperationService` 与任务审计；查询操作可用性字段随 Task 4 API / 页面契约共同接入。
- Test: 新 `ExpenseBudgetReviewIntegrationTest`，复用已有真实流程和预算夹具；相关预算、审批、子流程范围测试

- [x] 先复现柔性拒绝自动退回、预算未确认可误审批、以及独立节点与恢复缺失。
- [x] 提交事务创建本轮控制并绑定原预算号；全路径必经单人预算节点；真实冻结才自动推进并审计，柔性拒绝才开放人工审批。
- [x] 人工批准原子登记一次带原凭据的新预算命令；未知只查原号，拒绝不循环。
- [x] 刚性/旧定义、核减后拒绝、撤回退回重提、敏感权限/代理、子流程暂停和事务失败回归通过后本地提交。

### Task 4: 查询、费用页面、定义设计与模板

**Files:**
- Create: 费用预算例外只读 Query/Controller、前端解析与详情组件
- Modify: `ExpensePrecheckService`, `ExpenseWorkflowQuery`, `api.ts`, `expenses.ts`、费用提交/任务/详情组件
- Modify: `DefinitionExpenseStage.vue`, `TemplateCenter.vue`, `process-templates/expense-report.json` 与配套样例版本
- Modify: `api/openapi.json`、契约检查与前端测试运行器
- Test: 实际 Vue 状态与渲染、严格解析、身份/轮次竞争及原键恢复

- [x] 先复现来源状态和待预算时按钮/说明缺失；新增原轮次读取保持完整字段授权。
- [x] 展示需审批与已批准待预算两种状态；管理页明确节点规则，新模板不改写旧发布。
- [x] Java API、实际响应契约/解析、前端范围、类型检查和构建通过，本地提交。

### Task 5: 固定 V118 升级、强退与配套恢复

**Files:**
- Create: `scripts/check-expense-budget-exceptions.py` 及真实前端响应检查脚本
- Modify: 规范、计划、未完成台账及 `docs/evidence/expense-budget-exceptions-*.json`

- [ ] 固定 V118 `prior-controls-server-1f343ac8.jar`（SHA256 `33f721c9913d8cdf854663de2d8f2e13bd6b9a7fa63f839294182a0349edbe3f`）建立旧在审与预算状态，升级后核对旧列。
- [ ] 实际刚性/柔性、无需例外自动推进、并发人工审批、再次拒绝、旧键/权限、三轮及动态预算状态验收。
- [ ] 原命令受理后强退及独立配套恢复，原审批和预算号接续；各版本契约和真实前端解析通过。
- [ ] 归档固定包、源码及失败/成功记录，执行一次全范围独立审查；浏览器/PG 保持 OPEN。

## 初始决策

- Ruling: 用户已授权后续全部推荐规则和连续本地实现；此处业务选择属于原 F09 范围，不重复询问。
- Ruling: 不增加可编辑的预算例外表单布尔值；异步冻结可能晚于业务审批，固定的受控预算节点按真实结果等待、人工审批或有证据自动通过。
- Ruling: 柔性适用于本轮正式提交登记的首次冻结/重提调整；每轮一次人工授权，后续拒绝退回补正，避免无限重试与重新解释已审批事实。
- Ruling: 预算节点首期限定单人决策及单个共同必经节点；避免会签部分投票提前产生外部资金授权。
- Ruling: 后台恢复只做锁内本地事务，复用原预算 worker 处理网络；持久扫描补偿事件丢失，不建立第二套资金执行状态机。
