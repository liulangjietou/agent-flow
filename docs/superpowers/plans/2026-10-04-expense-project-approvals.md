# F14 项目负责人会签实施计划

> 使用 `superpowers:executing-plans` inline 执行。用户已授权全部推荐规则及连续本地开发；先追链路、最小失败复现、风险匹配回归，全部步骤后执行一次独立审查。

**Goal:** 可信项目目录驱动原轮次项目负责人全员会签，去重不丢责任，旧在审和原预算保持。

**Spec:** [项目分摊负责人会签](../../expense-project-approvals.md)。

**Architecture:** 财务预检提供项目来源，费用提交冻结责任映射，组织层核验资格/任职，既有费用职责快照和 Flowable ALL 执行任务。无新增外部副作用执行框架。

**Tech Stack:** Java 17、Spring/JDBC/Flyway、Flowable、Vue/TypeScript，无新依赖。

## Global Constraints

- 基线 `da7287ea426cf452463103cfaa9c545e3bdc6d44`，已合入本地 main。开发工作树 `/fyoung/tmp/agentflow-governance-identifier-integration`。
- 临时文件均在 `/fyoung/tmp`；保持 PID 87821。一次一个 Maven，执行期间不编辑 Java、测试或资源。
- 中文注释、英文日志；新增 Java 类型作者 `owlzhangfq@gmail.com`；JSON `io.agentflow.common.JsonUtil`。
- 旧缺字段编码与历史轮次不补造责任；外部目录读取仍在事务外，金额/项目路由不信任客户端。
- 浏览器/PG 不重试或绕过；远端交付保留 V02/E15 门禁；本地范围验证不得冒充企业验收。

## Review Focus

1. 每个实际项目都有可信原负责人且绑定同法人、预检、财务修订和提交轮次；同人多项目不能丢映射。
2. 有项目必经 ALL、无项目不激活空会签；字段、任职、资格和自审批上溯不可绕过。
3. 目录变化不替换已冻结责任；加减签、转交、代理/委派及多次决策不能减少原责任或伪造批准。
4. 重提、财务核减、暂停/终止、原键恢复、事务回滚与历史读取保持原边界。
5. 新模板与只读页面的状态、契约和实际响应一致；旧发布定义及管理员敏感权限保持。

### Task 1: 可信项目负责人来源及预检值兼容

**Files:**
- Modify: `agentflow-domain/src/main/java/io/agentflow/finance/FinanceCatalog.java`
- Create: `agentflow-domain/src/main/java/io/agentflow/expense/ExpenseProjectOwners.java`
- Modify: `agentflow-domain/src/main/java/io/agentflow/expense/ExpensePrecheckEvidence.java`
- Test: 新来源值和实际 `JsonUtil` / 财务网关兼容用例，复用已有预检范围

- [ ] 先复现目录缺少负责人、项目全集和来源校验未实现，保留旧目录/预检字节形状。
- [ ] 可空负责人字段向后兼容；实际项目映射完整且不可变，同人保留多个项目。
- [ ] 错法人/空负责人/重复项目/来源不完整/旧 JSON 恢复及范围回归通过后本地提交。

### Task 2: 原轮次项目来源持久化

**Files:**
- Create: `ExpenseProjectApprovalSnapshot` 与 `JdbcExpenseProjectApprovalRepository`
- Create: `agentflow-server/src/main/resources/db/migration/V120__expense_project_approval.sql`
- Test: 原预检/财务修订/定义/轮次交叉校验、唯一约束、并发与事务回滚、非空 V119 升级

- [ ] 冻结每项目原负责人、可信目录来源及原提交身份，不复制引擎审批状态机。
- [ ] 仓储复核完整项目集合与真实预检/修订；旧轮次保持未记录。
- [ ] JDBC 重启读取、串单/串轮/错版本、失败回滚和迁移验证通过后本地提交。

### Task 3: 提交接线、必经 ALL 与固定责任

**Files:**
- Modify: `ExpensePrecheckEvaluator`, `ExpenseSubmissionService`, `ExpenseReductionService`, `ExpenseFormContract`
- Create: `ExpenseProjectApprovalPolicy`、费用项目绑定服务
- Modify: `ExpenseProcessPolicy`, `DefinitionValidator`, `DefinitionReferenceInspector`, `ExpenseSelfApprovalBindings`
- Modify: 必要的原生任务动作/会签成员读写守卫与职责查询
- Test: 新真实项目会签集成测试，复用组织/财务合成来源和原生引擎

- [ ] 先复现多项目缺人/少批、无项目空名单、职责绕过和后续核减路由丢失。
- [ ] 有/无项目双向路径证明、只读敏感字段、派生布尔值和一次提交冻结接通。
- [ ] 复用费用职责与 ALL；保留同人项目映射/上溯依据，限制通用成员改动和转交，委派/有效代理不抹掉原责任。
- [ ] 当前资格、并发/回滚、暂停/终止、三轮及核减等范围回归通过后本地提交。

### Task 4: 原依据查询、费用页面、设计器及模板

**Files:**
- Create: 原轮次项目依据 Query/Controller、前端解析与详情组件
- Modify: 费用预检提示、`api.ts`、`expenses.ts`、定义选人/节点职责和费用页面
- Modify: 费用模板与配套样例新版本、真实路径模拟、`agentflow-server/src/main/resources/api/openapi.json`
- Test: 实际组件渲染、请求竞争/恢复、接口权限、契约和页面解析

- [ ] 查询展示旧未记录/无项目/已记录及原负责人和实际责任，不授予额外读取权。
- [ ] 专用选人、ALL 和固定责任限制在设计器和模板中可见；已发布副本保持。
- [ ] 范围 Java/Web、实际响应解析、OpenAPI、类型检查和构建通过后本地提交。

### Task 5: 固定 V119 升级、原会签恢复与最终审查

**Files:**
- Create: `scripts/check-expense-project-approvals.py` 及实际前端响应检查
- Modify: 本规范、计划、任务台账及机器证据

- [ ] 固定 V119 `budget-exceptions-server-eab64cd5.jar`（SHA256 `e5978f9fe03dd8ff37416e42f9baf61a9103853703920860038adc6d3f21629e`）建立旧在审与原预算，升级核对全部旧列。
- [ ] 多项目 ALL、同人多项目、无项目、负责人变更/失效、核减及多轮实际 HTTP 验证。
- [ ] 处理中强退、原键回执恢复、独立配套还原后继续原会签；分别核对各版本契约和实际页面解析。
- [ ] 归档源码/固定包/失败与成功记录，执行一次全计划独立审查并修复，浏览器/PG 保持 OPEN。

## 初始决策

- Ruling: 继续用户已授权的推荐规则，不重复询问逐任务许可。
- Ruling: 专用项目节点要求已有费用自审批策略，复用本轮候选与上溯；仅明确不适用且静态不可达的无项目节点可省略候选，不放宽其他空选人检查。
- Ruling: 新提交含项目却缺必经安全节点时阻断；旧在审不迁移到新模板，不补造项目审批。
- Ruling: 项目固定名单不提供通用加减签/转交；受控委派协助和有效代理沿原责任办理。
