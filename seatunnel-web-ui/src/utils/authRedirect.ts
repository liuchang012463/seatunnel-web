export interface AuthLocation {
  pathname: string;
  search: string;
  hash: string;
}

const DEFAULT_REDIRECT = '/';

export const buildLoginPath = (location: AuthLocation): string => {
  const target = `${location.pathname}${location.search}${location.hash}`;
  const params = new URLSearchParams({ redirect: target });
  return `/login?${params.toString()}`;
};

/**
 * Only allow an in-app target so a crafted login URL cannot become an open
 * redirect after authentication.
 */
export const resolvePostLoginRedirect = (
  href = typeof window === 'undefined' ? '' : window.location.href,
  fallback = DEFAULT_REDIRECT,
): string => {
  if (!href) {
    return fallback;
  }

  const loginUrl = new URL(href);
  const redirect = loginUrl.searchParams.get('redirect');
  if (!redirect) {
    return fallback;
  }

  const target = new URL(redirect, loginUrl.origin);
  if (target.origin !== loginUrl.origin) {
    return fallback;
  }

  return `${target.pathname}${target.search}${target.hash}`;
};
