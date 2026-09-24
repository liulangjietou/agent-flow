# 待修复：未提交草稿被计入缺失历史轮次

阶段 51 的真实浏览器验证发现：保存一份从未提交的申请草稿后，首次使用引导显示“有 1 个历史轮次缺少快照”。运营统计的同类提示也包含这些未提交草稿。该问题位于既有服务端读模型，尚未在阶段 51 修复。

最小复现：发布带表单的流程，创建申请并只保存为草稿，然后以 ADMIN 请求该版本的 `/api/v1/system/first-workflow?definitionId=...`。草稿没有提交轮次，但返回 `unrecordedHistoricalRounds=1`。期望未提交草稿不计作缺失提交历史；真正旧审批缺失轮次仍须保留提示。

证据保存在 `/fyoung/tmp/agentflow-selection-known-draft-count.json`，包括读报告和对应的 DRAFT 申请；隔离 H2 数据保留。阶段 51 的 PostgreSQL 普通账号浏览器案例也留下草稿，可重复核对。

调用链为 `FirstWorkflow / ApprovalOperations → 原查询 Controller → JdbcFirstWorkflowReadAdapter / JdbcApprovalOperationsReadAdapter`。两个适配器使用 `round_no - 已有轮次数量` 推算缺口，而新申请的 `round_no=1` 已预留首轮编号，尚不表示已提交。现有引导专门测试验证了手工插入 APPROVED 且没有轮次的旧数据，没有对正常未提交草稿断言这一指标。

下一步先补两个读模型的失败回归，再核对 DRAFT、未提交即作废、退回补正和真实旧历史缺口，按实际状态与提交事实修正查询。不能用草稿预留编号补造已提交记录。
