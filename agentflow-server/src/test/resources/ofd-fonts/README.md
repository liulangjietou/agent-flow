# OFD 字体合成样本

这些字体由 `scripts/generate-ofd-font-fixtures.py` 自造矩形轮廓生成，不含系统或第三方字体数据。生成器使用 fonttools 4.60.2；日常 Java 测试只读取已保存的样本，不需要 Python 或字体工具。

| 文件 | 验证内容 |
| --- | --- |
| a.ttf | 每 em 1000 单位，字形宽 600；含空格、A、中及 U+20000 |
| b.ttf | 每 em 2000 单位，字形宽 300，用于检查缩放 |
| two-faces.ttc | A、B 两个字体面，须按 PostScript 名称选择 |
| ambiguous-faces.ttc | 两个同名字体面，不能随意选择其中之一 |
| outline.otf | 每 em 1000 单位、宽 550 的 CFF 轮廓 |
| legacy-cmap.ttf | 仅有 Mac Roman 字符映射，不能冒充 Unicode |

生成的 `.notdef` 特意包含可见矩形，用于确保缺字会失败，而不是误把占位图形当作真实文字。
