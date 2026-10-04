# F04 跨单风险与合计金额路由实施计划

> 执行方式：使用 `superpowers:executing-plans` 在当前隔离工作区逐项执行；每项遵循 `superpowers:test-driven-development`，全部完成后按技能要求进行独立审查。用户已授权按建议实施并持续完成，无需重复请求方案许可。

**Goal:** 在正式报销提交时冻结同口径跨单依据，标记拆单风险并让指定业务网关按合计金额路由，保留真实费用和财务审批语义。

**Architecture:** 费用领域计算不可变窗口及金额证据，费用服务在现有提交事务和租户配置锁内查询、冻结。Flowable 仅在发布时明确标记的业务网关读取服务端冻结金额，所有其他表单和财务金额保持原路径。

**Tech Stack:** Java 17、Spring 事务、JDBC/Flyway、Flowable、H2/PostgreSQL、Vue 3/TypeScript；不增加运行依赖。

**Spec:** [F04 规则与边界](../../expense-split-routing.md)，原需求 `/Volumes/fyoung/code/AI/flow/doc/05-费用报销领域设计.md` §5.3，台账 `authorizedAdjustments` 中已接受的滚动窗口口径。

## Global Constraints

- 当前工作区 `/fyoung/tmp/agentflow-governance-identifier-integration`，基线 `322861302e1c42e4456d9892d5e9ffe7cf440575`；保留主工作区及 PID 87821。
- Java 17；中文注释，英文日志；新 Java 类型包含 `@author owlzhangfq@gmail.com`；JSON 使用 `io.agentflow.common.JsonUtil`。
- 不给企业选择 N、金额或启用状态；软件界限为 1–365 日、至多 1,000 张参与单据，超限阻断而不截断。
- 临时文件放 `/fyoung/tmp`；一次只运行一个 Maven，Maven 运行中不编辑 Java、测试或资源。
- 用户要求按改动范围回归，覆盖必要边界；不因技能默认要求重复全项目测试。最终完整门禁归 V02/E15。
- 不绕过浏览器和 PostgreSQL 现有阻碍；先完成本地实现；V02 和 E15 前不推送或创建 PR。

## Review Focus

1. 本单有多个类别，多个类别命中不能相加并重复计算本单；Task 1 断言路由取最大值且不降低本单总额。
2. 当前有效轮次核减后与原提交金额不同；Task 2/3 断言查询使用当前核定值且旧路由不被重写。
3. 两张不同报销单各自持有本单锁后并发提交；Task 3 断言现有租户串行点保证第二次看见第一次，不对其他单据补锁造成死锁。
4. 业务和财务网关共用 `amount`；Task 3 断言业务取冻结合计，财务核减后的复核取本单最新核定额。
5. 来源原轮次后来变为只读不可见或脱敏；Task 4/5 断言管理员也不能借依据接口或迟到响应看到来源事实。

---

### Task 1: 显式策略与纯领域金额证据

**Files:**
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseSplitRiskPolicy.java`
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseSplitRiskEvidence.java`
- Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpenseSplitRiskPolicyTest.java`
- Test: `agentflow-domain/src/test/java/io/agentflow/expense/ExpenseSplitRiskEvidenceTest.java`
- Modify: `agentflow-domain/src/main/java/io/agentflow/definition/DefinitionValidator.java`

**Interfaces:**
- `ExpenseSplitRiskPolicy.from(Graph): Configuration`；`validate(Graph, FormSchema): void`；嵌套 `Mode {UNCONFIGURED, DISABLED, ENABLED}`、`Rule(int windowDays, Money threshold)`、`Configuration(Mode mode, Rule rule, Set<String> gatewayIds)`。
- `ExpenseSplitRiskEvidence.assess(Rule, Document primary, List<Document> candidates, Instant at): Assessment`。
- `Scope(String tenantId, String employeeId, UUID legalEntityId, String currency)`；`Line(int lineNo, String categoryCode, Money approvedGross)`。
- `Document(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, int roundNo, Scope scope, Instant submittedAt, ApplicationStatus status, List<Line> lines)`。
- `CategoryTotal(String categoryCode, Money total, int reportCount, boolean triggered)`；`Assessment(Instant windowFrom, Instant assessedAt, Money ownAmount, Money routingAmount, List<CategoryTotal> categories, List<Document> sources)`，含 `suspected()`。

- [x] 写现有 `DefinitionValidator` 的失败测试：错误启用参数和财务之后的合计网关不得发布；先确认断言失败。
- [x] 写金额证据测试：主单 1,000 + 边界时刻旧单 5,000、阈值 5,000 时命中且路由 6,000；旧单 4,000 时不命中；下边界前 1 微秒与未来 1 微秒均排除。
- [x] 补同单、多行、多类别、零额、撤回重提、币种/法人/人员/租户隔离及 1,001 张来源阻断；未知配置和半套参数拒绝。
- [x] 实现纯规则与证据，主单先排、其余时刻/编号排序；用命中类别最大额形成路由，不修改任何财务状态。
- [x] 执行两个新增领域类及 `ExpenseSelfApprovalPolicyTest`、`ExpenseDuplicateApprovalPolicyTest`、现有定义验证相关范围；预期全部通过。
- [x] 校验身份后本地提交；阶段记录明确运行链路尚未启用，F04 保持 OPEN。

### Task 2: 当前轮次投影和不可变依据持久化

**Files:**
- Create: `agentflow-server/src/main/java/db/migration/V116__Expense_split_routing.java`
- Create: `agentflow-server/src/main/java/io/agentflow/expense/JdbcExpenseSplitRoutingRepository.java`
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseSplitRoutingSnapshot.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/expense/JdbcExpenseReportRepository.java`
- Test: `agentflow-server/src/test/java/io/agentflow/expense/ExpenseSplitRoutingPersistenceTest.java`

**Interfaces:**
- 仓储 `candidates(String tenantId, String employeeId, UUID legalEntityId, String currency, UUID excludedReport, Instant from, Instant through): List<Document>`。
- `ExpenseSplitRoutingSnapshot` 绑定主单、预期提交轮次、财务版本、已发布定义及版本、配置与 Task 1 的 `Assessment`；未配置/关闭状态明确保存，旧轮次无记录不补造已检查结论。
- 仓储 `save(ExpenseSplitRoutingSnapshot): void`、`find(String tenantId, UUID reportId, int roundNo): Optional<ExpenseSplitRoutingSnapshot>`、`findByApplication(String tenantId, UUID applicationId, int roundNo): Optional<ExpenseSplitRoutingSnapshot>`。

- [ ] 实际 Flyway/H2 建立有草稿、在审、核减及退回轮次的非空 V115 数据；记录旧列正文。
- [ ] 先验证缺少当前轮次投影与依据存储的失败，再新增 V116；迁移只填新列，不重写 `state_json`。
- [ ] 同一仓储写入维护并恢复校验 `current_round_no/current_submitted_at/current_legal_entity_id/current_base_currency`；创建查询所需索引。
- [ ] 来源查询只取当前 IN_APPROVAL/APPROVED，过滤本单、窗口和同组；1,001 个参与来源不可静默截断。
- [ ] 验证旧列不变、错配版本/租户被拒、原依据不可覆盖、事务回滚、核减更新查询投影和来源事实；预期范围全部通过后本地提交。

### Task 3: 提交事务与业务网关接通

**Files:**
- Create: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSplitRoutingService.java`
- Create: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSplitRoutingBindings.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSubmissionService.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/approval/process/FlowableProcessRuntimeAdapter.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/definition/FlowableDefinitionDeploymentAdapter.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/definition/FlowableConditionEvaluator.java`
- Test: `agentflow-server/src/test/java/io/agentflow/approval/ExpenseSplitRoutingIntegrationTest.java`

**Interfaces:**
- 服务 `prepare(ExpenseReport report, Application application, DefinitionDraft definition, Instant at): ExpenseSplitRoutingSnapshot`，在租户配置锁内且费用新修订已保存后调用，早于原 `submitBusiness`。
- 绑定 `require(StartProcessCommand command, DefinitionDraft definition): ExpenseSplitRoutingSnapshot`，只接收同事务原定义/主单/原轮次的服务端依据。
- 条件方法 `matchesExpenseSplit(DelegateExecution execution, String encodedCondition, int languageVersion): boolean`；仅其局部求值上下文覆盖 `amount`，内部冻结变量不属于可编辑 `formData`。

- [ ] 实际两张 4,000 报销并发提交，阈值 5,000；先复现业务路径没有使用 8,000 的失败。
- [ ] 接通准备记录与引擎变量；BPMN 只对启用的标记网关调用专用方法，旧两参数/三参数调用保持。
- [ ] 验证第二提交命中、首次不追溯改写、未知/缺失/错配依据阻断以及后置预算失败整体回滚。
- [ ] 在同一流程验证业务按 8,000 路由，财务核减后复核仍按本单额；关闭规则、旧 BPMN、重提和新定义版本各按自己的快照运行。
- [ ] 运行相关提交、核减、职责与原定义回归；预期通过后本地提交。

### Task 4: 原轮次依据查询与权限

**Files:**
- Create: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSplitRoutingController.java`
- Create: `agentflow-server/src/main/java/io/agentflow/expense/ExpenseSplitRoutingQueries.java`
- Modify: `agentflow-server/src/main/resources/api/openapi.json`
- Test: `agentflow-server/src/test/java/io/agentflow/approval/ExpenseSplitRoutingReadIntegrationTest.java`

**Interfaces:**
- `GET /api/v1/expense-reports/{id}/split-routing?roundNo=...`，严格参数、`no-store`，使用当前会话而不接收租户或身份参数。
- `View` 状态明确区分 `NOT_RECORDED/UNCONFIGURED/DISABLED/CLEAR/SPLIT_SUSPECTED`；另有 `sourcesReadable`。来源受限时 `details=null`，不返回来源编号、数量或金额；可读时 `details` 包含完整冻结依据。

- [ ] 先复现已知原轮次读取和跨单敏感来源缺少受控入口；真实申请参与关系及字段授权测试不以桩替代。
- [ ] 本单完整明细先授权，再逐个核对来源原轮次完整明细；失权/脱敏一律隐藏全部跨单明细，ADMIN 不例外。
- [ ] 验证旧轮次、历史无记录、关闭规则、重复参数、伪造租户、403/404 边界及查询不写业务表；契约和范围回归通过后本地提交。

### Task 5: 设计器、模板和审批详情

**Files:**
- Create: `agentflow-web/src/expenseSplitRouting.ts`
- Create: `agentflow-web/src/components/DefinitionExpenseSplitRisk.vue`
- Create: `agentflow-web/src/components/ExpenseSplitRoutingPanel.vue`
- Modify: `agentflow-web/src/App.vue`、`components/QuickDesigner.vue`、`components/ExpenseDetail.vue`、`api.ts`、`portableTemplate.ts`、`definitionComparison.ts`
- Modify: `agentflow-server/src/main/resources/process-templates/expense-report.json` 和实际模板导入导出白名单/模拟消费点。
- Test: `agentflow-web/tests/expense-split-routing.test.mjs` 及模板范围测试。

**Interfaces:**
- 设计器编解码完全使用 Task 1 属性；启用、参数及标记网关显式展示，模板默认关闭，升级不覆盖旧定义。
- `readSplitRouting(value, reportId, roundNo)` 严格校验 Task 4 响应；页面只读，不生成跨单依据写请求。

- [ ] 先写受限来源、账号/轮次切换、未知状态/缺失版本、旧模板往返及财务网关不可选的失败用例。
- [ ] 两种设计器、导入导出和差异使用同一规则；模拟显式输入合成合计并标明不是当前业务查询结果。
- [ ] 明细面板展示原轮次规则、风险和允许读取的依据；取消/迟到响应不能覆盖新的账号或轮次，不把受限当作零风险。
- [ ] 相关前端、类型检查、构建、OpenAPI 及模板回归全部通过后本地提交。

### Task 6: 固定运行包与配套恢复

**Files:**
- Create: `scripts/check-expense-split-routing.py`
- Create: `agentflow-web/scripts/check-expense-split-routing-runtime.mjs`
- Modify: 本说明、未完成台账与 `docs/evidence/` 阶段证据。

- [ ] 保存固定提交包，用 V115 公开接口建立非空旧单；执行 V116 升级，对照所有旧列和原件，不仅检查迁移版本号。
- [ ] 真实 HTTP/Flowable 验证两张并发单、返回重提、旧规则、新规则、核减隔离与原依据查询；记录来源及风险路由而不调用模型。
- [ ] 强退/重启与数据库、附件、合成预算接收方配套恢复后继续原审批；冻结依据和原幂等操作不重复生成。
- [ ] 实际响应通过 OpenAPI 和页面解析函数；归档固定包、源码摘要、失败记录与最终证据；范围通过后本地提交。
- [ ] 按 `executing-plans` 对整个 F04 变更审查，修复实证缺陷后更新状态；浏览器和 PostgreSQL 未验收时保持 F04 OPEN，不改全目标完成状态。

## 执行记录

- 初始复核：接口与金额语义一致，Task 1 的 Rule/Document/Assessment 由 Task 2/3 消费；Task 2 的 snapshot 由 Task 3/4 消费；Task 4 的 View 由 Task 5/6 消费。
- 决定：沿用现有租户配置锁；代码实际已有这一串行点，不新增全局锁，也不对对照报销加锁。
- 决定：业务网关显式标记；共用 `amount` 的财务复核不允许读取跨单合计。
- 决定：实施过程中若具体存储/API 类型与已核对链路不符，记录最小修订及原因；不得通过缩小原需求来取得测试通过。
