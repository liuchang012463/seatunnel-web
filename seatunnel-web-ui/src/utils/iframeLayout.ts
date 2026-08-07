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

const normalizePathname = (pathname: string): string => {
  const normalizedPathname = pathname.split(/[?#]/)[0] || '/';
  const pathWithLeadingSlash = normalizedPathname.startsWith('/') ? normalizedPathname : `/${normalizedPathname}`;

  if (pathWithLeadingSlash.length > 1) {
    return pathWithLeadingSlash.replace(/\/+$/, '');
  }

  return pathWithLeadingSlash;
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
