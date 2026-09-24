# 运营统计查询性能验证

2026-09-24，在阶段 57 的参与者检索修复之后，减少运营统计对当前有效任务集合的重复读取。改动限于 `JdbcApprovalOperationsReadAdapter`，不改 API、领域模型、审批写入、租户权限或数据库结构。

## 问题与职责

`ApprovalOperationsController` 校验管理员权限和筛选参数，通过 `ApprovalOperationsReadPort` 调用 JDBC 读适配器。原实现分别查询待办总数、节点分组和最老任务；三条 SQL 重复执行同一申请、租户、当前轮次、激活状态及根流程变量联查。数据量增加后，节点分组已经读取过的任务又被单独计数一次。

现在在节点分组查询中使用 `SUM(COUNT(*)) OVER ()`，得到全部分组的任务总数，再执行排序与 `LIMIT 21`。窗口计算发生在截断前，因此不能把展示的 20 行或预读的 21 行相加当作总数；无分组时总数为零。最老任务查询继续单独有界读取。当前任务集合由三次联查减少为两次，仍处于原有只读、可重复读事务中。

结果映射使用基础设施内部 `WaitingNodeRow`，只携带节点投影和完整总数。数据库聚合属于读适配器职责；领域状态与引擎写路径不承担 SQL 性能逻辑，也未新增任务事实副本或缓存。

## 先复现，再修改

原有测试验证了会签成员计数、非法租户/轮次、时间窗口、历史缺口和返回结果，没有约束数据库重复读取次数，也没有覆盖超过 20 个等待节点分组。

新增回归用例用真实 Flowable 实例生成 22 个分组、24 个任务，最后一个不可见分组有 3 个任务。测试同时验证完整总数、节点/任务截断标志、节点顺序、精确版本筛选和空结果，并在真实 JDBC 连接上记录实际准备的 SQL，不模拟查询结果或使用毫秒级阈值。修改前明确失败：当前任务集合读取次数预期 2，实际 3；修改后通过。

6 项运营集成用例分别在 H2、PostgreSQL 17.11 通过；完整 Java 门禁 613 项（领域 178、服务端 435），前端 238 项、工具 19 项、构建通过。251 个 Java 文件的 416 个命名类型全部带作者信息。

如需复验 PostgreSQL，先准备独立测试库，再执行：

```bash
AGENTFLOW_OPERATIONS_TEST_URL=jdbc:postgresql://localhost:5554/agentflow \
AGENTFLOW_OPERATIONS_TEST_USER=agentflow \
AGENTFLOW_OPERATIONS_TEST_PASSWORD=demo \
AGENTFLOW_OPERATIONS_TEST_DRIVER=org.postgresql.Driver \
mvn -B -ntp -pl agentflow-server -am \
  -Dtest=ApprovalOperationsIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试写入合成申请与流程数据，必须使用专用数据库。回归日志分别在 `/fyoung/tmp/agentflow-operations-query-{red,green,verify}.log`。

## 独立 SQL 实验

使用阶段 57 保留的 5000 份申请样本库，3750 个待办、30 个非空节点分组，数据库限制 1 CPU / 768 MiB。按生产查询片段组装并保留 SQL，比较原计数、原分组及带完整总数的新分组，分别执行三次 `EXPLAIN ANALYZE`。

| SQL | 三次执行时间 ms |
|---|---|
| 原独立计数 | 50.564 / 40.925 / 40.964 |
| 原节点分组 | 46.938 / 46.055 / 45.262 |
| 新节点分组及完整总数 | 44.479 / 45.575 / 43.045 |

新旧节点的前 21 行逐项一致，新查询每行的完整总数均为 3750。新节点查询没有明显增加耗时，并省去独立计数查询。此处只验证 SQL，接口在并发负载下的表现需要另外实测。

[SQL、各类首份完整执行计划及回归摘要](evidence/operations-query-plan-20260924.json)；原始实验 `/fyoung/tmp/agentflow-operations-query-experiment.json`。

## 同参数 HTTP 容量对比

对照阶段 57，使用同一容量工具，分别新建 1000 / 5000 份申请；40 个流程、四分之一批准、7 个读端点、每端点预热 5 次、每档每端点实测 100 次，读并发 1/4/8。后端 2 CPU / 1536 MiB / Java 堆 1024 MiB / Hikari 10，数据库 1 CPU / 768 MiB；Docker Desktop 29.6.2 arm64，PostgreSQL 17.11、Java 17.0.20。正式测量没有并行编译、测试或另一个容量任务。

每次新建独立样本库，业务参数和状态分布一致，ID、时间和物理布局不同。每请求建立新连接，计时包含连接与 JSON 解码，宿主机不是独占环境。

| 申请数 | 并发 | 前轮混合请求/秒 | 本轮混合请求/秒 | 前轮运营 p95 ms | 本轮运营 p95 ms |
|---|---:|---:|---:|---:|---:|
| 1000 | 1 | 78.081 | 71.726 | 40.916 | 38.704 |
| 1000 | 4 | 101.393 | 110.840 | 171.397 | 108.480 |
| 1000 | 8 | 82.184 | 93.754 | 390.746 | 290.933 |
| 5000 | 1 | 18.788 | 18.436 | 212.621 | 209.812 |
| 5000 | 4 | 16.300 | 19.765 | 902.942 | 685.686 |
| 5000 | 8 | 13.553 | 18.275 | 2688.537 | 1715.188 |

5000 份申请、8 并发的运营 p95 降低约 36.2%，p99 从 2712.102 ms 降至 1979.673 ms，混合吞吐提高约 34.8%。单并发混合吞吐本轮略低，待办等个别分位延迟也有上升；完整数据保留在证据中，不能把运营接口的改善外推为所有查询、所有并发档均改善。

本轮正式读请求共 4200 次、零失败。每次运营测量均核对提交轮次和完整待办总数；结束后逐页核对申请 ID/状态、待办集合、申请人分页、无关账号空结果、历史缺口、流程数和通知数。两组核对均通过。

[所有端点、并发档、资源及原始报告摘要](evidence/operations-capacity-comparison-20260924.json)；原始报告 `/fyoung/tmp/agentflow-operations-capacity-{1000,5000}-20260924/`。这些结果仍不是生产容量或 SLO 验收，当前任务联查本身仍有优化空间。

## 本机部署与数据核对

8080 本机后端及 8180 演示入口的 Docker 后端已更新，前端制品仍为阶段 55，数据库保持 V20，没有数据库迁移。H2 旧进程退出后等待文件句柄释放，再冷备并校验内容一致；原有同名历史备份未覆盖，本阶段备份使用 `/fyoung/tmp/agentflow-operations58-main-before.mv.db`。旧 Docker 后端保留为 `agentflow-before-operations-58-server`，数据库容器和卷保持原实例。

H2 原 15 个流程、21 份申请、23 个轮次及轨迹/审计，PostgreSQL 全部业务表（含 7 个流程、11 份申请和 11 个轮次）逐项一致。两端共 20 组运营报表覆盖默认、空范围与精确流程版本，除生成时间与等待秒数外逐项一致；等待秒数先以原始纳秒时间核对计算，再从对照中去除时间变化。两个 readiness 均为 UP。

后端制品 `/fyoung/tmp/agentflow-operations-release-20260924.jar`，SHA-256 `4a63db331f2acc258692dee07acb5bcb8ec623757db78f0abb8d3e4a41f45030`；Docker 镜像 `sha256:8f03bbcd7e5da8b75269b1f65277233affd1e5623d10d965ceb961c51eb7bdc9`。运行记录 `/fyoung/tmp/agentflow-operations-query-runtime.json`，数据核对 `/fyoung/tmp/agentflow-operations58-main-verification.json`。压测、SQL 实验与专用测试容器均已停止，数据保留。
