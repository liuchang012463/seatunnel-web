const prototypeMode =
  process.env.REACT_APP_PROTOTYPE === '1' ||
  process.env.UMI_APP_PROTOTYPE === '1';
const prototypePage = './prototype/CapabilityPage';
const component = (existing: string) =>
  prototypeMode ? prototypePage : existing;

const businessRoutes = [
  ['/reporting/forms', prototypePage],
  ['/reporting/reports', prototypePage],
  ['/data-source', component('./data-source')],
  ['/client', component('./client')],
  ['/resources/data-discovery', prototypePage],
  ['/sync/batch-link-up', component('./batch-link-up')],
  ['/sync/stream-link-up', component('./stream-link-up')],
  ['/sync/cloud-edge-tasks', prototypePage],
  ['/sync/edge-access-tasks', prototypePage],
  ['/sync/links', prototypePage],
  ['/sync/topology', prototypePage],
  ['/bi', component('./bi')],
  ['/metrics', component('./metrics')],
  ['/alarm', component('./alarm')],
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
  ['/sync/batch-link-up/:id/config/multi', './batch-link-up/config/multi', '/sync/batch-link-up'],
  ['/sync/batch-link-up/:id/config/script', './batch-link-up/config/script', '/sync/batch-link-up'],
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
    name: 'Login',
    path: '/login',
    component: './login',
    layout: false,
    hideInMenu: true,
  },
  {
    path: '*',
    layout: false,
    component: './404',
  },
];
