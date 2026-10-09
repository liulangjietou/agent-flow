# 企业报销与 Agent 优化交付及验收

本批沿用 Java 17、Flowable、MyBatis 和现有财务领域。Agent 自动处理授权材料、查询和建议；员工确认费用修改，审批与付款继续由具名人员及已发布规则执行。适用基线为中国境内多法人、多部门、人民币场景。真实 SSO、组织、预算、发票、ERP、资金服务及模型供应商仍待选定。

## 方案对应关系

| 方案事项 | 实现与职责归属 | 本地验收依据 |
| --- | --- | --- |
| 可恢复的报销办理入口 | `ExpenseHandlingTask` 维护状态、双版本和最多 32 步；`ExpenseHandlingService` 组合本人费用、票据、制度与预检读取。编辑器统一提供办理目标、原步骤和四个业务入口 | 领域边界、HTTP 身份隔离、原键回放、实际编辑器 |
| 串联既有助手 | 费用草稿、预检及解释的排队、执行和确认追加到当前办理；保存、补正、提交与办理轨迹同事务。界面刷新或进程重启读取原记录，不创建替代模型任务 | 新建仓储恢复、进程重启、补正事务回滚 |
| 票据整理 | 同一编辑器定位原票据整理面板；只读工具复用本人票夹读取。金额继续来自原件抽取确认与查验链路，正式保存及提交重新校验 | 原票据填报测试及办理工具身份测试；真实票据查验待接入 |
| 制度依据与补正 | 已发布、版本匹配的结构化制度可作为明确勾选的解释来源；补正清单绑定问题、原解释及双版本，员工确认后复用原保存用例并排队新预检 | 制度来源测试、失效与虚构引用拒绝、补正 HTTP 回执丢失恢复 |
| 审批统一阅读 | 当前审批任务按冻结事实、缺失证据、提交时制度、人工财务意见、模型解释排列。摘要与风险仍分别选择来源、保存人工复核 | 字段读取拒绝时清除旧事实、风险及摘要原有权限测试 |
| 财务恢复说明 | 付款与凭证面板根据原业务状态、原编号和服务端动作资格解释下一步；沿用原查询、对账、原号重发或安全结束入口。核销和归档继续使用已有缺项提示与恢复用例 | 资金未知、ERP 查无原操作、无权限时不提示重发；原付款及凭证回归 |
| 调度隔离 | 五类助手独立固定间隔调度与有界线程池，票据抽取沿用自己的工作器；领取和租约仍由数据库协调 | 慢任务隔离测试、工作器追踪与执行集成测试 |
| 持久用量 | 六类模型用途在网络调用前写入原运行身份；调用后保存原始用量、排队与执行毫秒数、格式校验结果。观测单独提交，不把网络等待放进业务事务 | 真零与未知用量、格式失败保留用量、观测落库失败、跨人查询、强退记录 |
| 模型效果与成本评估 | `scripts/evaluate-expense-agent.py` 离线对比财务标注与实际输出。安全动作断言独立于评分；成本用 Decimal 和生效价格版本计算 | 八项风险测试；合成样本仅输出 `EVIDENCE_ONLY` |
| PostgreSQL 与非空升级 | V129 补正关联，V130 用量，V131 办理轨迹均为增量表。活动办理唯一，身份与原费用通过数据库外键绑定 | PostgreSQL 17 上的集成及非空 V129 → V131 升级 |
| 企业环境验收 | 对已选真实适配器逐场景执行，财务确认样本和门槛后再评价准入 | **待外部输入**，不能以本地合成通过替代 |

办理状态 `OPEN / NEEDS_INFORMATION / NEEDS_CONFIRMATION / WAITING` 不等于预检 `READY`，也不等于申请 `APPROVED`。同一子运行只更新原步骤；较新预检结论替代旧结论参与当前状态归并，但历史步骤保留。达到上限或结束办理只结束该记录，不取消已授权的原子任务，不影响单独使用已有业务入口。

四种手工读取返回本人可访问事实。自动办理须额外预览并明确授权费用、目标与工具结果的模型发送；未授权不会因保存目标而启动模型。只读查询在调用前提交原步骤、输入摘要与授权依据，结果先保存再登记；成功原键重放恢复原回执。费用修改继续由本人确认。

## 验证命令与证据边界

使用 JDK 17。前端先执行 `npm ci`。所有临时输出置于 `/fyoung/tmp`。

```bash
mvn -B -ntp -pl agentflow-server -am \
  -Dtest=ExpenseHandlingTaskTest,AgentExecutionTelemetryTest,AgentWorkerTraceTest,AgentBusinessTraceTest,ExpenseRiskExecutionTest,OpenApiContractTest,AssistSchedulingTest,PrecheckExplanationIntegrationTest,ExpenseDraftAssistIntegrationTest,ExpenseHandlingMigrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djava.io.tmpdir=/fyoung/tmp test

node agentflow-web/scripts/test-requests.mjs expense-handling.test.mjs precheck-explanation.test.mjs expense-draft-assist.test.mjs expense-review.test.mjs payments.test.mjs vouchers.test.mjs
npm --prefix agentflow-web run build
node agentflow-web/scripts/check-openapi.mjs
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p 'test_expense_*.py'
```

进程验收使用已打包 jar，显式覆盖数据源为本次目录的 H2 文件并关闭延迟落盘。不能继承项目默认 PostgreSQL 连接。`--output-dir` 必须为新目录；脚本只停止自己的后端与前端进程。浏览器验证需要本地 Playwright 和 Chrome。

```bash
python3 scripts/check-expense-handling.py \
  --java /path/to/jdk17/bin/java \
  --jar agentflow-server/target/agentflow-server-0.1.0-SNAPSHOT.jar \
  --output-dir /fyoung/tmp/agentflow-handling-acceptance-unique \
  --browser
```

此验收通过真实 Java HTTP、独立数据库、真实浏览器执行，但身份、财务和模型数据是合成夹具。检查范围：排队后重启沿用原任务；已保存补正丢失响应后按原键恢复；强退后不重发模型；未知用量保持未知；本人以外身份不能读取；编辑器刷新恢复原步骤；未保存修改时关闭事实工具入口；390px 视口无横向溢出。`result.json`、HTTP 记录、模型请求计数和截图保存在指定目录。结果报告没有 `PASS` 时不算通过。

PostgreSQL 连接由 `AGENTFLOW_EXPLANATION_TEST_*` 与 `AGENTFLOW_HANDLING_MIGRATION_*` 环境变量提供，每轮必须为每组新建独立空测试库；迁移用例在其中建立 V129 非空业务基线后升级，不能复用已经升级的验收库。CI 为这两组提供新建的单独数据库。H2 进程测试不能替代 PostgreSQL 验证。

## 本批本地验证记录（2026-10-09）

验证基于已合并首批改动的 main（`298f1c78`），按本批调用链执行，未将结果表述为全项目测试或企业验收。

| 验证 | 结果 |
| --- | --- |
| Java 领域、调用追踪、调度及助手集成 | 129 项通过；其中包含领域状态 6 项 |
| 模型工作器、预检及提交相关回归 | 407 项通过 |
| 无效模型正文的用量保留、集成及迁移最终复核 | 43 项通过；上述各轮存在重复，不相加作为独立测试数量 |
| PostgreSQL 17 集成与非空升级 | 30 项通过 |
| 前端办理、补正、审批、付款及凭证 | 124 项通过；构建通过，保留现有大包体积警告 |
| 评估工具与验收数据库隔离 | 10 项通过 |
| OpenAPI 与 Java 作者检查 | 403 个操作、884 个 schema、131 个示例校验通过；无缺失作者标注 |
| Java HTTP + 文件数据库 + Chrome | PASS：三次启动、原键补正恢复、强退不重发、完整 10 步刷新恢复、制度查询、未保存修改禁用工具；桌面 1360×1000、手机 390×844 |

最终进程测试使用的 jar SHA-256：`4984b9d5a811aa52abf4bf7c68c49239dbb44b078adeadfd9bc428f6cdeca777`。模型、组织和财务端点均为本地合成夹具，没有据此评价真实模型质量或真实适配器可用性。排队重启只发生一次模型调用；执行中强退后新增模型调用为零，未返回用量保留未知。

## 模型评估数据与准入

仓库示例 `docs/examples/expense-agent-evaluation.synthetic.jsonl` 明确标记为合成，示例中的价格或分数不能用于生产选型。每行包含：

- `id`、`provenance`（`synthetic` 或 `enterprise`）、`labelled_by`，以及明确的 `risk_catalog`。
- `expected.fields / citations / risks / allowed_actions`：财务标注的预期字段、来源版本与摘要、风险及允许动作。金额字段使用十进制字符串。
- `actual`：实际模型输出、实际动作、可选人工修改计数、耗时和服务商原始用量。没有观测时省略，不填零。

```bash
python3 scripts/evaluate-expense-agent.py \
  docs/examples/expense-agent-evaluation.synthetic.jsonl \
  --output /fyoung/tmp/expense-agent-synthetic-report.json

python3 scripts/evaluate-expense-agent.py /fyoung/tmp/finance-labelled-cases.jsonl \
  --enterprise --thresholds /fyoung/tmp/finance-approved-thresholds.json \
  --pricebook /fyoung/tmp/effective-model-prices.json \
  --output /fyoung/tmp/expense-agent-enterprise-report.json
```

阈值文件含 `reviewed_by` 和 `metrics`，指标为 `field_accuracy`、`citation_precision`、`citation_recall`、`risk_false_positive_rate`、`risk_false_negative_rate`、`human_edit_ratio`、`duration_p95_ms`。前三者为下限，其余为上限。由财务根据真实风险设置，项目不预设任意“合格分”。所有分母为零的指标保持未知。

价格文件含 `prices` 数组，每项为 `provider / model / version / currency / valid_from / valid_until / input_per_million / output_per_million`。费率用十进制字符串；同提供者、模型的生效区间不能重叠。报告仅列出有依据的逐案例估算与未知案例数，不把不同货币混加，不代表最终账单。

`--enterprise` 要求全部样本真实、全部指标阈值由财务确认、人工复核/耗时/价格完整。未授权动作始终阻断，良好效果分数不能抵消越权审批或付款。退出码 2 表示输入或验收阻断；`EVIDENCE_ONLY` 仅表明工具输出可供检查。

## 仍需真实业务输入的事项

| 待输入 | 关闭验收需要的证据 |
| --- | --- |
| SSO、组织及多法人任职 | 实际租户与身份映射、离职/失权、跨法人及同名人员用例；当前 UI 和服务端仍按已有服务器身份授权 |
| 预算、发票、ERP、资金 | 正式协议和测试环境，验证超预算、重复票据、外部结果未知、回调冲突、原命令恢复及金额精度 |
| 企业制度原文 | 访问边界、法人、适用日期及正式发布版本；结构化制度先用现有查询，未提供原文时不引入无法验收的 RAG |
| 模型与材料外发边界 | 确认供应商、模型、协议、超时和数据发送范围，填写实际价格版本 |
| 财务样本与准入标准 | 财务标注、人工复核记录、真实耗时/用量、风险阈值和执行上限校准 |
| 低风险自动通过 | 尚未授权新规则；如需启用，应另行明确并发布可审计规则，不能使用模型置信度批准 |

现有 `remaining-task-ledger.md` 的待交付口径不因本次新增测试而批量关闭。真实联调、企业数据验收及正式发布继续逐项登记。

## 四项增量开发（2026-10-09）

| 截图中的剩余项 | 本批实现 | 验证要点 |
| --- | --- | --- |
| 受控 Agent 自动编排 | `ExpenseAgentRun` 管理最多 12 步和 30 分钟授权；实际模型依次选择白名单查询、读取结果、提问或完成；后台复核原登录及人员启停，修改前停在人工节点 | 查询前持久身份、读取后决策、补充回答、取消迟到结果、撤销登录、版本及目标变更、未知模型步骤恢复 |
| 结构化补正与差异确认 | v2 建议包含六类允许字段的修改前后值、依据及影响；编辑器逐项勾选；服务端只接受原建议编号，再经原费用服务保存和预检 | 伪造来源、错误原值、重复字段和金额字段拒绝；只保存选中字段，保留金额与分摊；原键回放 |
| 票据任务接续 | 原票据抽取排队与办理绑定同事务，抽取领取、成功、失败及复核更新原轨迹；本人确认后进入费用草稿人工节点；草稿仍用原目录预览及确认服务 | 真实 XML 抽取、原任务绑定、确认后进入 DRAFT；实际草稿排队和确认后结束自动办理，费用仍待本人保存 |
| 外部查询持久执行 | V132 保存原输入、摘要、授权及固定制度目标；RUNNING → PREPARED → RECORDED，失败保留 FAILED；恢复原编号和原参数 | 事务外调用、外呼前有记录、查询失败重试、登记回滚不丢准备结果、重启沿用原记录、丢失回执无额外调用 |

V133 保存自动循环、步骤变迁、原模型请求和子任务绑定，并将 HANDLING 纳入模型用量。非空升级测试保留既有用量，拒绝未知用途。原财务、审批和付款聚合没有被 Agent 替代。

增量验收命令：

```bash
mvn -B -ntp -pl agentflow-server -am \
  -Dtest=ExpenseAgentRunTest,HandlingReadExecutionTest,ExpenseFieldPatchTest,ExpenseHandlingTaskTest,PrecheckExplanationRunTest,PrecheckExplanationIntegrationTest,ExpenseDraftAssistIntegrationTest,InvoiceExtractionIntegrationTest,ExpenseHandlingMigrationTest,OpenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djava.io.tmpdir=/fyoung/tmp test
node agentflow-web/scripts/test-requests.mjs expense-agent.test.mjs expense-handling.test.mjs precheck-explanation.test.mjs expense-draft-assist.test.mjs invoice-extraction.test.mjs
python3 scripts/check-expense-orchestration.py --java /path/to/jdk17/bin/java \
  --jar agentflow-server/target/agentflow-server-0.1.0-SNAPSHOT.jar \
  --output-dir /fyoung/tmp/agentflow-orchestration-acceptance-unique
```

浏览器脚本使用真实编辑器与 Java HTTP，记录桌面和移动截图；只在 `result.json` 明确 PASS 后算通过。财务、模型和组织数据仍为合成夹具。PostgreSQL 非空 V131 → V133 升级使用另建空库 `AGENTFLOW_ORCHESTRATION_MIGRATION_URL`，不能指向工作库；账号沿用同次 `AGENTFLOW_HANDLING_MIGRATION_USER/PASSWORD`。企业真实供应商与财务验收数据仍待选定。

本批最终本地验收：相关 Java 用例共 129 项通过；其中 75 项另外在 PostgreSQL 17 的四个新建隔离库通过，验证后仅删除这四个库。前端请求、状态与组件用例 83 项通过；TypeScript 和生产构建通过；OpenAPI 校验 411 个操作、896 个模型；Java 署名检查与差异空白检查通过。

真实浏览器验收覆盖 1360×1000 桌面及 390×844 移动端：授权后自动查询、读取结果后提问、刷新恢复原运行、本人回答后继续、两处建议仅采纳一处、实际 XML 抽取和本人确认、确认票面值带入原费用草稿表单、外部制度查询失败后重启沿用原编号，以及成功回执丢失后恢复且无额外外呼。页面无脚本错误，移动端无横向溢出。

模型调用中断与查询重试的边界不同：原模型步骤不静默重发；原登录仍有效时，本人确认后开启下一次决策并保留旧步骤。原登录撤销、人员停用或费用版本变化会停止运行。演示令牌随进程重启失效，不能用另一次登录冒充原授权；企业跨实例恢复依赖已有持久 OIDC 会话配置。真实供应商联调、真实财务样本效果和生产准入尚未由这些合成测试证明。
