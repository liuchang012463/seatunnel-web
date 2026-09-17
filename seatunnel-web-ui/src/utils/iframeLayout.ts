import { HIDDEN_LAYOUT_ROUTE_PREFIX } from '../../config/routePrefix';

export interface IframeWindowContext {
  self: unknown;
  top: {
    location?: {
      origin?: string;
    };
  } | null;
  location: {
    origin: string;
  };
}

export type InAppLocationTo =
  | string
  | {
      pathname?: string;
      search?: string;
      hash?: string;
      query?: Record<string, unknown>;
      state?: unknown;
    };

const EXTERNAL_LOCATION_PATTERN = /^(https?:|mailto:|tel:|\/\/)/i;

const normalizePathname = (pathname: string): string => {
  const normalizedPathname = pathname.split(/[?#]/)[0] || '/';
  const pathWithLeadingSlash = normalizedPathname.startsWith('/')
    ? normalizedPathname
    : `/${normalizedPathname}`;

  if (pathWithLeadingSlash.length > 1) {
    return pathWithLeadingSlash.replace(/\/+$/, '');
  }

  return pathWithLeadingSlash;
};

const splitPathAndRest = (pathOrUrl: string): { path: string; rest: string } => {
  const match = pathOrUrl.match(/^([^?#]*)(.*)$/);
  return {
    path: match?.[1] ?? pathOrUrl,
    rest: match?.[2] ?? '',
  };
};

export const shouldHideLayout = (
  pathname = typeof window === 'undefined' ? '' : window.location.pathname,
): boolean => {
  const normalizedPathname = normalizePathname(pathname);

  return (
    normalizedPathname === HIDDEN_LAYOUT_ROUTE_PREFIX ||
    normalizedPathname.startsWith(`${HIDDEN_LAYOUT_ROUTE_PREFIX}/`)
  );
};

/**
 * Strip the `/iframe` namespace prefix so business pathname checks stay prefix-agnostic.
 */
export const stripLayoutPrefix = (pathname: string): string => {
  const normalizedPathname = normalizePathname(pathname);

  if (normalizedPathname === HIDDEN_LAYOUT_ROUTE_PREFIX) {
    return '/';
  }

  if (normalizedPathname.startsWith(`${HIDDEN_LAYOUT_ROUTE_PREFIX}/`)) {
    return normalizedPathname.slice(HIDDEN_LAYOUT_ROUTE_PREFIX.length) || '/';
  }

  return normalizedPathname;
};

/**
 * Always prefix an in-app absolute path with `/iframe` when missing.
 * Leaves external URLs and relative paths unchanged.
 */
export const prefixLayoutPath = (pathOrUrl: string): string => {
  if (!pathOrUrl || EXTERNAL_LOCATION_PATTERN.test(pathOrUrl) || !pathOrUrl.startsWith('/')) {
    return pathOrUrl;
  }

  const { path, rest } = splitPathAndRest(pathOrUrl);
  const normalizedPath = normalizePathname(path);

  if (shouldHideLayout(normalizedPath)) {
    return pathOrUrl;
  }

  const prefixedPath =
    normalizedPath === '/'
      ? HIDDEN_LAYOUT_ROUTE_PREFIX
      : `${HIDDEN_LAYOUT_ROUTE_PREFIX}${normalizedPath}`;

  return `${prefixedPath}${rest}`;
};

/**
 * When the current location is under `/iframe`, rewrite absolute in-app paths
 * into the same namespace. No-op outside iframe mode.
 */
export const withLayoutPrefix = (
  pathOrUrl: string,
  pathname = typeof window === 'undefined' ? '' : window.location.pathname,
): string => {
  if (!shouldHideLayout(pathname)) {
    return pathOrUrl;
  }

  return prefixLayoutPath(pathOrUrl);
};

/**
 * Rewrite history.push/replace targets so iframe-mode navigation cannot escape
 * the `/iframe` namespace.
 */
export const rewriteInAppLocation = <T extends InAppLocationTo>(
  to: T,
  pathname = typeof window === 'undefined' ? '' : window.location.pathname,
): T => {
  if (!shouldHideLayout(pathname)) {
    return to;
  }

  if (typeof to === 'string') {
    return withLayoutPrefix(to, pathname) as T;
  }

  if (to && typeof to === 'object' && typeof to.pathname === 'string') {
    return {
      ...to,
      pathname: withLayoutPrefix(to.pathname, pathname),
    };
  }

  return to;
};

export const applyLayoutVisibility = (hidden: boolean): void => {
  if (typeof document === 'undefined') {
    return;
  }
  document.documentElement.dataset.hideSidebar = String(hidden);
  document.documentElement.dataset.hideHeader = String(hidden);
};

/**
 * Cross-origin iframe logins need a SameSite=None session cookie. Reading the
 * parent origin throws for a cross-origin frame, which is also a positive
 * signal here.
 */
export const isCrossOriginIframe = (
  context: IframeWindowContext | undefined =
    typeof window === 'undefined' ? undefined : window,
): boolean => {
  if (!context || context.self === context.top) {
    return false;
  }

  try {
    return context.top?.location?.origin !== context.location.origin;
  } catch (_error) {
    return true;
  }
};
