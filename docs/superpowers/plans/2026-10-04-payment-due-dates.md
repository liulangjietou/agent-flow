# F16 付款到期日实施计划

> 使用 `superpowers:executing-plans` 在当前工作区逐项实施；遵循 TDD，整个范围结束后一次独立审查。用户已授权按推荐规则持续开发，不重复询问方案或执行方式。

**Goal:** 财务显式填写并固定付款到期日，出纳能按日期筛选和排序，历史未设置及旧付款接续得到保留。

**Architecture:** 日期归既有授权 Decision，仓储增加派生日期索引与参数化查询；现有财务及出纳入口维持原权限和幂等语义。付款网关命令不增加字段。

**Tech Stack:** Java 17、Spring/JDBC/Flyway、H2/PostgreSQL、Vue 3/TypeScript；无新依赖。

**Spec:** [付款到期日](../../payment-due-dates.md)。

## Global Constraints

- 当前工作区 `/fyoung/tmp/agentflow-governance-identifier-integration`，开始基线 `10bc4f2420c7c8a498a5b81407db6a28a39ad77a`；F04 本地实现与最终审查修复已完成，继续保留其验收边界。
- 保留主工作区和 PID 87821；临时文件在 `/fyoung/tmp`；一次一个 Maven，运行期间不编辑 Java、测试或资源。
- 中文注释、英文日志、新 Java 类型标注 `@author owlzhangfq@gmail.com`；JSON 使用 `io.agentflow.common.JsonUtil`。
- 用户的范围回归要求优先；不为本任务重跑无关全项目测试。最终整体验收由 V02/E15 承担。
- 不绕过现有浏览器和 PostgreSQL 限制；V02/E15 前不推送或创建 PR。

## Review Focus

1. 旧 JSON 缺字段时，日期为 null 且更新条件仍匹配旧 `decision_json`；Task 1 覆盖原授权执行、到期、作废以及原命令摘要保持。
2. 同日多条、日期与 null 混合且跨页；Task 2 覆盖稳定顺序、全 null 尾页、同条件总数和外法人游标。
3. 原版本缺日期请求已经成功但响应丢失；Task 3 使用旧安装包保存原键，升级后原正文回放不能被新增必填校验阻断。
4. 到期日可能已过去或晚于授权窗；Task 1/2 验证它不延长执行窗口、不修改到账结论，新授权和账户复核后授权必须重新明确填写。
5. 日期/排序/身份改变时迟到响应不能拼接到新页；Task 2 验证页面清理、原键未知状态和角色边界。

---

### Task 1: 固定人工日期与旧授权兼容

**Files:**
- Modify: `agentflow-domain/src/main/java/io/agentflow/finance/PaymentAuthorization.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/FinancePaymentActions.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/PaymentView.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/JdbcPaymentAuthorizationRepository.java`
- Create: `agentflow-server/src/main/resources/db/migration/V117__payment_due_date.sql`（执行前复核编号未占用）
- Modify: `agentflow-server/src/main/resources/api/openapi.json`
- Test: `agentflow-domain/src/test/java/io/agentflow/finance/PaymentAuthorizationTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/finance/PaymentPersistenceTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/finance/PaymentDueDateMigrationTest.java`
- Test fixture compatibility: `agentflow-server/src/test/java/io/agentflow/finance/CashierPaymentFiltersTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/expense/ExpenseSubmissionIntegrationTest.java`
- Test: `agentflow-server/src/test/java/io/agentflow/expense/AdvanceRepaymentIntegrationTest.java`

**Interfaces:** `Decision(..., LocalDate dueDate)` 保留三参数历史构造；`issue(..., Instant expiresAt, LocalDate dueDate)` 新入口必须有日期，旧入口只用于历史兼容夹具。`Authorize` 增加可解码的 `String dueDate`，新写入在幂等回调内的服务入口一次验证规范日期并转换为 LocalDate；`PaymentView.dueDate` 明确序列化 null。仓储添加 date 投影和严格读取，更新不允许日期改变。

- [x] 在真实授权 API 增加合法日期固定及缺失日期拒绝用例，确认现有实现失败；仅运行新增方法定位 RED。
- [x] 实现领域、输入、投影、不可变仓储及 V117；不改变外部付款命令或原授权窗口。
- [x] 补日期边界、旧 JSON 规范化与更新、非空迁移旧列保持、原资金命令摘要保持；必要范围旧请求夹具改为显式合成日期。
- [x] 运行领域/持久化/授权相关范围与 OpenAPI，记录结果并本地提交。

### Task 2: 日期筛选排序及两端页面

**Files:**
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/CashierPaymentWorkspace.java`
- Modify: `agentflow-server/src/main/java/io/agentflow/finance/JdbcPaymentAuthorizationRepository.java`
- Modify: `agentflow-web/src/payments.ts`, `agentflow-web/src/cashierFilters.ts`, `agentflow-web/src/api.ts`
- Modify: `agentflow-web/src/components/FinancePaymentStatus.vue`, `agentflow-web/src/components/PaymentFacts.vue`, `agentflow-web/src/components/CashierWorkspace.vue`
- Modify: `agentflow-server/src/main/resources/api/openapi.json`
- Test: `agentflow-server/src/test/java/io/agentflow/finance/CashierPaymentFiltersTest.java`
- Test: `agentflow-web/tests/cashier-filters.test.mjs`, `agentflow-web/tests/payments.test.mjs`, `agentflow-web/tests/payment-batches.test.mjs`

**Interfaces:** 列表查询增加 `dueFrom`/`dueTo`/`undated=true`/`sort=AUTHORIZED_AT_DESC|DUE_DATE_ASC`，未指定排序保持旧行为；日期升序 null 最后，同日授权时间/编号降序。原授权号游标不变，日期和时间从服务端原授权读取。筛选选项接口保持法人/账户职责。

- [x] 先确认跨同日、跨 null 段分页、非法/无权条件和页面输入/晚到结果用例失败。
- [x] 实现共享参数化范围、排序键集边界、页面必填日期与只读事实、筛选和排序控件。
- [x] 验证历史未设置、新旧授权权限、日期与授权窗口独立、取消/切换/原键恢复；运行匹配范围、类型检查、构建和 OpenAPI；本地提交。

### Task 3: 固定包升级及恢复验收

**Files:**
- Create: `scripts/check-payment-due-dates.py`
- Create: `scripts/payment-due-dates-support/SnapshotH2.java`, `CompareH2Schema.java`（离线摘要与完整 schema 核对，无历史临时脚本依赖）
- Create: `agentflow-web/scripts/check-payment-due-dates-runtime.mjs`
- Modify: 本计划、`docs/payment-due-dates.md`、`docs/cashier-payment-filters.md`、未完成台账及阶段证据。

- [x] 固定 V116 基线与新版；旧公开 API 建立借款和报销授权、已保存原键及原付款命令，非空升级核对全部旧列与摘要。
- [x] 实际 HTTP 验证新日期、历史 null、法人/账户/日期组合、同日与 null 分页、权限拒绝、旧键重放、新键缺日期拒绝、财务核对账户后重新授权。
- [x] 付款处理中强退后沿原号查询；配套恢复后继续原付款，新旧日期及原命令保持；真实页面解析器及 OpenAPI 校验响应。
- [x] 归档源码、包摘要、失败及成功记录；整个 F16 范围独立审查一次，复现修复实际缺陷后更新台账。浏览器/PG 未验收时仍 OPEN。

## 执行记录

- Pre-flight: Task 1 的 Decision/PaymentView 日期由 Task 2 查询和页面消费；Task 3 同时消费历史 null 与当前日期。日期加入决定而不加入付款命令，避免改动既有资金摘要。
- Ruling: 既有用户授权覆盖方案和持续实施，沿用 inline 执行；不再次请求技能默认设计批准。
- Ruling: 新授权必填校验在幂等回调内，兼容原版本缺字段请求的已保存成功回执；不能把业务必填一概加在 DTO 解码阶段。

- Ruling: HTTP 到期日保留 String 到幂等回调，入口统一校验规范 YYYY-MM-DD 并解析 LocalDate。这样既拒绝隐式日期转换，也允许旧缺字段正文原键回放；领域和存储只接收已解析日期。
