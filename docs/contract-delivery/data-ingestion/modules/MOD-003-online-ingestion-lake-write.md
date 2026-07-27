# MOD-003 在线引接与物理入湖

## 1. 目标

以 SeaTunnel 2.3.13 为真实执行引擎，完成关系库、Kafka、CDC 和文件场景的全量/增量引接及物理入湖。

## 2. 指标与投标模块映射

- 指标：F-02.01、F-02.02、F-06.01、P-01、P-04、P-06。
- 投标模块：数据在线引接、文件夹增量同步、数据物理入湖。

## 3. 当前能力和证据

- 批/流任务、HOCON Builder、JDBC/Kafka、MySQL/PostgreSQL CDC 已有代码实现。
- 通用 JDBC、Kafka 和 CDC 已有 ServiceLoader/HOCON 等测试基础。
- 当前无文件夹增删改实时同步的完整实现证据。
- 本轮未执行真实 SeaTunnel Engine E2E 或吞吐基准。

## 4. 实施边界

### Reuse

- 复用现有数据源插件、HOCON、任务定义、Engine Client、指标和日志。

### Modify

- 按最终湖存储补齐对应 Sink、参数、部署和 UI。
- 补齐连接测试、Analyzer、保存回显和最终包验证。

### Add

- 文件夹增量事件、删除语义、断点、幂等和目录一致性。
- 性能基准配置、数据集和验收脚本/报告。

### Integrate

- 优先评估 SeaTunnel 2.3.13 已有文件 Connector。
- 无法满足实时删除语义时，集成独立文件同步 Agent，而不是强行塞入 JDBC 模型。

### Limited or Mock

- 无。

### Out of scope

- 不由 Web 进程直接搬运生产数据。
- 不把 Web 连接测试或单元测试描述为 Engine E2E。
- 不在目标湖类型未明确前新增虚构 Connector。

## 5. 主要流程与接口边界

```text
SeaTunnel-Web 保存任务
→ 生成 SeaTunnel 2.3.13 HOCON
→ Engine Source/Transform/Sink 执行
→ Web 采集状态/日志/指标
→ 目标湖提交结果形成验收证据
```

## 6. 依赖和实施顺序

1. 冻结目标湖、源/目标 Connector 和 Engine 部署清单。
2. 先完成最小 Source/Sink E2E，再做文件夹增量。
3. 最后在固定环境执行 P-01/P-04/P-06。

## 7. 验收场景

- 全量、CDC/持续增量、Kafka 和多表任务真实运行。
- 文件新增、修改、删除、断网和恢复后源目标一致。
- 失败重试不重不漏，并能从日志定位原因。
- 性能最低线和响应线分别报告，不以瞬时峰值替代持续吞吐。

## 8. 风险与未验证项

- 目标湖 Connector、驱动和 Engine 节点依赖尚未冻结。
- P-06 的 200MB/s 响应值风险极高。
- 文件系统事件在网络盘、跨平台和大量小文件场景下语义不同。
