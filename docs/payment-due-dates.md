# F16 付款到期日

状态：授权日期、旧 JSON 接续、V117 迁移、日期筛选排序及页面已通过本地范围测试；固定包升级、强退接续、独立恢复及实际 HTTP/前端解析已通过，等待最后一次独立审查。用户已授权财务在每次付款授权时明确填写并固定到期日，历史未填写记录单独显示；沿用当前隔离分支和本地优先交付。

## 事实与职责

财务入口为 `FinancePaymentStatus` → `financePaymentInput` → `FinancePaymentController` → `FinancePaymentActions.authorize` → `PaymentAuthorization.issue` → `JdbcPaymentAuthorizationRepository`。出纳入口为 `CashierWorkspace` → `CashierPaymentController` → `CashierPaymentWorkspace` → 同一授权仓储。影响员工借款和报销付款授权、重新核对账户后的新授权、单笔与批次执行、出纳目录及原键恢复。

到期日属于财务授权决定，存入 `PaymentAuthorization.Decision`；索引、总数和键集分页归仓储；参数解析和当前权限仍由入口负责。页面展示受控 `PaymentView`，不取得额外财务明细或账户事实。

## 明确规则

- 新授权必须由财务填写 `dueDate`，格式为 `YYYY-MM-DD`，支持公历 0001–9999 年的合法日期。允许登记已逾期业务，不添加必须未来的规则，不以今天作为默认值。
- 每份授权日期固定。过期、作废或安全结束后，下一份授权重新填写；不改写旧决定。此日期不改变原有最长 24 小时授权窗、资金命令、金额、预算、凭证或实际到账结论。
- 历史授权缺字段恢复为 null，显示“历史未设置”。迁移只加可空日期投影及索引，不改原 JSON、版本、修订、资金命令或摘要；严格读取核对日期投影与原决定。
- 旧请求原键重放必须继续工作。缺日期的旧正文仍可解码，只有幂等执行器确认为新授权时，服务入口才要求日期；已保存旧回执仍经原权限校验返回。新请求缺日期直接拒绝。OpenAPI 注明这一兼容边界，不能用 DTO 必填校验在幂等回放之前挡住原请求。
- 新决定的旧空字段序列化保持生产 `non_null` 规则，特别验证旧 `decision_json` 与更新条件相同，不能因重序列化添加 null 字段使旧授权无法继续执行。

## 出纳查询

保留法人、固定出款账户、页大小和原授权号游标。新增 `dueFrom`、`dueTo`（含边界）、`undated=true` 和 `sort`。日期范围允许单侧；“历史未设置”与日期范围互斥；未知、重复、空、非规范或倒置条件直接拒绝。

`sort=AUTHORIZED_AT_DESC` 为缺省值，保持原行为；`DUE_DATE_ASC` 按日期升序、null 放末尾，同日再按原授权时间和编号降序。服务器从原授权读取游标日期及时间，不能信任客户端声明的排序事实。列表、总数和游标检查共用当前法人任职及全部筛选条件；同日跨页、从有日期跨入 null 段及全 null 页都不能重漏。

财务新授权表单和核对账户后的授权都要求显式日期。取消、切换动作/身份/轮次清空未提交日期；结果未知时保留原请求正文和幂等键。出纳更换范围或排序立即清空旧游标和迟到结果，保留付款操作中的原状态锁。

## 验收边界

先由真实授权 API 复现日期字段缺失，再验证日期固定、旧 JSON/旧请求兼容、非法日期、日期与授权过期的独立性、出纳范围/排序/权限与并发分页、财务和出纳页面。固定包通过旧公开接口建立非空授权，再验证升级、原付款/原键接续、重启及数据库与财务接收方的配套恢复。浏览器和 PostgreSQL 验收仍受已有环境限制，不能被本地组件或 H2 结果替代。

## 本地验证记录与复现

授权及旧数据阶段通过 181 条范围用例；日期查询与页面阶段的最终范围为 184 条 Java 和 62 条前端用例，两个阶段存在重叠，数量不能相加。类型检查、Vite 构建及 OpenAPI 392 个操作、841 个 schema、129 个请求示例通过。源码与报告分别见 [Task 1](evidence/payment-due-dates-task1-20261004.json) 和 [Task 2](evidence/payment-due-dates-task2-20261004.json)。

运行验证使用固定的 V116 基线包和 V117 新包，清单明确 `sourceCommit`、两个 JAR 路径及 SHA-256、`baselineSchema: 116`、`targetSchema: 117`、前端 `webDist` 和逐文件 `webFiles` 摘要。执行：

```sh
python3 scripts/check-payment-due-dates.py --artifacts /fyoung/tmp/<固定包清单>.json --java <Java17路径>/bin/java
node agentflow-web/scripts/check-payment-due-dates-runtime.mjs /fyoung/tmp/agentflow-due-runtime-<运行目录>
```

脚本只创建独立的 `/fyoung/tmp` 运行目录，使用本地演示身份与合成财务服务，显式记录 15 秒恢复租约、250 毫秒轮询的验收配置；不改变生产默认值。每次保留真实 HTTP 原文、接收方原命令、失败记录、数据库快照、源文件和固定包摘要。辅助 Java 程序按旧列逐表比较全部数据，配套恢复另比较完整 schema 语句；不会把局部 SQL 查询当作完整恢复证据。

固定包共 7 次启动、665 次业务 HTTP、165 次合成财务服务调用；665 个响应、141 个请求和 238 次真实前端解析通过。V116→V117 保留 272 张既有表、819 行旧字段值，7 个原授权键均可回放缺日期正文。独立恢复逐表核对 273 张表、1,394 行及 2,155 条完整结构语句，恢复后原库保持、新旧授权均可继续付款；见[运行证据](evidence/payment-due-dates-runtime-20261004.json)。两次受控付款强退都保持原命令、摘要、日期和一次发送。
