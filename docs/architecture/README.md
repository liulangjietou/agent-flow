# 架构图与流程图维护说明

本目录记录 AgentFlow 图文的源码依据。源码核对基线为 `fec4a87c6c785ddabf40ad2584060e264abddd2f`，日期为 **2026-10-03**。图中表达这一版本已经存在的实现、显式配置能力和已知缺口；不以目录或接口存在代替企业上线验收。

## 交付文件

| 文件 | 用途 | 画布 / 导出尺寸 |
| --- | --- | --- |
| [全景 PNG](../assets/agentflow-panorama.png) | README、汇报及分享，白底五栏与贯穿治理条 | 4,608 × 3,540 像素 |
| [全景 SVG](../assets/agentflow-panorama.svg) | 缩放阅读和修改文字、布局及连线 | 3,072 × 2,360，矢量 |
| [业务流程 PNG](../assets/agentflow-business-flow.png) | 专用报销的完整路径、恢复分支及 Agent 侧路 | 4,608 × 4,650 像素 |
| [业务流程 SVG](../assets/agentflow-business-flow.svg) | 六个业务阶段、事务边界和事实分离 | 3,072 × 3,100，矢量 |
| [source-map.json](source-map.json) | 25 个栏目卡片、68 份源码/文档路径及 SHA-256 | 内容及证据索引 |
| [绘图脚本](../../scripts/render_architecture_diagrams.py) | 使用 Python 标准库生成两份 SVG，统一文字转义和排版 | 无业务依赖、无网络 |
| [项目 README](../../README.md) | 图文入口、7 份 Mermaid、详细链路和部署说明 | 可直接在 GitHub 阅读 |

架构图沿用参考图的密集五栏结构、蓝青紫绿橙配色和横向治理条，所有模块及业务内容来自 AgentFlow。正式图由确定性脚本生成 SVG，再渲染为 PNG；风格生成稿仅用于布局参考。正式图没有把参考项目的框架、存储、通道或平台品牌移入本项目。

SVG 使用文本节点，源文件可编辑；中文字体按 `PingFang SC / Heiti SC / Microsoft YaHei / Noto Sans CJK SC` 回退。PNG 固定像素内容，不要求阅读设备安装字体。修改或跨系统重新导出时，需要在渲染设备安装上述中文字体之一并检查替换后的宽度。

## 如何阅读

全景图从用户入口、可信接入、领域与引擎、Agent 与财务执行，到运营集成。各栏表示职责及协作，不表示五个独立服务，也不要求所有业务串行经过全部卡片。蓝色为调用和协作，绿色为真实结果反馈；虚线表示整个卡片中的能力需要显式配置。强制认证、站内通知、审计卡片使用实线，卡片内另写出可选 OIDC、共享会话、外发通知和指标。

业务流程图以**专用报销**说明从设计发布、填报预检、人工审批、会计与付款，到核销归档的链路。主图中的纸件签收、会签、财务审核和复核按发布定义及法人配置决定。事前申请、借款、采购和预算调整复用基础设施，但不能直接套用报销全部状态与来源。

阅读财务路径时分别确认批准、预算确认、ERP 过账、真实资金成功、核销及归档。橙色分支表达补正、失败、争议、原操作查询及人工恢复；零应付有独立业务依据，不制造零额银行付款。Agent 位于独立侧路：输入选择、模型调用、结果校验、人工采纳与最终审批分别授权。

## 内容与源码的对应关系

`source-map.json` 的 `pillars[].cards[]` 保存标题、四行说明、可选标志及来源路径；`sourceSha256` 保存本次核对时的源文件摘要。它们是审阅依据，不是运行配置。`supportedNodes`、`notPublishedNode` 和 `templateKeys` 明确区分已开放节点、被发布校验拒绝的节点和实际内置目录。

下列事实在更新图文时优先复核：

| 事实 | 主要入口及依据 |
| --- | --- |
| 编译与运行依赖 | [domain pom](../../agentflow-domain/pom.xml)、[common pom](../../agentflow-common/pom.xml)、[server pom](../../agentflow-server/pom.xml)；领域使用 Spring 集合工具，不引入 Flowable 运行类型 |
| 模板与发布节点 | [模板目录](../../agentflow-server/src/main/java/io/agentflow/template/ClasspathProcessTemplateCatalog.java)、[发布校验](../../agentflow-domain/src/main/java/io/agentflow/definition/DefinitionValidator.java)；当前 5 个模板、9 类开放节点 |
| 申请状态及实例控制 | [Application](../../agentflow-domain/src/main/java/io/agentflow/approval/model/Application.java)、[InstanceControlService](../../agentflow-server/src/main/java/io/agentflow/approval/process/InstanceControlService.java)；管理员终止当前在审根轮次进入 `CANCELLED`，不能覆盖既有批准 |
| 任职与字段权限 | [组织选人](../../agentflow-server/src/main/java/io/agentflow/organization/OrganizationAssigneeResolver.java)、[字段视图](../../agentflow-server/src/main/java/io/agentflow/approval/ApplicationFieldViews.java)；固定提交轮次，敏感字段约束适用于管理员 |
| 财务提交及原外部操作 | [报销提交](../../agentflow-server/src/main/java/io/agentflow/expense/ExpenseSubmissionService.java)、[预算执行](../../agentflow-server/src/main/java/io/agentflow/finance/BudgetOperationWorker.java)、[付款执行](../../agentflow-server/src/main/java/io/agentflow/finance/PaymentOperationWorker.java)；远端 HTTP 在本地事务外 |
| 凭证、核销与归档 | [凭证准备](../../agentflow-server/src/main/java/io/agentflow/finance/VoucherPreparationWorker.java)、[核销说明](../expense-settlement.md)、[归档说明](../expense-archives.md)；真实事实分别成立，已消费资源不能靠重试恢复 |
| 模型与人工复核 | [AssistExecutionService](../../agentflow-server/src/main/java/io/agentflow/agent/AssistExecutionService.java)、[AssistWorker](../../agentflow-server/src/main/java/io/agentflow/agent/AssistWorker.java)、[受控模型客户端](../../agentflow-server/src/main/java/io/agentflow/agent/OpenAiTextClient.java) |
| 默认配置与部署边界 | [配置文件](../../agentflow-server/src/main/resources/application.yml)、[生产部署](../production-container-deployment.md)、[多实例](../multi-instance-deployment.md)、[未完成台账](../remaining-task-ledger.md) |

不要只刷新摘要就推定旧说明仍成立。修改来源后，先读调用方、下游和对应测试，确认行为，再更新文字、基线日期和摘要。源码不变时保留原摘要，便于判断图文依据是否漂移。

## 再生成与导出

在仓库根目录运行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/render_architecture_diagrams.py
```

脚本生成 `docs/assets` 中两份 SVG。修改全景卡片内容时编辑 `source-map.json`；修改业务流程的布局或文字时编辑脚本中的 `business_flow`；同时更新项目 README 的相关 Mermaid 和正文。超出卡片可用高度会直接失败，避免静默截字。

本次 PNG 使用 Sharp 以 108 DPI 导出：SVG 默认 72 DPI，得到 1.5 倍像素尺寸。在已经提供 `sharp` 的 Node 工具环境中，可在仓库根目录执行以下命令；这只是文档导出工具，不加入业务 `package.json`：

```javascript
const path = require('node:path');
const sharp = require('sharp');

(async () => {
  for (const stem of ['agentflow-panorama', 'agentflow-business-flow']) {
    await sharp(path.join('docs/assets', stem + '.svg'), { density: 108 })
      .png({ compressionLevel: 9 })
      .toFile(path.join('docs/assets', stem + '.png'));
  }
})().catch(error => {
  process.stderr.write(error.message + '\n');
  process.exitCode = 1;
});
```

将此段保存为独立工具脚本后，用提供 Sharp 的 Node 环境运行。也可用已有 SVG 导出工具按上表尺寸导出。不要把未重新渲染的旧 PNG 与新 SVG 一起提交。

## 更新检查

1. 核对来源文件、关键枚举、模板目录及默认配置。只描述当前可观察实现；未完成能力仍指向台账。
2. 同步图稿、README Mermaid 与正文，并检查本地 Markdown 路径和章节锚点。
3. 执行绘图脚本并解析 SVG/XML；按规定尺寸重新导出 PNG。
4. 打开两张完整 PNG，按原尺寸检查中文、换行、连线方向和恢复分支；确认 PNG 和 SVG 文字一致。
5. 复核 `sourceSha256` 与冻结基线；依据变化时重新审阅业务说明，不仅替换哈希。
6. 执行对应文档校验及仓库要求的门禁。此次交付已执行 Java 作者校验、全量 Maven verify、前端请求/契约测试和生产构建。

业务代码并未因图稿改变。两张图与 README 是源码结构说明；企业身份、模型、预算、ERP、银行、通知、容量及最终部署仍按目标环境验收。
