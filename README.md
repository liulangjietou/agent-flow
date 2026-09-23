# AgentFlow

AgentFlow 是面向 OA、财务和表单审批的 DDD 工作流平台骨架。领域层只表达审批与流程定义规则，Flowable 作为基础设施防腐层运行 BPMN，Web 层提供租户隔离后的 REST API，Vue 设计器负责流程图编辑。

当前已贯通官方模板复制、流程草稿编辑、版本化基础表单配置、校验、版本发布、申请提交和人工审批。初始化向导、完整组织、节点字段权限与附件、财务领域、Agent 协作和生产认证仍在开发范围内；当前版本用于本地开发验收。详细进度与验收证据见相邻文档目录中的 [开发进度与验收记录](../doc/06-开发进度与验收记录.md)。

## 单命令演示安装

Docker 已启动时，在仓库根目录执行：

```bash
docker compose -f compose.demo.yml up --build -d --wait --wait-timeout 180
```

打开 `http://127.0.0.1:8180`，以 `demo / admin / demo` 登录。“系统自检”显示真实依赖状态，并提供模板和流程管理入口。数据库使用持久卷，仅 Web 入口开放到本机。首次构建需要网络，停止时保留数据卷。详见[演示安装与系统自检](docs/demo-installation.md)。

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
- `agentflow-domain`：流程定义、受限条件 AST、流程模拟、审批申请聚合及仓储/运行时端口。
- `agentflow-server`：Spring Boot、Flowable/Flyway/JDBC 适配器、认证过滤器和 REST API。
- `agentflow-web`：Vue 3 + TypeScript 的任务中心、申请表单和流程设计器。

流程设计器只接受 `START`、`END`、`USER_TASK` 和 `EXCLUSIVE_GATEWAY` 节点；条件使用白名单语法（例如 `amount >= 1000 AND department == 'finance'`），不会执行用户输入的 JUEL、脚本或 Java 代码。

Web 端的流程管理、申请记录和待办动作均调用服务端接口。流程设计器当前支持部门审批组（`MANAGER`）和财务审批组（`FINANCE`）；组织关系解析、Agent 预检、发票核验、预算控制和付款台显示为未接入状态，不使用演示数据冒充真实结果。

申请记录支持申请人撤回当前审批、查看退回或撤回说明、保存补正和重新提交；每次提交保存独立轮次快照及实际流程实例，后续修改不覆盖旧轮次。详见[申请撤回、补正与重新提交](docs/approval-resubmission.md)。

待办与申请详情支持真实审批轨迹、操作审计、轮次与动作/时间筛选及游标分页。新操作在业务事务内保存当时的操作人、转交接收人和申请状态变化；旧记录没有的信息不补造。详见[审批轨迹与操作审计](docs/approval-history.md)。

所有申请、任务动作、流程定义和模板复制写接口要求 `Idempotency-Key`。服务端在业务事务内保存成功响应，前端在网络结果未确认时保留原请求供恢复；详见[业务写请求幂等协议](docs/request-idempotency.md)。

流程管理支持文本、长文本、数字、日期、单选、布尔字段配置和填写预览。申请可先保存不完整草稿，提交时由服务端检查必填与类型；申请及每轮历史各自保留绑定表单，不随新版本改变。数字字段使用十进制字符串保留精度，分支只能引用已声明字段；详见[版本化申请表单](docs/versioned-forms.md)。

模板中心提供请假、用印和合同审批模板，包含表单、流程图、角色说明和路由样例。流程管理员复制后得到当前租户的独立草稿，可编辑后发布；模板复制记录保留来源版本，模板更新不覆盖旧副本。演示审批角色与阈值需按实际制度配置，通知文案尚不执行发送；详见[流程模板中心](docs/process-templates.md)。

设计器有未保存修改时，切换、新建、复制模板、打开副本、退出及恢复定义操作会显示应用内确认框；取消继续保留原内容，确认后重新检查会话和操作锁。详见[未保存流程修改的确认](docs/unsaved-design-confirmation.md)。

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

GitHub Actions 将执行作者检查、后端 `verify`、前端请求测试和构建；推送前的本地验证与远端 CI 状态分别记录。
