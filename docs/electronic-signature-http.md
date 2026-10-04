# 电子签 HTTP 端口协议

这是 I02 本地防腐端口的服务方契约。当前端口可以接收已持久领取的操作，传输真实字节并返回已验真回执；申请权限编排、后台工作器和[公开接口及验真回调](electronic-signature-api.md)已接通，页面及固定安装包运行验收继续。真实电子签供应商通过适配此契约接入，企业身份、文件内部签名、证书链及时间戳验收仍归 E10。

## 部署配置与调用边界

`agentflow.signatures.gateway` 默认关闭。开启时，部署者按租户声明精确的资料键、版本、操作人、实际签署身份、Ed25519 回执公钥、基础地址、令牌和超时。详见 [可信配置与回执证据](electronic-signatures.md#可信配置与原始回执证据)。令牌只来自配置，不写入请求快照、日志或证据。

`SignatureGateway` 提供 `submit`、`query`、`collect` 三个业务方法，分别只接受 `SENDING`、`QUERYING`、`FETCHING_FILES` 领取。调用方必须先完成申请、字段和签署资料的授权检查，在短事务内领取操作，再于事务外调用端口。HTTP 和物理文件操作检测到活动事务会直接拒绝执行。

所有网络请求使用配置中的同一个基础地址，只附加固定相对路径；不跟随 HTTP 重定向。资料、实际签署身份、公钥或目标变化后，不会切换到新的配置继续原操作。停用资料阻止首次发送，但保留原配置的查询和下载；整个功能关闭则停止网络调用。

端口使用 [JDK 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) 的 HTTP/1.1、异步接收和外层有界等待。时限覆盖响应头后的全部正文，取配置超时与当前租约剩余时间的较小值；首次发送还受授权剩余时间约束。超时、连接中断和无法验证的响应都不能推导远端未签署，后续只能查询原操作号。

## 首次提交

`POST <基础地址>/submit` 使用 `multipart/form-data`。`Authorization` 为配置的 Bearer 令牌；`Idempotency-Key` 固定为原操作 UUID。第一部分名为 `request`，UTF-8 JSON 字段如下：

| 字段 | 类型及内容 |
| --- | --- |
| `protocol` | 字符串，固定为 `agentflow-signature-http-1` |
| `requestDigest` | 原请求的 SHA-256 摘要 |
| `targetDigest` | 固定目标的 SHA-256 摘要 |
| `request` | 完整 `SignatureRequest` 对象，包含申请及流程版本、明确授权、冻结文件元数据和实际签署身份 |

上述 JSON 最多 64 KiB。后续每个文件部分名为 `file-<attachmentId>`，传输文件名固定为 `<attachmentId>.bin`，类型为 `application/octet-stream`；真实原文件名只存在于 JSON 元数据，不能注入 multipart 头。

发送前读取并核验全部原件的物理标识、大小和 SHA-256。任何一份缺失、损坏或链接异常均阻止整个提交。最多 10 份原件，单份 16 MiB、合计 32 MiB；multipart 使用 [BodyPublishers.concat](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpRequest.BodyPublishers.html#concat(java.net.http.HttpRequest.BodyPublisher...)) 拼接已验证的二进制快照，不扩张为 Base64 文件正文。外层发布器仅允许一次订阅，阻止 HTTP 客户端重新发送同一提交正文。

服务方仍须持久保存原操作号及请求摘要，校验全部元数据和真实文件指纹后再产生签署副作用。同号同摘要只能对应原操作；同号不同摘要必须拒绝。单次发送后丢回包时，客户端使用原号查询，不生成新的授权、原件集合或签署号。

## 原号查询

`POST <基础地址>/query` 使用 UTF-8 JSON，包含：

```json
{
  "protocol": "agentflow-signature-http-1",
  "tenantId": "原租户",
  "operationId": "原操作 UUID",
  "requestDigest": "原请求摘要",
  "targetDigest": "固定目标摘要",
  "profileKey": "原资料键",
  "profileVersion": 1,
  "profileDigest": "原资料摘要"
}
```

查询不含原件字节、文件名、授权目的和实际签署身份，也不携带提交幂等头。HTTP 404 视为传输失败；业务 `NOT_FOUND` 必须通过正常的已签名回执返回，即使多次出现也不授权重发原件。

## 回执响应

提交和查询都只接受 HTTP 200、单一 `Content-Type: application/json`（可声明 UTF-8）以及单一 `X-Agentflow-Receipt-Signature`。不接受 `Content-Encoding`。完整正文最多 64 KiB，不能只依赖响应的 `Content-Length` 控制接收量。

原文及签名遵循 [回执证据格式](electronic-signatures.md#可信配置与原始回执证据)。同一服务方修订必须保持事实和记录时间稳定；事实变化需要提升修订号。正向响应返回完整 `SignatureReceiptVerifier.Verified`，包括可重验的原始证据，调用方必须将状态更新和证据追加放在同一事务。`SIGNED` 回执只进入收集状态，全部结果字节保存并登记 READY 后才完成本地签署。

## 结果文件

`POST <基础地址>/artifact` 仍访问固定端点，其 JSON 包含 `operation`（上述查询身份对象）、`revision`、`receiptDigest`、`documentId`、`size`、`sha256`。这些值均来自原操作和已验真回执，不接受回执指定下载 URL。

响应必须为 HTTP 200、单一且与回执一致的媒体类型，不允许 `Content-Encoding`。正文接收上限为该结果登记的准确大小；随后还要检查实际长度及 SHA-256。结果单份最多 32 MiB、合计 64 MiB，并受共享文档存储 `agentflow.attachments.max-file-bytes` 的实际配置约束。

结果保存在原操作已经预留的独立内容 UUID 下。存储先暂存、核验和同步，再排他发布；短文件、多余字节和指纹不符均拒绝发布，发布前再次核对领取时限。若发布后尚未登记 READY 就退出，恢复时可验证并直接复用该文件；已存在的损坏文件不能覆盖，也不能写回原件标识。

已有结果的本地重验使用保留的原始签名证据，不需要当前部署配置或远端可用。缺失结果才访问固定端点。调用方仍需按有效领取逐份确认 READY；保存一个文件不会自动确认其他文件或整个签署状态。

## 验证边界

自动化范围使用真实回环 HTTP、真实二进制文件、JDK 回执签名和 H2 数据库。合成文件只验证协议、字节身份和恢复流程。安装包运行中强退、实际供应商、浏览器及 PostgreSQL 验收需要各自的独立证据。
