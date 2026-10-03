# XML 发票原件

个人票夹支持 XML 原件登记、流式上传、同原件恢复、本人下载及查验转发。XML 只新增文件格式，不从票面字段生成金额、法人或查验结论；内容中自写 `VERIFIED` 也仍为待查验。

## 调用链与职责

页面 `InvoiceUploader` 根据后端格式白名单登记 `XML`，原件上传进入 `InvoiceWalletService`，先由 `LocalDocumentStore` 核对实际长度和 SHA-256，再由 `InvoiceOriginalFiles` 检查 XML 结构，最后排他发布原字节。格式检查属于原件存储入口，领域原件维护不可变身份与上传状态，`Invoice` 单独维护可信查验事实。

`InvoiceVerificationWorker` 在事务外将原存字节作为 `application/xml` 转交配置的查验端口。查验仍绑定本人、法人、原件和摘要，原有租约、版本、防重票及结果权限继续生效。报销冻结原件引用，核销后归档读取同一字节并核对指纹。

## 结构与内容边界

- 使用 JDK 流式 SAX 读取文件，不构建 DOM、不重新编码或序列化原件。UTF-8、UTF-8 BOM、UTF-16、命名空间、注释、CDATA 和签名节点保留原字节。
- 禁止 DTD，禁用外部实体、外部 DTD、Schema 加载及 XInclude 展开。样式表处理指令、Schema 地址和 XInclude 节点只作为原文保留，不执行或访问引用。签名真实性由企业查验服务验证。
- 限制最大深度 64、元素总数 100,000、每元素属性 64；命名空间声明计入属性数。文件仍受部署上限与 20 MiB 两者较小值约束。
- 非法结构、DOCTYPE 或超限返回 `INVOICE_ORIGINAL_FORMAT_MISMATCH`，清理本次暂存且保留原登记。错误不携带解析原文或本地路径，既有 READY 文件不能被失败重试覆盖。
- 不限定业务根节点或企业 Schema，不把结构合格当成有效发票。真实性、票种、票号、买方和票面金额只采用查验服务返回且通过契约校验的事实。

## 下载、迁移和兼容

下载仍要求同租户本人，管理员不能读取他人票夹；返回 `application/octet-stream`、`Content-Disposition: attachment`、`nosniff`、`no-store`。不提供内联 XML 渲染和远程文件 URL。

V57 只将 `invoice_original.format` 检查扩充为 `PDF/OFD/PNG/JPEG/XML`。按信息模式读取旧内联检查名，兼容 H2 和 PostgreSQL；保留非空、体积、状态和归属外键。旧原件、财务资源修订、配额和 `stored_document_inventory` 不重写。发布使用原数据库和原件目录的配套恢复点。

接口路径和请求字段保持现有票夹契约；OpenAPI 的登记、原件元数据、可用格式三处白名单同步扩充。已有 PDF、OFD 和图片继续使用各自的格式识别。

## 本地验收

结构测试覆盖合法边界和越界、DOCTYPE/实体扩张拒绝、HTTP 与本地文件引用不读取、编码及签名原文不变。集成测试覆盖上传失败状态、本人及管理员隔离、实际 HTTP 查验字节与类型、采用网关金额、报销核销和 ZIP 原文一致性。

阶段证据及实际环境结果见 [机器可读验收记录](evidence/invoice-xml-originals-20260929.json)。企业 XML 格式兼容性和查验服务联调按真实接入单独验收。
