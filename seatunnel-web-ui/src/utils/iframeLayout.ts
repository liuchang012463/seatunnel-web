const TRUE_VALUES = new Set(['1', 'true', 'yes', 'on']);
const FALSE_VALUES = new Set(['0', 'false', 'no', 'off']);

export interface IframeLayoutEnvironment {
  UMI_APP_HIDE_SIDEBAR?: string;
  REACT_APP_HIDE_SIDEBAR?: string;
}

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

const parseSwitch = (value?: string | null): boolean | undefined => {
  if (value == null || value.trim() === '') {
    return undefined;
  }
  const normalized = value.trim().toLowerCase();
  if (TRUE_VALUES.has(normalized)) {
    return true;
  }
  if (FALSE_VALUES.has(normalized)) {
    return false;
  }
  return undefined;
};

/**
 * The URL switch has priority so one deployment can serve both the standalone
 * shell and iframe pages. Environment variables provide a deployment default.
 */
export const shouldHideSidebar = (
  search = typeof window === 'undefined' ? '' : window.location.search,
  environment: IframeLayoutEnvironment = {
    UMI_APP_HIDE_SIDEBAR: process.env.UMI_APP_HIDE_SIDEBAR,
    REACT_APP_HIDE_SIDEBAR: process.env.REACT_APP_HIDE_SIDEBAR,
  },
): boolean => {
  const querySwitch = parseSwitch(
    new URLSearchParams(search).get('hideMenu'),
  );
  if (querySwitch !== undefined) {
    return querySwitch;
  }

  return (
    parseSwitch(environment.UMI_APP_HIDE_SIDEBAR) ??
    parseSwitch(environment.REACT_APP_HIDE_SIDEBAR) ??
    false
  );
};

export const applySidebarVisibility = (hidden: boolean): void => {
  if (typeof document === 'undefined') {
    return;
  }
  document.documentElement.dataset.hideSidebar = String(hidden);
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
