# 双模入湖 Doris 能力 Spike 记录

> 执行日期：2026-08-31
> 环境：`/mnt/lc/doris`，仅记录能力与版本事实，不记录密码、Token 或连接参数。

## 运行基线

| 项目 | 实测结果 |
| --- | --- |
| FE/BE 镜像 | `apache/doris:fe-4.1.2` / `apache/doris:be-4.1.2` |
| Doris 版本 | `doris-4.1.2-rc01-aec169d2025` |
| FE SQL 兼容版本 | `5.7.99` |
| FE | 1 个，容器 `doris-fe-1`，9030 可用 |
| BE | 3 个，均 Alive |
| Catalog | `internal`；另有一个既有 JDBC catalog（名称不写入实现 fixture） |
| FE Driver | `file:///opt/apache-doris/fe/lib/mysql-connector-j-8.0.33.jar` |
| PostgreSQL/Oracle Driver | 本次运行容器内未确认，能力必须为 disabled |

## 可重复的临时验证

验证在唯一临时库 `codex_lake_spike_20260831` 中进行，结束后已执行 `DROP DATABASE` 清理，未触碰业务库或现有 `ods` 库。

| 能力 | 结果 |
| --- | --- |
| `STRING` Duplicate/Unique Key | 拒绝，错误为 `String Type should not be used in key column` |
| `VARCHAR(255)` Key | 创建成功 |
| `AUTO PARTITION BY RANGE(date_trunc(...))` | 创建成功；4.1.2 语法需带空的分区定义 `()` |
| `DISTRIBUTED BY HASH ... BUCKETS AUTO` | 创建成功；`_auto_bucket=true` |
| `ALTER TABLE ... SET ("partition.retention_count"="N")` | 成功；`information_schema.table_properties` 可读回 |
| `SHOW CREATE TABLE` | `STRING` 实际输出为 `text`，并附带 Doris 默认属性 |

## 实现约束

1. DDL Builder 必须阻止 Key 使用 `STRING`/`TEXT`/浮点/复杂类型，并为默认 Key 选择 `VARCHAR(255)`。
2. ContractReader 将 `TEXT` 归一化为 Contract 的 `STRING`，将 `DATETIME(0)` 归一化为 `DATETIME`。
3. Drift 只比较 Web 管理的字段、Key、Auto Range、分布和 retention；不能比较 `SHOW CREATE TABLE` 原始字符串或 Doris 默认属性。
4. MySQL Catalog 是当前可实测能力；PostgreSQL/Oracle 在 Driver 安装、重启并验证前返回稳定 disabled reason。

