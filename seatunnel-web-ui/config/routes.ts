const prototypeMode = process.env.REACT_APP_PROTOTYPE === '1' || process.env.UMI_APP_PROTOTYPE === '1';
const prototypePage = './prototype/CapabilityPage';
const component = (existing: string) => (prototypeMode ? prototypePage : existing);

/**
 * 真实实现的二级菜单页面（在原型模式下被 CapabilityPage 覆盖，方便菜单评审）。
 *
 * 被撤下的具体页面继续保留原型路由和注册信息，统一落到 CapabilityPage，
 * 供后续原型设计使用，不再加载已删除的业务实现。
 */
const businessRoutes = [
  ['/reporting/forms', prototypePage],
  ['/reporting/reports', component('./reporting-reports')],
  ['/data-source', component('./data-source')],
  ['/client', component('./client')],
  ['/resources/data-discovery', prototypePage],
  ['/sync/batch-link-up', component('./batch-link-up')],
  ['/sync/file-link-up', component('./file-link-up')],
  ['/sync/stream-link-up', component('./stream-link-up')],
  ['/sync/cloud-edge-tasks', prototypePage],
  ['/sync/edge-access-tasks', prototypePage],
  ['/sync/links', prototypePage],
  ['/sync/topology', prototypePage],
  ['/bi', prototypePage],
  ['/metrics', component('./metrics')],
  ['/alarm', component('./alarm')],
  ['/operations/protocol', './prototype/ProtocolPlaceholderPage'],
  ['/operations/diagnostics', prototypePage],
  ['/lake/resources', prototypePage],
  ['/lake/lifecycle', prototypePage],
  ['/lake/logical-access', prototypePage],
  ['/knowledge-management', component('./knowledge-management')],
  ['/open-api', component('./open-api')],
].map(([path, routeComponent]) => ({
  path,
  component: routeComponent,
  hideInMenu: true,
}));

const hiddenRoutes = [
  ['/sync/batch-link-up/:id/detail', './batch-link-up/detail', '/sync/batch-link-up'],
  ['/sync/batch-link-up/:id/config/single', './batch-link-up/config/single', '/sync/batch-link-up'],
  [
    '/sync/batch-link-up/:id/config/single-incremental',
    './batch-link-up/config/single-incremental',
    '/sync/batch-link-up',
  ],
  ['/sync/batch-link-up/:id/config/file-sync', './batch-link-up/config/file-sync', '/sync/batch-link-up'],
  ['/sync/batch-link-up/:id/config/multi', './batch-link-up/config/multi', '/sync/batch-link-up'],
  ['/sync/batch-link-up/:id/config/script', './batch-link-up/config/script', '/sync/batch-link-up'],
  ['/sync/file-link-up/:id/config/file-sync', './batch-link-up/config/file-sync', '/sync/file-link-up'],
  ['/sync/stream-link-up/:id/detail', './stream-link-up/detail', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/single', './stream-link-up/config/single', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/multi', './stream-link-up/config/multi', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/script', './stream-link-up/config/script', '/sync/stream-link-up'],
].map(([path, existing, parentPath]) => ({
  path,
  component: component(existing),
  hideInMenu: true,
  parentKeys: [parentPath],
}));

export default [
  {
    path: '/',
    redirect: prototypeMode ? '/prototype/traceability' : '/data-source',
  },
  {
    path: '/prototype/traceability',
    component: './prototype/TraceabilityPage',
    hideInMenu: true,
  },
  ...businessRoutes,
  ...hiddenRoutes,
  {
    path: '*',
    layout: false,
    component: './404',
  },
];
