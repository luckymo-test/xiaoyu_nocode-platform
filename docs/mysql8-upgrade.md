# MySQL 5.7 → 8.4 升级与回滚手册

> 验证日期：2026-10-24  
> 适用版本：当前 Flyway V1～V14
> 当前生产兼容基线：MySQL 5.7；MySQL 8.4 已完成独立验证，尚未切换生产

## 一、已验证基线

- Spring Boot 3.5.15、Spring AI 1.1.8、MyBatis-Flex 1.11.1。
- MySQL Connector/J 9.7.0。
- Flyway 11.20.3；该版本消除了 Flyway 11.7 对 MySQL 8.4 的“未验证版本”告警。
- MySQL 8.4.0 Community 发行包位于 `D:\computer tools\mysql-8.4`。
- 全新 MySQL 8.4 数据库成功执行 V1～V14，创建 20 张表。
- V14 新增 `ai_call_log.jobId` 与 `(jobId, createTime)` 索引；同版本迁移也在
  独立 MySQL 5.7.19 空库通过。
- `GenerationJobPersistenceProbeTest` 与 `ChatHistoryLongMessagePersistenceTest`
  在 MySQL 8.4 和现有 MySQL 5.7.19 上均通过。
- 默认后端测试 127 个通过，17 个真实模型/浏览器/基础设施探针按约定跳过。

MySQL 5.7 已超出 Flyway 社区支持范围，启动时会打印支持提示；MySQL 8.4
在 Flyway 11.20.3 下没有 SQL 兼容警告。

## 二、兼容性结论

### 已通过

- 业务表的 `utf8mb4_unicode_ci` 排序规则在 8.4 上保持不变。
- 用户名唯一键仍为大小写不敏感；`CaseProbe` 与 `caseprobe` 不能同时写入。
- `generation_job.activeLockKey` 的 STORED GENERATED COLUMN 正常工作。
- 同一 App 只允许一个活跃 Job；终态 Job 的生成列值为 NULL，
  MySQL 唯一索引允许保存多条历史记录。
- `CURRENT_TIMESTAMP`、`ON UPDATE CURRENT_TIMESTAMP` 和 `DATETIME` 默认值行为一致。
- `chat_history.message` 的 `LONGTEXT` 可保存百万字节级历史消息。
- 没有生成列、保留字、索引长度或索引名方面的迁移阻塞项。

### 有意保留

- 迁移中的 JSON 语义字段仍使用 `LONGTEXT`，由应用层校验。
- 本次不迁移 MySQL 原生 `JSON` 类型。原因是当前 5.7 兼容回滚窗口仍需要保留，
  且原生 JSON 会改变排序、默认值和驱动映射语义。
- Flyway 自己创建的 `flyway_schema_history` 在 8.4 上使用数据库默认
  `utf8mb4_0900_ai_ci`；业务表仍显式使用 `utf8mb4_unicode_ci`。

## 三、升级前检查

1. 冻结新写入：停止生成任务、发布、成员变更和后台定时写入。
2. 等待在途事务结束，记录升级前 Job、Revision、Artifact 和用户数量。
3. 备份整个 MySQL 5.7 数据目录。
4. 使用 MySQL 8 客户端从 5.7 导出逻辑备份。不要让 PowerShell 重定向生成
   UTF-16 文件，使用 `--result-file` 写 UTF-8。
5. 将备份复制到独立存储，并校验文件大小和 SHA-256。
6. 在 MySQL 8.4 测试实例上恢复并验证后，才允许切换生产连接。

备份示例：

```powershell
$mysql8 = 'D:\computer tools\mysql-8.4\bin'
& "$mysql8\mysqldump.exe" `
  --protocol=TCP -h <host> -P 3306 -u <user> -p `
  --single-transaction --routines --triggers --events `
  --set-gtid-purged=OFF --column-statistics=0 `
  --default-character-set=utf8mb4 `
  --databases xiaoyu_zerocode `
  --result-file=D:\backup\xiaoyu_zerocode-before-mysql8.sql
```

密码通过交互输入、安全凭据工具或受控环境变量提供，不写入脚本、日志和 Git。

## 四、恢复与验证

1. 在 MySQL 8.4 创建空数据库，显式指定 `utf8mb4` 字符集。
2. 使用 MySQL 8 客户端导入完整备份。
3. 启动应用，让 Flyway 校验现有 V1～V14；已有库应显示 schema version 14。
4. 执行以下真实基础设施探针：

```powershell
$env:JAVA_HOME = 'D:\computer tools\java\JDK'
& 'D:\computer tools\IDEA\IntelliJ IDEA 2023.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -o '-Dtest=GenerationJobPersistenceProbeTest,ChatHistoryLongMessagePersistenceTest' `
  '-DcodegenProbe=true' `
  "-Dspring.datasource.url=$env:CODEGEN_TEST_DB_URL" `
  "-Dspring.datasource.username=$env:CODEGEN_TEST_DB_USER" `
  "-Dspring.datasource.password=$env:CODEGEN_TEST_DB_PASSWORD" `
  test
```

5. 核对关键表行数、最近 Job 状态、Revision 发布指针、Artifact URI 和用户登录。
6. 观察 15 分钟后再开放写入，避免恢复成功但写入路径异常。

如果是从 5.7 导出的旧 `mysqldump` 文件，文件头可能包含已删除的
`NO_AUTO_CREATE_USER` SQL mode。不要直接重试或忽略该错误；应使用 MySQL 8
客户端重新导出，或在受控副本中修正 SQL mode 后重新校验备份。

## 五、启动本地 MySQL 8.4 测试实例

发行包默认不含 `data` 目录。首次使用必须初始化：

```powershell
$mysql = 'D:\computer tools\mysql-8.4'
New-Item -ItemType Directory -Path "$mysql\data"
& "$mysql\bin\mysqld.exe" --no-defaults --initialize-insecure `
  "--basedir=$mysql" "--datadir=$mysql\data" --console
```

初始化会创建空密码 root，仅可用于本机临时验证。启动后必须立即设置强密码；
不要绑定公网地址。启动示例：

```powershell
& "$mysql\bin\mysqld.exe" --no-defaults `
  "--basedir=$mysql" "--datadir=$mysql\data" `
  --port=3307 --bind-address=127.0.0.1 --mysqlx=0
```

不要把 MySQL 5.7 的 `data` 目录直接交给 8.4，也不要原地降级数据目录。
跨大版本迁移使用逻辑备份和恢复。

## 六、切换与回滚窗口

- 切换窗口建议安排在低流量维护期，期间禁止应用写入。
- 切换前保留：5.7 数据目录冷备份、逻辑备份、SHA-256 和版本清单。
- 新库验证完成后，先只读切流，再开放写流量。
- 旧 5.7 实例至少保留 7 天，不删除、不覆盖，并保持停止写入或只读。
- 在尚未接受 8.4 新写入前，回滚只需切回 5.7 连接。
- 已接受新写入后，优先从前置备份恢复；如业务要求保留新写入，
  使用 MySQL 8 客户端重新导出并恢复到 5.7 空库。
- 在后续迁移使用 MySQL 8 专有语法前，反向逻辑回滚保持可行；
  引入专有能力时必须停止承诺 5.7 回滚。
- 正式切换后观察 Job 成功率、事务冲突、锁等待、慢查询和连接池指标；
  出现持续写入错误时按预案切回，不以重试掩盖数据一致性问题。
