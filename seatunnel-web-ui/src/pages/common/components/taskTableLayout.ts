/**
 * 批量数据引接 / 实时数据引接 / 离线文件导入 / 文件同步任务四个列表页共用的列宽。
 *
 * 任务定义列表复用这些列宽；实时任务不显示调度列。创建时间和最近更新时间保持紧凑，
 * 避免增加更新时间后挤压任务名称、状态和操作列。
 * 勾选列只出现在支持批量操作的列表（批量、实时），宽度同样固定，保证带勾选列的页面彼此一致。
 */
export const TASK_TABLE_COLUMN_WIDTHS = {
  selection: 44,
  name: 208,
  status: 112,
  plan: 198,
  execution: 156,
  schedule: 154,
  createTime: 132,
  updateTime: 132,
  action: 224,
} as const;
