# 个人发票原件票夹

票夹在审批申请之外保存员工自己的发票原件。调用链为个人票夹页面/集成方 → `InvoiceWalletService` → `InvoiceOriginal` 与 `Invoice` → 原件仓储、财务资源仓储和 `LocalDocumentStore`。当前完成原件上传与读取 API、数据库约束及配套恢复；[持久验票任务](invoice-verification.md) 已单独实现，报销选票和页面继续实施。

## 文件身份与状态

登记只接受文件名、字节数、SHA-256 及 `PDF` / `OFD` / `PNG` / `JPEG` / `XML` 格式。租户与归属人取当前认证身份，服务端生成发票和原件 UUID；不能指定存储路径、下载 URL、票面金额或已查验状态。登记容量、财务资源、原件元数据与幂等结果在同一个事务提交，不需要先伪造审批申请。

`InvoiceOriginal` 的 `UPLOADING`、`READY`、`FAILED` 仅表示原件上传状态。`Invoice` 的 `PENDING`、`VERIFIED`、`FAILED` 表示独立的查验状态。内容传输完成不会将发票标记为已查验，也不会形成费用额度或发票占用。

原件身份、归属、名称、格式、大小及指纹在登记后不可修改。断流、错误摘要和格式不匹配保留同一身份以便重试；已经 READY 的原件不能被重试覆盖或被晚到失败降级。所有原件继续保留，当前不提供物理删除或用删除回收配额。

容器识别检查 PDF、PNG、JPEG 文件头，OFD 还检查 ZIP 中央目录存在非空 `OFD.xml`，不解压、执行或解析文档业务内容。这只能识别容器格式，不证明文档结构完整、发票真实或通过安全扫描；真实性必须由真实验票端口确认。

XML 使用有界流式结构检查，拒绝 DTD、禁用外部访问，保留声明编码、BOM、命名空间和签名节点的原始字节。深度、元素数、每元素属性（包含命名空间声明）分别最多 64、100,000、64；查验转发及归档不重编码。V57 扩充格式约束且保留原件及恢复清单，详见 [XML 原件](invoice-xml-originals.md)。

## 接口与权限

| 方法及路径 | 行为 |
| --- | --- |
| `GET /api/v1/invoices/options` | 当前存储是否可用、文件和个人累计配额、支持格式 |
| `POST /api/v1/invoices` | 幂等登记，返回发票 `id`；`Idempotency-Key` 必填 |
| `PUT /api/v1/invoices/{id}/content` | 原始二进制上传，使用登记身份与摘要恢复；不需要 JSON 幂等键 |
| `GET /api/v1/invoices/{id}` | 本人发票状态与原件元数据 |
| `GET /api/v1/invoices` | 本人有界分页；`limit` 1–100，默认 25；`beforeId` 接上一页 `nextBeforeId` |
| `GET /api/v1/invoices/{id}/content` | 本人下载已完整上传且指纹一致的原件 |

完整请求响应结构见 [OpenAPI](../agentflow-server/src/main/resources/api/openapi.json)。列表按发票 UUID 的固定顺序分页，不宣称按上传时间排序。不接受员工、租户或其他查询参数；不能通过任意身份筛选读取别人的票夹。

所有票夹详情、原件上传和下载都要求同租户且同本人，管理员没有他人个人票夹权限。审批人按已冻结报销轮次查看原件属于后续报销专用用例，不能借用个人票夹接口放开权限。下载使用 `application/octet-stream`、`Content-Disposition: attachment`、`nosniff` 和 `no-store`，不提供内联或公开静态 URL。

## 存储、代理和配额

物理内容与表单附件共用显式配置的 `AGENTFLOW_ATTACHMENT_DIRECTORY`。公共 `LocalDocumentStore` 只处理不可变字节，审批附件继续由 `LocalAttachmentStore` 保留旧接口错误码、申请配额与字段授权；票夹由自己的领域模型和用例决定归属。没有把个人原件绑定到假的 `Application`。

单原件上限是附件部署单份限制与 20 MiB 两者的较小值。应用按实际流式读到的长度限制，不信任 Content-Length；传输不持有数据库事务，也不复制整份文件到 JSON 幂等请求缓存。文件完成写入与指纹检查后才排他发布，已经发布的不同内容不能覆盖。

个人累计默认 1 GiB、1,000 次登记，可配置 `AGENTFLOW_INVOICE_MAX_WALLET_BYTES`、`AGENTFLOW_INVOICE_MAX_WALLET_UPLOADS`；容器通过 `compose.attachments.yml` 同步传入。累计计入失败和未完成登记，相同幂等请求回放不重复计入。最后一个配额由数据库原子更新争抢，失败请求不会残留新的发票资源。

Nginx 演示和生产配置为 `/api/v1/invoices/{id}/content` 单独设置 20 MiB 上限，关闭请求缓冲与上游自动重试；普通 JSON 保持 1 MiB。附件原有的 100 MiB 代理路径保持其部署协议。

## 数据库升级与配套恢复

V35 添加个人配额与独立原件表。复合外键同时匹配租户、发票类型、发票 ID、本人和原文件来源，不能把他人或另一份原件绑定到发票。升级保留旧财务资源、版本证据及审批附件，不给已有发票补造原件文件。

`stored_document_inventory` 统一列出审批附件和发票原件的 UUID、大小、摘要及状态。配套工具 `scripts/production_attachments.py` 在 V35 使用该视图，对旧恢复点继续使用附件表。清单不包含文件名、票面正文或账户信息；原有数据库摘要绑定、私有目录、禁止覆盖及恢复新库规则继续有效。

备份前先停止该数据库的全部写入和上传，再使用 [附件配套备份命令](field-attachments.md#数据库与文件配套恢复)。没有原始字节的未完成上传可以保留元数据；每个 READY 原件必须有匹配内容，缺失或损坏则不能生成完整回执。必须使用支持 V35 原件视图的恢复工具处理新备份。

## 本地验证

H2 票夹集成 8 项、原有附件/文件存储回归 12 项、升级与 OpenAPI 6 项通过；PostgreSQL 票夹及升级 9 项通过，数据库间用例范围有重叠。配套恢复工具 6 项通过，其中新增回归先证明旧清单遗漏发票，再验证两类原件一起备份恢复。

打包 jar、隔离 PostgreSQL 与真实 Nginx 完成 19 次 HTTP 验证，包括 2 MiB 原件、重传、原文摘要、当前查验仍为 PENDING、管理员隔离、JSON 代理限额和幂等回放。代理验收先失败于新路径落入 1 MiB 限制，补入原件专用路径后通过；仅 MockMvc 无法覆盖该部署边界。

此阶段没有票夹或报销页面验收，也没有真实税务查验；具体证据见 `evidence/invoice-wallet-20260928.json`。

另以真实 TLS PostgreSQL 客户端完成 1 份 2 MiB 发票原件的配套备份和独立新库/新目录恢复。4 类财务表逐行一致，恢复服务后 6 次 HTTP 检查确认原件摘要、READY/PENDING 状态和管理员隔离。恢复没有覆盖源库或源文件；未完成上传的元数据仍保留。生产 Nginx 配置通过语法校验，但上述业务 HTTP 验收使用本机演示代理，不代表企业生产验收。

## 页面接续

个人票夹页面已完成上传、恢复、授权下载和持久查验，并通过浏览器引用发票完成费用正式提交，详见 [票夹页面验收](invoice-wallet-ui.md)。
