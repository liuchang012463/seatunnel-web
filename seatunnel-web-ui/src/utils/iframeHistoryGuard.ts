import { rewriteInAppLocation } from './iframeLayout';

type HistoryLike = {
  push: (to: unknown, state?: unknown) => void;
  replace: (to: unknown, state?: unknown) => void;
};

let installed = false;

/**
 * Patch Umi history after createHistory(). Must not run during app.tsx module
 * init — importing history from `@umijs/max` there trips a circular dependency
 * and leaves the splash screen up forever.
 */
export const installIframeHistoryGuard = (history: HistoryLike | undefined): void => {
  if (installed || !history?.push || !history?.replace) {
    return;
  }

  installed = true;

  const rawPush = history.push.bind(history);
  const rawReplace = history.replace.bind(history);

  history.push = (to: unknown, state?: unknown) => {
    const next = rewriteInAppLocation(to as string | { pathname?: string });
    return state === undefined ? rawPush(next) : rawPush(next, state);
  };

  history.replace = (to: unknown, state?: unknown) => {
    const next = rewriteInAppLocation(to as string | { pathname?: string });
    return state === undefined ? rawReplace(next) : rawReplace(next, state);
  };
};
