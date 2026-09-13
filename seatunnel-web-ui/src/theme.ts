export type NavTheme = "light" | "realDark";

export const THEME_STORAGE_KEY = "seatunnel-web-theme";

const normalizeNavTheme = (value: string | null | undefined): NavTheme =>
  value === "light" ? "light" : "realDark";

export const getStoredNavTheme = (fallback: NavTheme = "realDark"): NavTheme => {
  if (typeof window === "undefined") {
    return fallback;
  }

  try {
    const storedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
    return storedTheme === null ? fallback : normalizeNavTheme(storedTheme);
  } catch (_error) {
    return fallback;
  }
};

export const isDarkNavTheme = (navTheme?: string): boolean =>
  normalizeNavTheme(navTheme) === "realDark";

export const applyNavTheme = (navTheme?: string): NavTheme => {
  const normalizedTheme = normalizeNavTheme(navTheme);

  if (typeof document !== "undefined") {
    document.documentElement.dataset.stTheme =
      normalizedTheme === "realDark" ? "dark" : "light";
    document.documentElement.style.colorScheme =
      normalizedTheme === "realDark" ? "dark" : "light";
  }

  return normalizedTheme;
};

export const persistNavTheme = (navTheme: NavTheme): void => {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, navTheme);
  } catch (_error) {
    // Theme state still applies for the current session when storage is unavailable.
  }
};

/**
 * 主题初始值中转：getInitialState（rootContainer 之外）写入，
 * ThemeConfigProvider 挂载时读取一次作为 React 状态初值；
 * 运行时切换经 ThemeConfigProvider 的 Context，避免分包产生多份模块实例。
 */
let currentNavTheme: NavTheme = "realDark";

export const getNavThemeSnapshot = (): NavTheme => currentNavTheme;

export const setNavTheme = (navTheme?: string): NavTheme => {
  currentNavTheme = normalizeNavTheme(navTheme);

  return currentNavTheme;
};
