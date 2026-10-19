import { menuData } from '../menuData';

export const SITE_NAME = 'Aircas Web';

export const routeTitles = (() => {
  const titles = new Map<string, string>();
  const visit = (items: any[]) => {
    items.forEach((item) => {
      if (item.path && item.name) {
        titles.set(item.path, String(item.name));
      }
      if (item.children) {
        visit(item.children);
      }
    });
  };
  visit(menuData);
  // Hidden or secondary pages that do not have their own menu entry.
  titles.set('/data-source/master-data', '主数据管理');
  titles.set('/sync/file-link-up', '文件引接任务');
  titles.set('/sync/file-resources', '湖文件管理');
  return titles;
})();

/** Longest-prefix lookup so hidden detail/config routes inherit their menu title. */
export const resolveDocumentTitle = (pathname: string) => {
  let match = '';
  routeTitles.forEach((_name, path) => {
    if ((pathname === path || pathname.startsWith(`${path}/`)) && path.length > match.length) {
      match = path;
    }
  });
  if (!match) {
    return SITE_NAME;
  }
  return `${routeTitles.get(match)} - ${SITE_NAME}`;
};
