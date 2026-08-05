import { prototypeMenuData } from '../menuData';

const summarizeMenu = (items: any[]): any[] =>
  items.map(({ path, name, children }) =>
    children ? { path, name, children: summarizeMenu(children) } : { path, name },
  );

describe('prototype navigation menu', () => {
  it('matches the approved first-level and second-level menu structure', () => {
    expect(summarizeMenu(prototypeMenuData)).toEqual([
      { path: '/bi', name: '引接态势' },
      { path: '/data-source', name: '数据源管理' },
      { path: '/resources/data-discovery', name: '数据探查' },
      { path: '/reporting/forms', name: '数据采报' },
      {
        path: '/menu/ingestion',
        name: '数据引接',
        children: [
          { path: '/metrics', name: '任务概览' },
          { path: '/sync/batch-link-up', name: '离线引接任务' },
          { path: '/sync/stream-link-up', name: '实时引接任务' },
          { path: '/sync/file-link-up', name: '文件引接任务' },
          { path: '/sync/cloud-edge-tasks', name: '云边协同任务' },
          { path: '/sync/edge-access-tasks', name: '边缘接入任务' },
          { path: '/sync/topology', name: '数据拓扑' },
        ],
      },
      {
        path: '/menu/operations',
        name: '运行运维',
        children: [
          { path: '/client', name: '引擎管理' },
          { path: '/alarm', name: '告警管理' },
          { path: '/operations/protocol', name: '协议管理' },
          { path: '/operations/diagnostics', name: '安全加密' },
        ],
      },
      {
        path: '/menu/lake',
        name: '入湖管理',
        children: [
          { path: '/lake/resources', name: '物理入湖管理' },
          { path: '/lake/logical-access', name: '逻辑入湖管理' },
          { path: '/lake/lifecycle', name: '数据生命周期管理' },
        ],
      },
      {
        path: '/menu/system',
        name: '系统管理',
        children: [
          { path: '/knowledge-management', name: '参数与知识' },
          { path: '/open-api', name: '开放接口' },
        ],
      },
    ]);
  });
});
