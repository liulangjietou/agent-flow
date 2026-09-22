# AgentFlow

AgentFlow 是面向 OA、财务和表单审批的 DDD 工作流平台骨架。领域层只表达审批与流程定义规则，Flowable 作为基础设施防腐层运行 BPMN，Web 层提供租户隔离后的 REST API，Vue 设计器负责流程图编辑。

## 本地启动

需要 Java 17+、Maven 3.9+ 和 Node.js 20+。默认使用 H2 文件库，首次启动会由 Flyway 创建业务表，Flowable 自动创建引擎表并部署 `expense-reimbursement` 示例流程。

```bash
cd /Volumes/fyoung/code/AI/flow/agentflow
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl agentflow-server -am spring-boot:run
cd agentflow-web
npm install
npm run dev
```

演示认证默认开启：租户 `demo`，用户 `admin` / `finance` / `manager` / `employee` / `alice` / `bob`，密码统一为 `demo`。生产环境应设置 `AGENTFLOW_DEMO_AUTH=false` 并接入企业 OIDC。

## 模块边界

- `agentflow-common`：认证主体、统一 JSON 和稳定错误码。
- `agentflow-domain`：流程定义、受限条件 AST、流程模拟、审批申请聚合及仓储/运行时端口。
- `agentflow-server`：Spring Boot、Flowable/Flyway/JDBC 适配器、认证过滤器和 REST API。
- `agentflow-web`：Vue 3 + TypeScript 的任务中心、申请表单和流程设计器。

流程设计器只接受 `START`、`END`、`USER_TASK` 和 `EXCLUSIVE_GATEWAY` 节点；条件使用白名单语法（例如 `amount >= 1000 AND department == 'finance'`），不会执行用户输入的 JUEL、脚本或 Java 代码。

## 验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl agentflow-server -am test
cd agentflow-web && npm run build
```
