# AgentFlow

AgentFlow 是面向 OA、财务和表单审批的 DDD 工作流平台骨架。领域层只表达审批与流程定义规则，Flowable 作为基础设施防腐层运行 BPMN，Web 层提供租户隔离后的 REST API，Vue 设计器负责流程图编辑。

当前已贯通流程草稿编辑、校验、版本发布、申请提交和人工审批。初始化向导、完整组织与表单能力、财务领域、Agent 协作和生产认证仍在开发范围内；当前版本用于本地开发验收。详细进度与验收证据见相邻文档目录中的 [开发进度与验收记录](../doc/06-开发进度与验收记录.md)。

## 本地启动

需要 Java 17+、Maven 3.9+ 和 Node.js 20+。默认使用 H2 文件库，首次启动会由 Flyway 创建业务表，Flowable 自动创建引擎表并部署 `expense-reimbursement` 示例流程。

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

## 验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/java" scripts/CheckAuthors.java .
mvn -B -ntp verify
cd agentflow-web && npm run build
```

全部 Java 命名类型（包括接口、枚举、record、内部类型和测试类）必须在所属 Javadoc 中包含 `@author owlzhangfq@gmail.com`。检查器解析源码语法树，缺失作者或语法错误均返回非零退出码。

本地前后端启动后，执行 `python3 scripts/check-web-proxy.py`，检查带浏览器 Origin 的演示登录成功，以及未允许来源被拒绝。省略 Origin 的 curl 请求无法覆盖这类代理问题。

GitHub Actions 将执行作者检查、后端 `verify` 和前端构建；推送前的本地验证与远端 CI 状态分别记录。
