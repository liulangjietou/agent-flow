# AgentFlow

AgentFlow 是面向 OA、财务和表单审批的 DDD 工作流平台骨架。领域层表达审批、流程定义与工作日历规则，Flowable 作为基础设施防腐层运行 BPMN，Web 层提供租户隔离后的 REST API，Vue 设计器负责流程图编辑。

当前已贯通官方模板复制、流程草稿编辑、版本化基础表单配置、校验、版本发布、申请提交和人工审批。初始化向导、完整组织、节点字段权限与附件、财务领域、Agent 协作和生产认证仍在开发范围内；当前版本用于本地开发验收。详细进度与验收证据见相邻文档目录中的 [开发进度与验收记录](../doc/06-开发进度与验收记录.md)。

“流程管理”提供[流程目录](docs/definition-catalog.md)，支持名称、状态、准确流程标识及版本筛选，分批加载草稿和发布版本；打开时读取最新配置并保护未保存修改。

申请、首次引导、版本比较和筛选也已接入[按需流程选择](docs/definition-selection.md)：列表只读摘要，选择后再取得所需配置；Web 不再启动时加载全部流程图和表单。

待办办理支持[批准意见填写与确认](docs/approval-comments.md)：意见选填，确认后提交；支持取消、原请求恢复和会签分别留痕，可在已办记录与操作审计追溯。

[Agent 审批摘要核心](docs/agent-summary-core.md)正在开发：已建立领域状态、证据绑定与事务存储；真实模型、应用 API、执行器和 UI 尚未接入，不计为可用 Agent 功能。
- [Agent 摘要运行记录](docs/agent-summary-records.md)：有申请授权的分页目录与详情展示；模型生成和人工复核写入尚未启用。

管理员“操作审计”支持按操作人、动作、来源、申请及 UTC 时间跨申请检索追加事件，保留缺失元数据的旧记录，并可下钻原申请详情。详见[管理员操作审计](docs/audit-search.md)。

操作审计可按已查询条件[导出审计摘要 Excel](docs/audit-export.md)，一次最多 10,000 条；保留操作事实、原始标识和 UTC 时间，旧事件缺失信息留空，不包含表单正文和审批意见。

管理员“申请记录”支持标题/单号、状态、流程版本、申请人和 UTC 创建日期筛选，并通过有界摘要分页定位历史申请。详情仍使用原授权接口，普通用户权限不扩展；详见[管理员申请检索](docs/application-search.md)。

管理员可按相同筛选[导出申请摘要 Excel](docs/application-export.md)，一次导出全部匹配记录，保留单号、状态及 UTC 时间；最多 10,000 份，超过时要求缩小筛选。单号和标题按文本保存，不包含表单正文或审批意见。

## 单命令演示安装

Docker 已启动时，在仓库根目录执行：

```bash
docker compose -f compose.demo.yml up --build -d --wait --wait-timeout 180
```

打开 `http://127.0.0.1:8180`，以 `demo / admin / demo` 登录。“系统自检”显示真实依赖状态，并提供模板和流程管理入口。数据库使用持久卷，仅 Web 入口开放到本机。首次构建需要网络，停止时保留数据卷。详见[演示安装与系统自检](docs/demo-installation.md)。

演示 PostgreSQL 支持[完整备份与隔离恢复](docs/demo-backup-recovery.md)：校验归档后使用原镜像恢复到新项目、新卷及新端口，保留旧实例；恢复后可继续办理未完成会签任务。工具不会覆盖已有数据，当前不包含生产灾备、跨版本升级或 H2 备份。

## 本地启动

需要 Java 17+、Maven 3.9+ 和 Node.js 20.19+ 或 22.12+。默认使用 H2 文件库，首次启动会由 Flyway 创建业务表，Flowable 自动创建引擎表并部署 `expense-reimbursement` 示例流程。

```bash
cd /Volumes/fyoung/code/AI/flow/agentflow
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl agentflow-server -am package
java -jar agentflow-server/target/agentflow-server-0.1.0-SNAPSHOT.jar
```

另开终端启动前端：

```bash
cd /Volumes/fyoung/code/AI/flow/agentflow
cd agentflow-web
npm ci
npm run dev -- --host 127.0.0.1
```

开发服务器会把 `/api` 请求代理到 `http://127.0.0.1:8080` 并保留同源 Host；使用 `http://127.0.0.1:5173` 访问前端。直接调用其他后端地址时，设置 `VITE_API_BASE` 指向完整 API 前缀，并将 `AGENTFLOW_WEB_ORIGIN` 设置为实际前端来源。

演示认证默认开启：租户 `demo`，用户 `admin` / `finance` / `manager` / `employee` / `alice` / `bob`，密码统一为 `demo`。生产环境应设置 `AGENTFLOW_DEMO_AUTH=false` 并接入企业 OIDC。

## 模块边界

- `agentflow-common`：认证主体、统一 JSON 和稳定错误码。
- `agentflow-domain`：流程定义、受限条件 AST、流程模拟、审批申请与日历聚合及仓储/运行时端口。
- `agentflow-server`：Spring Boot、Flowable/Flyway/JDBC 适配器、认证过滤器和 REST API。
- `agentflow-web`：Vue 3 + TypeScript 的任务中心、申请表单和流程设计器。

流程设计器只接受 `START`、`END`、`USER_TASK` 和 `EXCLUSIVE_GATEWAY` 节点；条件使用白名单语法（例如 `amount >= 1000 AND department == 'finance'`），不会执行用户输入的 JUEL、脚本或 Java 代码。

Web 端的流程管理、申请记录和待办动作均调用服务端接口。流程设计器支持从当前身份源选择指定账号或审批角色，显示有效成员人数，发布前重新检查无人审批节点；组织关系解析、Agent 预检、发票核验、预算控制和付款台显示为未接入状态，不使用演示数据冒充真实结果。详见[审批人配置与发布检查](docs/designer-assignees.md)。

申请记录支持申请人撤回当前审批、查看退回或撤回说明、保存补正和重新提交；每次提交保存独立轮次快照及实际流程实例，后续修改不覆盖旧轮次。详见[申请撤回、补正与重新提交](docs/approval-resubmission.md)。

申请人可[作废](docs/application-cancellation.md)草稿、已退回或已撤回的申请，保留正文、旧轮次与审计，作废后不能再修改或提交。未保存内容先明确处理；作废结果未知时恢复原请求，不能重复执行或改写已有审批结论。

个人工作台提供“我发起”“我的草稿”“已办记录”，按本人归属和真实办理事实查询，支持搜索、筛选、分页及继续填写；转交后仍能查看参与过的申请。详见[个人工作台](docs/personal-workspace.md)。

待办支持[列表与看板视图](docs/task-board.md)，按待领取、已指派和待回交分栏；两种视图共用筛选、分页和详情，列内按进入待办时间排列。

待办支持委派、回交、转交、领取和释放。受托人填写意见并回交后，由原审批人最终决定；接收人从当前身份源读取，操作审计和已办保留双方办理事实。详见[任务委派与回交](docs/task-delegation.md)。

消息中心提供真实站内提醒、全部/未读筛选、未读总数、分页和已读操作，可从消息定位当前待办或申请。审批与消息共同提交，消息不扩大原申请权限；邮件、IM 和 SLA 尚未接入。详见[站内消息中心](docs/notification-inbox.md)。

待办与申请详情支持真实审批轨迹、操作审计、轮次与动作/时间筛选及游标分页。新操作在业务事务内保存当时的操作人、转交接收人和申请状态变化；旧记录没有的信息不补造。详见[审批轨迹与操作审计](docs/approval-history.md)。

申请详情还提供按实际轮次绑定的[流程图](docs/round-process-diagram.md)，展示当前节点、已离开节点、会签剩余任务以及实际流转路径与连线记录；后续发布和重提不会替换旧轮次的图。

待办与申请详情提供[提交轮次内容对比](docs/submission-round-comparison.md)，并排核对任意两轮的标题、版本、字段和表单配置，保留精确金额、空值及字段增删。对比仅使用提交快照，不包含尚未提交的修改。

审批中申请支持协作评论，按申请权限读取并保留当时的轮次和状态，支持分页、草稿保留及幂等恢复；已结束申请的追加策略待确认，当前只读。详见[申请协作评论](docs/application-comments.md)。

管理员可维护工作日历、节假日与调休，保留不可变修订并按固定版本试算到期时间。当前未关联实际审批任务期限；详见[工作日历与期限试算](docs/business-calendars.md)。

所有申请、评论、日历管理、任务动作、流程定义和模板复制写接口要求 `Idempotency-Key`。服务端在业务事务内保存成功响应，前端在网络结果未确认时保留原请求供恢复；详见[业务写请求幂等协议](docs/request-idempotency.md)。

流程管理支持文本、长文本、数字、日期、单选、布尔字段配置和填写预览。申请可先保存不完整草稿，提交时由服务端检查必填与类型；申请及每轮历史各自保留绑定表单，不随新版本改变。数字字段使用十进制字符串保留精度，分支只能引用已声明字段；详见[版本化申请表单](docs/versioned-forms.md)。

模板中心提供请假、用印和合同审批模板，包含表单、流程图、角色说明和路由样例。流程管理员复制后得到当前租户的独立草稿，可编辑后发布；模板复制记录保留来源版本，模板更新不覆盖旧副本。演示审批角色与阈值需按实际制度配置，通知文案尚不执行发送；详见[流程模板中心](docs/process-templates.md)。

流程配置支持[模板文件导入与导出](docs/portable-process-templates.md)：预览 JSON 文件、检查当前审批人、创建独立草稿；已保存与未保存设计均可导出，同标识的新发布不覆盖原版本。

设计器提供[快速步骤与高级画布](docs/quick-workflow-designer.md)两种视图：快速插入审批和嵌套条件、调整分支顺序、按表单字段配置条件；复杂流程保留在高级画布编辑。两种模式共用保存、撤销、模拟和发布链路。

设计器支持[版本化组合条件](docs/condition-language.md)：枚举 IN 多选、括号、取反及混合且/或表达式；旧草稿显式等价升级，已发布流程继续原语义，语法错误显示字符位置并定位连线。

设计器支持[数字分支覆盖与重叠检查](docs/branch-coverage.md)：遗漏输入阻止发布，重叠保留顺序并提醒；诊断提供精确示例，发布时保存提醒，校验面板打开后随编辑自动检查。

组合条件、覆盖诊断及中文条件编辑已完成[隔离环境浏览器补充验收](docs/designer-browser-acceptance.md)，记录实际操作、发布留档和仍待验证的浏览器边界。

设计器有未保存修改时，切换、新建、复制模板、打开副本、退出及恢复定义操作会显示应用内确认框；取消继续保留原内容，确认后重新检查会话和操作锁。详见[未保存流程修改的确认](docs/unsaved-design-confirmation.md)。

设计器支持对当前未保存的流程和表单运行路径模拟，显示条件分支依据、节点与连线高亮并可定位配置错误。修改设计或测试数据后旧结果立即清除，模拟不创建业务记录；详见[流程设计器模拟运行](docs/designer-simulation.md)。

画布支持 50%–160% 缩放、适应画布和可撤销的自动布局。缩放只改变视图；自动布局只修改节点坐标，长连线按当前坐标绕开节点，保存后再次打开仍可展示。详见[流程画布与自动布局](docs/designer-canvas.md)。

设计器编辑停顿 2 秒后自动保存草稿，可关闭；保存期间继续输入不会被迟到响应覆盖。版本冲突保留本地设计，支持另存草稿或确认后加载服务端版本；请求结果不确定时暂停并恢复原操作。详见[草稿自动保存与冲突恢复](docs/designer-autosave.md)。

版本比较可将当前设计与同一流程的已发布版本对照，按节点、审批人规则、连线条件、分支顺序、表单和布局显示修改前后的内容。比较只读，已有申请保持其原版本；详见[流程版本比较](docs/definition-comparison.md)。

发布时必须填写变更说明，成功后展示真实发布者、时间、权限依据和校验摘要；历史缺失记录不补造。详见[流程发布说明与发布记录](docs/definition-publication.md)。

## 验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/java" scripts/CheckAuthors.java .
mvn -B -ntp verify
cd agentflow-web
npm run test:requests
npm run build
```

全部 Java 命名类型（包括接口、枚举、record、内部类型和测试类）必须在所属 Javadoc 中包含 `@author owlzhangfq@gmail.com`。检查器解析源码语法树，缺失作者或语法错误均返回非零退出码。

本地前后端启动后，执行 `python3 scripts/check-web-proxy.py`，检查带浏览器 Origin 的演示登录成功，以及未允许来源被拒绝。省略 Origin 的 curl 请求无法覆盖这类代理问题。

在代码仓库根目录执行 `python3 scripts/check-idempotency.py`，验证演示环境中原有 8 个申请、任务与定义写接口的响应回放和关键操作的并发幂等。可用第一个参数指定后端地址，例如 `http://127.0.0.1:8081`。脚本会创建带随机业务号的演示申请与流程定义，并完成两轮审批，不删除既有数据。

执行 `python3 scripts/check-versioned-forms.py` 验证表单发布、必填校验、精确数字与单选条件、V1/V2 隔离及补正历史。它同样接受后端地址参数，只生成带随机前缀的演示记录。

需要额外验证内置与租户同名定义的来源绑定时，执行 `python3 scripts/check-versioned-forms.py http://127.0.0.1:8080 --check-bundled-binding`。此选项会保留一个固定 key 为 `expense-reimbursement` 的租户定义，仅用于尚无同名已发布模板的验收环境；登录后会先检查前置条件，再创建业务记录。详见[来源绑定验收说明](docs/versioned-forms.md#ui-与验收)。

执行 `python3 scripts/check-process-templates.py` 验证三个模板的权限、复制幂等、样例模拟、真实审批路径、独立副本和来源记录；可用第一个参数指定后端地址。脚本会创建带随机前缀的定义与申请并完成审批，保留所有验收数据。

执行 `python3 scripts/check-designer-simulation.py` 验证当前设计模拟、全部模板样例、精确边界、权限和错误定位；可用第一个参数指定后端地址。该脚本只读业务数据，并检查定义、申请、任务和模板列表前后相等。

已有发布流程时，执行 `python3 scripts/check-definition-comparison.py` 验证真实版本基线、未保存修改、权限、重复标识拒绝和业务数据只读性；同样接受后端地址参数。

在独立验收库执行 `python3 scripts/check-personal-workspace.py http://127.0.0.1:8082`，创建随机流程和申请，验证个人分页、草稿、真实办理与读取权限；保留所有验收数据。

在独立验收库执行 `python3 scripts/check-task-delegation.py http://127.0.0.1:8082`，验证委派、受托回交、原审批人最终批准、四请求并发幂等和历史参与权限；保留另一张待办供浏览器验收。

在独立验收库执行 `python3 scripts/check-notifications.py http://127.0.0.1:8082`，验证真实动作产生消息、接收范围、并发已读和权限隔离，保留全部测试数据与一张浏览器待办。

GitHub Actions 将执行作者检查、后端 `verify`、备份恢复保护测试、前端请求测试和构建；推送前的本地验证与远端 CI 状态分别记录。

- [待办检索与分页](docs/pending-task-queue.md)

开放 API 与集成：[接口契约、调用顺序与验收](docs/openapi-reference.md)。登录后从“接口文档”进入，下载当前部署的 OpenAPI JSON。

人工审批节点现支持[全员会签](docs/all-countersign.md)：全部同意才流转，任一驳回结束整轮。设计器可配置，待办展示实际完成进度；名单在节点激活时固定，支持委派协助后回交。

审批运营的指标、权限、日期边界和验证方式见 [审批运营统计](docs/approval-operations.md)。

管理员的首次使用入口、真实运行进度和样例预览见 [首次流程使用引导](docs/first-workflow-guide.md)；企业租户及身份初始化仍待接入。

- [Webhook 可靠投递](docs/webhook-delivery.md)：部署目的地、签名接入、事务 outbox、失败重试、管理员页面与本地验收示例。

- [集成投递概况](docs/webhook-overview.md)：按租户、目的地和申请统计完整当前状态，点击状态卡片筛选明细；重试不增加投递总量。

- [参与者申请检索：当前权限、历史办理与有界分页](docs/participant-application-search.md)

- [重复明细表单：列配置、行编辑与历史快照](docs/detail-table-forms.md)

流程设计器支持[条件中文说明与字段插入](docs/readable-conditions.md)，以本版本表单标签解释条件，保留原始表达式与执行顺序。

流程设计器支持[可视化嵌套条件组](docs/visual-condition-groups.md)，可配置分组且/或、整组与单项取反，共用原有校验、模拟及发布链路。

部署验收提供 [PostgreSQL 容量基线](docs/capacity-baseline.md)：新建隔离容器，通过真实申请与审批 API 生成负载，记录延迟、并发吞吐和业务一致性，结束后停止并保留数据。报告按实际资源与负载解释，不代替生产容量目标验收。

[参与者检索性能验证](docs/participant-search-performance.md) 记录大量不可见申请导致重复扫描的根因、授权等价性、双库回归与修复前后的执行计划。

[运营统计查询性能验证](docs/operations-query-performance.md) 记录完整待办总数与节点分组合并聚合、展示截断回归及同参数容量对比。

[移动端工作空间导航](docs/mobile-workspace-navigation.md)：窄屏完整文字抽屉、键盘焦点、角色菜单和退出确认，桌面侧栏共用同一份入口配置。

[待办分页查询性能验证](docs/pending-query-performance.md)：合并页面与完整总数的重复联查，保持当前权限、游标和空后续页的计数语义。

企业登录协议接入和部署边界见 [企业 OIDC 登录](docs/enterprise-oidc.md)。默认演示入口保持演示认证，启用 OIDC 必须提供显式可信配置并关闭演示认证。

企业多实例部署可显式启用 [JDBC 共享会话](docs/shared-enterprise-sessions.md)，支持跨实例回调、登录恢复和平台退出同步；系统自检展示会话存储状态。真实企业身份源、组织同步和生产集群仍需另行验收。

企业认证支持可选的 [OIDC 后通道注销](docs/oidc-backchannel-logout.md)：身份源签名通知按用户或会话跨实例生效，覆盖延迟登录回调和重复投递；需启用 JDBC 共享会话。
