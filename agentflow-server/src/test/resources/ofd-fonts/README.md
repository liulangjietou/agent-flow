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
| cid-subset.otf | GID 与 CID 不相同；默认矩阵；每个字形宽度不同 |
| cid-matrices.otf | 顶层矩阵与两个 FD 矩阵；分别包含缩放和平移；FDSelect 格式 3 |
| cid-fd-only.otf | 无顶层矩阵；两个 FD 自身提供 em 变换，其中一个包含斜切 |
| cid-top-only.otf | 只有顶层矩阵，两个 FD 均省略矩阵 |
| cid-range1.otf / cid-range2.otf | 连续 CID，分别触发 charset 的格式 1／2；后者含 261 个字形 |
| cid-singular-matrix.otf / cid-missing-private.otf | 第二个 FD 的奇异矩阵或缺少 Private 字典 |
| cid-invalid-fd.otf / cid-invalid-range-start.otf / cid-invalid-range-sentinel.otf | 非法 FD 索引、范围非零起点及错误终点 |
| cid-duplicate-cid.otf / cid-inconsistent-count.otf | 重复 CID 或 maxp／CharStrings 字形数量不一致 |
| cid-range-overflow.otf / cid-range-cid-overflow.otf | charset 范围超过字形数量或 16 位 CID 范围 |

生成的 `.notdef` 特意包含可见矩形，用于确保缺字会失败，而不是误把占位图形当作真实文字。

CID 字体的 `.notdef`、A、中、U+20000 分别宽 900、300、600、800 单位，空格为空轮廓。负向原件只损坏指定的映射字段，故意保留 FontBox 可读取的结构；其中直接改字节的文件不重算 sfnt 校验和。它们只用于错误处理测试，不作为合法字体样例。全部 21 份字体可以确定性重新生成。
