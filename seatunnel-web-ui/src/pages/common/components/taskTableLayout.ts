/**
 * 批量数据引接 / 实时数据引接 / 离线文件导入 / 文件同步任务四个列表页共用的列宽。
 *
 * 名称和状态固定在左、操作固定在右。中间列按完整时间和 14px 操作文字分配宽度，
 * 窄窗口通过表体横向滚动查看其余列。
 * 勾选列只出现在支持批量操作的列表（批量、实时），宽度同样固定，保证带勾选列的页面彼此一致。
 */
export const TASK_TABLE_COLUMN_WIDTHS = {
  selection: 44,
  name: 208,
  status: 112,
  plan: 198,
  execution: 156,
  schedule: 154,
  createTime: 164,
  updateTime: 164,
  action: 240,
} as const;
