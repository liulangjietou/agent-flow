# H2 关闭重开完整性

H2 用于本地演示；容器演示和后续生产部署使用 PostgreSQL。工程显式固定 H2 2.5.250，避免 Spring Boot 3.5.6 默认管理的 2.3.232 在文件关闭压缩后出现旧数据回退。

## 发现与根因

2026-09-23 在首次流程引导升级核对中发现：停服前 15 个定义、21 个申请、23 个轮次，重启后变成 4、3、1。没有业务删除请求，Flyway 也错误地重新从 v3 迁移。正常停服后得到的文件不等同于已验证可恢复的备份。

使用更早且业务快照完全相同的停服备份副本，仅运行 JDBC 查询并关闭连接即可稳定复现，排除了新增引导查询、Flowable 审批操作和 UI。现有测试主要使用内存库、单次运行或新库，未覆盖累积版本数据的文件压缩及多次重开。

[H2 #4247](https://github.com/h2database/h2database/issues/4247) 记录了压缩释放区块后未持久化最终布局、文件缩减导致区块缺失并恢复旧版本的问题；[修复 #4249](https://github.com/h2database/h2database/pull/4249) 调整关闭过程的最终提交顺序，[2.4.240 发布说明](https://github.com/h2database/h2database/releases/tag/version-2.4.240) 包含该修复。本地相同副本在 2.3.232 第一次重开失败，在 2.4.240 连续三次重开后全部 54 张 PUBLIC 表内容摘要一致，与该修复对应。

2.4.240 虽通过文件重开测试，但工程现有 `FormSchemaMigrationTest` 复现了跨连接 CHECK 约束引用已关闭会话的回归，不能交付该版本，也不能删约束或削弱测试。[H2 #4302](https://github.com/h2database/h2database/issues/4302) 与 [2.5.250 发布说明](https://github.com/h2database/h2database/releases/tag/version-2.5.250) 对应修复了该问题。最终选择 2.5.250，保留原测试、迁移及约束，同时对同一旧库执行三次重开摘要比较。

修复归属依赖与持久化运行保障，不修改领域状态或用业务迁移补造数据。原故障文件、停服备份和每次验收副本均保留。恢复到新路径，避免覆盖原文件；恢复后对比原业务快照及完整表摘要，再开放入口。

同时开启 `maven.jar.forceCreation`：依赖版本变化但类文件不变时，增量打包曾复用旧可执行 jar，导致测试使用 2.5.250，而 jar 内仍为 2.3.232。重新生成普通 jar 后再执行 repackage，验收必须检查 `BOOT-INF/lib/h2-2.5.250.jar` 并核对实际启动日志。Docker 从不包含 target 的源码上下文构建，亦需核对最终镜像产物。

边界：Flyway 当前版本仍提示 H2 2.5 超出其已测试版本；本地实际迁移测试不能替代官方兼容承诺。上游 [#4387](https://github.com/h2database/h2database/issues/4387) 还报告短 `RETENTION_TIME=1000` 与大量混合写入下的重开损坏，当前保留默认 retention 设置，本次并未执行该压力场景；H2 仍只用于演示，不计为生产数据库验收。

## 可重复验证

`scripts/CheckH2Restart.java` 接收一个已停服的、无密码 `sa` 演示库 `.mv.db` 备份和一个尚不存在的输出目录。脚本只复制原文件，再在副本中打开/关闭连接三次。它比较所有 PUBLIC 基础表的行数及完整行内容摘要，包括 Flowable 表；不会输出业务正文，不会修改原备份。

```bash
java -cp /path/to/h2-2.5.250.jar scripts/CheckH2Restart.java \
  /fyoung/tmp/offline-backup.mv.db /fyoung/tmp/new-restart-check
```

该脚本是文件兼容与恢复验收，不作为需要本机私有备份的 CI 测试。验收目录已经存在时直接失败，禁止覆盖。不要复制运行中的数据库作为恢复依据；备份可读、重开后数据一致与 HTTP 业务核对都需要分别验证。升级后不要使用旧版 H2 工具打开新运行库。
