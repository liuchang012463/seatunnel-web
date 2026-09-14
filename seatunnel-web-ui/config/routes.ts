import { HIDDEN_LAYOUT_ROUTE_PREFIX } from './routePrefix';

const hiddenLayoutRoutePrefix = HIDDEN_LAYOUT_ROUTE_PREFIX;
const withHiddenLayoutPrefix = (path: string) =>
  path === '/'
    ? hiddenLayoutRoutePrefix
    : `${hiddenLayoutRoutePrefix}${path.startsWith('/') ? path : `/${path}`}`;

/**
 * 二级菜单页面。尚未接入业务实现的功能保留正式入口，使用统一的占位页。
 */
const businessRoutes = [
  ['/reporting/forms', './FeaturePlaceholderPage'],
  ['/reporting/reports', './reporting-reports'],
  ['/data-source', './data-source'],
  ['/data-source/master-data', './master-data'],
  ['/data-exploration/overview', './data-exploration/overview'],
  ['/data-exploration/tasks', './data-exploration/tasks'],
  ['/data-exploration/results', './data-exploration/results'],
  ['/client', './client'],
  ['/operations/metadata-engine', './operations/metadata-engine'],
  ['/resources/data-discovery', './data-exploration/overview'],
  ['/sync/batch-link-up', './batch-link-up'],
  ['/sync/file-link-up', './file-link-up'],
  ['/sync/stream-link-up', './stream-link-up'],
  ['/sync/cloud-edge-tasks', './FeaturePlaceholderPage'],
  ['/sync/edge-access-tasks', './FeaturePlaceholderPage'],
  ['/sync/links', './FeaturePlaceholderPage'],
  ['/sync/topology', './FeaturePlaceholderPage'],
  ['/bi', './FeaturePlaceholderPage'],
  ['/metrics', './metrics'],
  ['/alarm', './alarm'],
  ['/operations/protocol', './FeaturePlaceholderPage'],
  ['/operations/diagnostics', './FeaturePlaceholderPage'],
  ['/lake/resources', './lake/physical'],
  ['/lake/warehouse', './lake/warehouse'],
  ['/lake/lifecycle', './lake/lifecycle'],
  ['/lake/logical-access', './lake/logical'],
  ['/knowledge-management', './knowledge-management'],
  ['/open-api', './open-api'],
].map(([path, routeComponent]) => ({
  path,
  component: routeComponent,
  hideInMenu: true,
}));

const hiddenRoutes = [
  ['/operations/metadata-engine/config', './operations/metadata-engine/config', '/operations/metadata-engine'],
  ['/lake/warehouse/config', './lake/warehouse/config', '/lake/warehouse'],
  ['/lake/resources/table/create', './lake/physical/wizard', '/lake/resources'],
  ['/lake/resources/table/:mappingId', './lake/physical/table-detail', '/lake/resources'],
  ['/lake/resources/:sourceDataSourceId', './lake/physical/detail', '/lake/resources'],
  ['/lake/logical-access/:catalogId', './lake/logical/detail', '/lake/logical-access'],
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
  ['/sync/file-link-up/:id/detail', './file-link-up/detail', '/sync/file-link-up'],
  ['/sync/file-link-up/:id/config/file-sync', './batch-link-up/config/file-sync', '/sync/file-link-up'],
  ['/sync/stream-link-up/:id/detail', './stream-link-up/detail', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/single', './stream-link-up/config/single', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/multi', './stream-link-up/config/multi', '/sync/stream-link-up'],
  ['/sync/stream-link-up/:id/config/script', './stream-link-up/config/script', '/sync/stream-link-up'],
].map(([path, existing, parentPath]) => ({
  path,
  component: existing,
  hideInMenu: true,
  parentKeys: [parentPath],
}));

const framedRoutes = [
  ...businessRoutes,
  ...hiddenRoutes,
];

const hiddenLayoutRoutes = [
  {
    path: hiddenLayoutRoutePrefix,
    redirect: withHiddenLayoutPrefix('/data-source'),
    hideInMenu: true,
  },
  ...framedRoutes.map((route) => ({
    ...route,
    path: withHiddenLayoutPrefix(route.path),
    hideInMenu: true,
  })),
];

export default [
  {
    path: '/',
    redirect: '/data-source',
  },
  ...framedRoutes,
  ...hiddenLayoutRoutes,
  {
    path: '*',
    layout: false,
    component: './404',
  },
];
