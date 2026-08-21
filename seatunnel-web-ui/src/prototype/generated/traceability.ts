/* 原合同指标原型追踪静态快照；规划源已退休，运行时仅将其作为原型展示数据使用。 */
export const generatedTraceability = [
  {
    "id": "F-01.01",
    "parentId": "F-01",
    "title": "支持表单结构、字段、样式、版本和发布管理",
    "technicalModule": "MOD-001",
    "strategy": "INTEGRATE",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.02",
    "parentId": "F-01",
    "title": "支持字段格式、范围、完整性和跨字段逻辑校验",
    "technicalModule": "MOD-001",
    "strategy": "INTEGRATE",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.03",
    "parentId": "F-01",
    "title": "对有限业务词典和规则识别语义异常并给出可解释建议",
    "technicalModule": "MOD-001",
    "strategy": "LIMITED",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.04",
    "parentId": "F-01",
    "title": "支持单条填报、批量填报、导入导出和上报状态闭环",
    "technicalModule": "MOD-001",
    "strategy": "INTEGRATE",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.05",
    "parentId": "F-01",
    "title": "按 SSO 组织、角色、主体、数据源或场景规则分配采集任务并跟踪状态",
    "technicalModule": "MOD-001",
    "strategy": "BUILD",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.06",
    "parentId": "F-01",
    "title": "提供人工填报和系统自动引接两种模式并统一跟踪",
    "technicalModule": "MOD-001",
    "strategy": "INTEGRATE",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-01.07",
    "parentId": "F-01",
    "title": "保存并复用场景模板、采集模式和任务策略",
    "technicalModule": "MOD-001",
    "strategy": "ADAPT",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-02.01",
    "parentId": "F-02",
    "title": "支持关系库和消息场景的全量、CDC/持续增量引接",
    "technicalModule": "MOD-003",
    "strategy": "REUSE",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  },
  {
    "id": "F-02.02",
    "parentId": "F-02",
    "title": "同步目录层级并正确处理文件新增、修改、删除和断点恢复",
    "technicalModule": "MOD-003",
    "strategy": "INTEGRATE",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  },
  {
    "id": "F-02.03",
    "parentId": "F-02",
    "title": "统一展示多源库表字段、样例、画像、质量和导出结果",
    "technicalModule": "MOD-002",
    "strategy": "INTEGRATE",
    "pageIds": [
      "data-source",
      "data-discovery"
    ]
  },
  {
    "id": "F-03.01",
    "parentId": "F-03",
    "title": "创建、编辑、发布、启动、暂停、终止和查询引接任务",
    "technicalModule": "MOD-004",
    "strategy": "REUSE",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "F-03.02",
    "parentId": "F-03",
    "title": "展示任务和实例状态、吞吐、行数、耗时及汇总",
    "technicalModule": "MOD-005",
    "strategy": "ADAPT",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-03.03",
    "parentId": "F-03",
    "title": "支持立即触发、Quartz 周期和自定义频率调度",
    "technicalModule": "MOD-004",
    "strategy": "ADAPT",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "F-03.04",
    "parentId": "F-03",
    "title": "支持批量创建、执行、暂停和状态查看",
    "technicalModule": "MOD-004",
    "strategy": "REUSE",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "F-03.05",
    "parentId": "F-03",
    "title": "Guide Multi 模式在单任务内配置并同步多张表",
    "technicalModule": "MOD-004",
    "strategy": "REUSE",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "F-03.06",
    "parentId": "F-03",
    "title": "失败状态触发告警并可查看日志和定位任务实例",
    "technicalModule": "MOD-005",
    "strategy": "ADAPT",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-03.07",
    "parentId": "F-03",
    "title": "通过可视化流程配置 Source、Transform、Sink 及策略参数",
    "technicalModule": "MOD-004",
    "strategy": "ADAPT",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "F-04.01",
    "parentId": "F-04",
    "title": "盘点数据源、库表、字段和数据分布并形成统计/清查报告",
    "technicalModule": "MOD-002",
    "strategy": "INTEGRATE",
    "pageIds": [
      "data-source",
      "data-discovery"
    ]
  },
  {
    "id": "F-04.02",
    "parentId": "F-04",
    "title": "维护归属单位、模型、数据库、数据源状态和停用注销",
    "technicalModule": "MOD-002",
    "strategy": "ADAPT",
    "pageIds": [
      "data-source",
      "data-discovery"
    ]
  },
  {
    "id": "F-05.01",
    "parentId": "F-05",
    "title": "设计、分类、版本化和复用采集报告模板",
    "technicalModule": "MOD-001",
    "strategy": "INTEGRATE",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-05.02",
    "parentId": "F-05",
    "title": "支持手动、批量和定时生成报告并记录状态",
    "technicalModule": "MOD-001",
    "strategy": "BUILD",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-05.03",
    "parentId": "F-05",
    "title": "支持 Excel、Word、PDF、CSV、在线预览、批量压缩和受控分发",
    "technicalModule": "MOD-001",
    "strategy": "ADAPT",
    "pageIds": [
      "reporting-forms",
      "reporting-reports"
    ]
  },
  {
    "id": "F-06.01",
    "parentId": "F-06",
    "title": "通过批或流任务把全量/增量数据真实写入湖存储",
    "technicalModule": "MOD-003",
    "strategy": "ADAPT",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  },
  {
    "id": "F-06.02",
    "parentId": "F-06",
    "title": "建立跨源元数据映射和受控逻辑访问入口",
    "technicalModule": "MOD-009",
    "strategy": "LIMITED",
    "pageIds": [
      "lake-resources",
      "lifecycle",
      "logical-access"
    ]
  },
  {
    "id": "F-07.01",
    "parentId": "F-07",
    "title": "Web、外部组件、Engine 与数据源之间使用批准的加密链路",
    "technicalModule": "MOD-007",
    "strategy": "INTEGRATE",
    "pageIds": [
      "edge-access",
      "knowledge",
      "open-api"
    ]
  },
  {
    "id": "F-07.02",
    "parentId": "F-07",
    "title": "人员用户和权限统一接入既有 SSO；终端和外部服务使用独立身份执行认证授权并记录审计",
    "technicalModule": "MOD-007",
    "strategy": "INTEGRATE",
    "pageIds": [
      "edge-access",
      "knowledge",
      "open-api"
    ]
  },
  {
    "id": "F-08.01",
    "parentId": "F-08",
    "title": "支持云边全量镜像和增量差异同步并保持一致性",
    "technicalModule": "MOD-008",
    "strategy": "INTEGRATE",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-08.02",
    "parentId": "F-08",
    "title": "断网时持久化暂存并在恢复后按优先级续传",
    "technicalModule": "MOD-008",
    "strategy": "BUILD",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-08.03",
    "parentId": "F-08",
    "title": "云端下发采集任务，边缘执行并双向同步状态",
    "technicalModule": "MOD-008",
    "strategy": "INTEGRATE",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-09.01",
    "parentId": "F-09",
    "title": "汇聚任务、链路、数据源和告警状态形成可钻取大屏",
    "technicalModule": "MOD-005",
    "strategy": "BUILD",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-09.02",
    "parentId": "F-09",
    "title": "统计成功率、吞吐、时延、完成率和瓶颈趋势",
    "technicalModule": "MOD-005",
    "strategy": "ADAPT",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-10.01",
    "parentId": "F-10",
    "title": "通过插件接入并解析明确列出的异构协议",
    "technicalModule": "MOD-008",
    "strategy": "INTEGRATE",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-10.02",
    "parentId": "F-10",
    "title": "将选定协议数据转换为内部规范模型",
    "technicalModule": "MOD-008",
    "strategy": "ADAPT",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-10.03",
    "parentId": "F-10",
    "title": "管理协议规则、版本和向下兼容策略",
    "technicalModule": "MOD-008",
    "strategy": "BUILD",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-11.01",
    "parentId": "F-11",
    "title": "关联用户操作、任务版本、实例、节点状态、日志和关键摘要",
    "technicalModule": "MOD-005",
    "strategy": "ADAPT",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-11.02",
    "parentId": "F-11",
    "title": "基于已记录事件重放任务节点和状态时间线",
    "technicalModule": "MOD-005",
    "strategy": "LIMITED",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-11.03",
    "parentId": "F-11",
    "title": "区分数据源、网络、Connector、Engine、目标端和配置故障并提供证据",
    "technicalModule": "MOD-005",
    "strategy": "LIMITED",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "F-12.01",
    "parentId": "F-12",
    "title": "统一表示源、目标、逻辑映射、带宽配额和优先级",
    "technicalModule": "MOD-006",
    "strategy": "ADAPT",
    "pageIds": [
      "links",
      "topology"
    ]
  },
  {
    "id": "F-12.02",
    "parentId": "F-12",
    "title": "基于任务状态和基础设施信号执行批准的启停/切换策略",
    "technicalModule": "MOD-006",
    "strategy": "LIMITED",
    "pageIds": [
      "links",
      "topology"
    ]
  },
  {
    "id": "F-12.03",
    "parentId": "F-12",
    "title": "基于指标识别瓶颈并提供可控参数建议和故障恢复",
    "technicalModule": "MOD-006",
    "strategy": "LIMITED",
    "pageIds": [
      "links",
      "topology"
    ]
  },
  {
    "id": "F-13.01",
    "parentId": "F-13",
    "title": "通过 OpenMetadata 连接器扫描数据源和资产结构",
    "technicalModule": "MOD-002",
    "strategy": "INTEGRATE",
    "pageIds": [
      "data-source",
      "data-discovery"
    ]
  },
  {
    "id": "F-13.02",
    "parentId": "F-13",
    "title": "从 Aircas DAG 和 OpenMetadata 血缘生成引接拓扑",
    "technicalModule": "MOD-006",
    "strategy": "INTEGRATE",
    "pageIds": [
      "links",
      "topology"
    ]
  },
  {
    "id": "F-13.03",
    "parentId": "F-13",
    "title": "展示、筛选和钻取资产/链路拓扑",
    "technicalModule": "MOD-006",
    "strategy": "ADAPT",
    "pageIds": [
      "links",
      "topology"
    ]
  },
  {
    "id": "F-14.01",
    "parentId": "F-14",
    "title": "在指定边缘设备上运行受资源约束的轻量接入代理",
    "technicalModule": "MOD-008",
    "strategy": "INTEGRATE",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-14.02",
    "parentId": "F-14",
    "title": "边缘侧执行限定的格式规整、过滤和压缩",
    "technicalModule": "MOD-008",
    "strategy": "BUILD",
    "pageIds": [
      "cloud-edge",
      "edge-access"
    ]
  },
  {
    "id": "F-14.03",
    "parentId": "F-14",
    "title": "边缘代理执行轻量身份认证、链路加密和密钥更新",
    "technicalModule": "MOD-007",
    "strategy": "INTEGRATE",
    "pageIds": [
      "edge-access",
      "knowledge",
      "open-api"
    ]
  },
  {
    "id": "F-15.01",
    "parentId": "F-15",
    "title": "按数据时间与策略在入湖前识别、拦截或标记过期数据",
    "technicalModule": "MOD-009",
    "strategy": "ADAPT",
    "pageIds": [
      "lake-resources",
      "lifecycle",
      "logical-access"
    ]
  },
  {
    "id": "F-15.02",
    "parentId": "F-15",
    "title": "为缓存/暂存数据配置 TTL、容量和清理策略",
    "technicalModule": "MOD-009",
    "strategy": "BUILD",
    "pageIds": [
      "lake-resources",
      "lifecycle",
      "logical-access"
    ]
  },
  {
    "id": "F-15.03",
    "parentId": "F-15",
    "title": "按数据源、类型、部门配置差异化有效期并展示效果",
    "technicalModule": "MOD-009",
    "strategy": "INTEGRATE",
    "pageIds": [
      "lake-resources",
      "lifecycle",
      "logical-access"
    ]
  },
  {
    "id": "P-01",
    "parentId": "P-01",
    "title": "在线全量和增量引接吞吐",
    "technicalModule": "MOD-003",
    "strategy": "ADAPT",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  },
  {
    "id": "P-02",
    "parentId": "P-02",
    "title": "离线文件导入性能",
    "technicalModule": "MOD-009",
    "strategy": "INTEGRATE",
    "pageIds": [
      "lake-resources",
      "lifecycle",
      "logical-access"
    ]
  },
  {
    "id": "P-03",
    "parentId": "P-03",
    "title": "任务启动和管理容量",
    "technicalModule": "MOD-004",
    "strategy": "ADAPT",
    "pageIds": [
      "client",
      "batch-link-up",
      "stream-link-up",
      "knowledge"
    ]
  },
  {
    "id": "P-04",
    "parentId": "P-04",
    "title": "增量发现时效",
    "technicalModule": "MOD-003",
    "strategy": "ADAPT",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  },
  {
    "id": "P-05",
    "parentId": "P-05",
    "title": "异常任务告警时效",
    "technicalModule": "MOD-005",
    "strategy": "ADAPT",
    "pageIds": [
      "bi",
      "metrics",
      "alarm",
      "diagnostics"
    ]
  },
  {
    "id": "P-06",
    "parentId": "P-06",
    "title": "结构化小数据传输吞吐",
    "technicalModule": "MOD-003",
    "strategy": "ADAPT",
    "pageIds": [
      "batch-link-up",
      "stream-link-up",
      "lake-resources"
    ]
  }
] as const;
