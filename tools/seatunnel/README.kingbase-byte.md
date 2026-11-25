# Kingbase BYTE 字符字段引接

适用 SeaTunnel Engine 3.0.0。

Kingbase 的 `VARCHAR(4 BYTE)` 限制的是字节数，PostgreSQL 的 `VARCHAR(4)` 限制的是字符数。合法源值可写入目标字符列，但两个类型的约束不等价。旧 JDBC 连接器可能在**读取源表结构**时因为不认识 `varcharbyte` 而失败，此时尚未写入目标表。Engine 修复将 BYTE 长度类型识别为字符串，不把字段当成二进制，也不要求改写同步 SQL。

## 检查实际挂载的连接器

```bash
python3 tools/seatunnel/package-kingbase-byte-patch.py \
  --check-jar /实际挂载目录/connectors/connector-jdbc-3.0.0.jar
```

该命令只读，输出 JAR 和转换器的 SHA-256 及缺失的类型别名。有缺失时返回非零。检查 JAR 文件不代表运行中的进程已经加载该文件。

- **缺少别名**。先复制现场原始 JAR，用下方命令为该版本生成补丁。不能只更新 Web，也不要在同一目录并排放入两个 JDBC JAR。按维护流程更新后，确认所有 Engine 节点加载了同一版本。
- **别名齐全**。不要直接替换 JAR。先取同一运行实例的 Engine 完整异常，区分源类型未识别、其他原生类型不支持和目标表长度约束错误。

```bash
python3 tools/seatunnel/package-kingbase-byte-patch.py \
  --base-jar /备份的现场原始/connector-jdbc-3.0.0.jar \
  --fixed-jar /已验证的修复构建/connector-jdbc-3.0.0.jar \
  --output-dir /新的补丁输出目录
```

脚本只替换金仓转换器类并输出校验文件。使用已有补丁包时，先核对 `manifest.json` 的 `baseSha256` 与现场原始 JAR 是否一致。

用包含 ASCII 边界值和多字节字符值的 `VARCHAR(4 BYTE)` 测试列分别验证自动建表和写入已有表。对比行数及字段值。若目标也必须强制同样的字节上限，需按目标编码设计约束，不能将 `VARCHAR(4)` 视为等价约束。

参考 [Kingbase 字符类型](https://help.kingbase.com.cn/v8/development/sql-plsql/sql/datatype.html) 和 [PostgreSQL Character Types](https://www.postgresql.org/docs/current/datatype-character.html)。
