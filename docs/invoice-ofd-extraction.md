# OFD 票面抽取与部署字体

票夹通过原抽取入口处理 OFD：服务端核对本人完整原件，在隔离 JVM 内绘制所有文档和页面，按原顺序向模型提供 PNG 和页码。`InvoiceExtractionInput` 仍绑定原件编号、完整 SHA256、格式、字节数和实际页数；模型只能返回这些页的候选字段，申请人逐项确认后另存复核结果。

`InvoiceExtractionInputOptions.transmission=RENDERED_PAGES` 表示发送全部页图。页面在提交前展示页数和目的地，并要求本次明确授权。内部 ZIP/XML/字体文件不会成为模型的附件。票面上的文字、图形、静态批注和可见签章会包含在页图中；签章外观不等于密码学验签或正式查验结论。

## 支持范围

沿用路径、文字、PNG/JPEG、复合图元、模板、静态批注和有界裁剪。颜色包括 Gray/RGB/CMYK、位深、调色板、受限 ICC 和透明度。文字按明确字体字形与 TextCode/CGTransform 坐标绘制，不用 OCR 或系统字体猜测缺字。

签章外观支持 SES v1/v4 中的 PNG/JPEG 或单页嵌套 OFD。正文、模板、签章、批注按顺序合成；印章保留透明背景。多页嵌套章缺少明确页选择语义，整份拒绝。没有可见外观的签名不会合成一个章。

支持 `http://www.ofdspec.org/2016` 和已有票面的 `http://www.ofdspec.org`，不同 XML 文件可各用一种；同一 XML 内混合绘制命名空间仍拒绝。批注缺省 Creator/LastModDate 和无命名空间的 Parameters/Parameter 仅作非绘制兼容；外观、图元不补命名空间。

完整处理并不表示支持 OFD 的所有可选扩展。渐变和底纹、TextObject 合成斜体或非默认 Weight、未知图元、缺失绘制资源、缺失首段文字坐标或非法间距、无法精确取得的字体、无法解释的签章等，均使整个原件失败；不能跳过后页、后章或未知内容后提交模型。字体资源的 Bold/Italic 声明可以选择配置中明确提供的相应字体面，不自动拉伸或描粗字形。

文字间距另采用 [OFDRW 2.4.0 DeltaTool 的既有兼容规则](https://raw.githubusercontent.com/ofdrw/ofdrw/2.4.0/ofdrw-reader/src/main/java/org/ofdrw/reader/DeltaTool.java)：DeltaX/DeltaY 已给出但长度不足时，余下字形延续最后一个明确间距；整个属性缺省仍为零。空序列、非法数值、超限重复与缺失首段 X/Y 坐标仍失败。这是参考实现的兼容行为，不声明为标准强制规则。

## 字体清单

部署方可设置 `AGENTFLOW_INVOICE_OFD_FONT_CATALOG`（对应 `agentflow.invoices.ofd-font-catalog`）为受信清单的绝对路径。空配置只接受包内字体。服务启动拒绝相对路径；处理原件时隔离进程校验清单、字体文件、face 和 SHA256。

清单结构如下，路径、face 和摘要必须换为部署方合法持有并实际核对的字体信息：

```json
{
  "fonts": [
    {
      "name": "票据所声明的字体名称",
      "bold": false,
      "italic": false,
      "file": "/srv/agentflow/fonts/invoice.ttf",
      "face": "实际的PostScript字体面名称",
      "sha256": "实际字体文件的64位小写SHA256"
    }
  ]
}
```

以文档声明的 name/bold/italic 精确匹配；不从票据提供的路径读取主机字体，也不搜索系统或网络后备字体。包内字体存在但损坏时失败，不能以部署字体掩盖损坏。字体及清单不进入代码仓库或安装包，部署变更应同时保留清单与字体摘要证据。

## 限制与失败行为

- 最多 10 页；144 DPI；单页最多 800 万像素，累计最多 4000 万像素；全部 PNG 累计最多 20 MiB。
- 隔离 JVM 最大堆 512 MiB，处理最长 30 秒；PDF 检查与 OFD 渲染共用一个进程槽。忙碌时明确返回 `INVOICE_EXTRACTION_SOURCE_BUSY`。
- 最多 1024 个 ZIP 条目，解压内容累计最多 64 MiB；字体最多 64 个、单字体最多 32 MiB、累计最多 64 MiB。嵌套章最多 4 层，与正文共享累计资源限制。
- 超限、损坏、缺字或不支持内容返回 `INVOICE_EXTRACTION_SOURCE_UNAVAILABLE`，不产生部分模型输入。完成、失败和超时后清理本次私有临时目录，并停止子进程。

持久层仍只保存原件引用和抽取运行状态。重放已成功的原幂等请求不重新读取或外发原件；执行时再次核对原件身份、实际页数、处理方式和模型目的地。人工确认不改变正式票面、查验事实、发票占用或财务金额。
