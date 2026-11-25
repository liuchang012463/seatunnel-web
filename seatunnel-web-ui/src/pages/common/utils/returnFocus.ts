import { history } from '@umijs/max';
import { useEffect, useRef } from 'react';

type NavigationState = { returnFocusId?: string; openedFromTrigger?: boolean };

/** Store the opener on this history entry so Back restores the correct button in each tab. */
export function openFromTrigger(path: string, id: string) {
  const { pathname, search, hash, state } = history.location;
  history.replace({ pathname, search, hash }, { ...(state as NavigationState | null), returnFocusId: id });
  history.push(path, { openedFromTrigger: true });
}

export function returnToTrigger(fallbackPath: string) {
  const state = history.location.state as NavigationState | null;
  if (state?.openedFromTrigger) history.back();
  else history.push(fallbackPath);
}

export function useRestoreTriggerFocus(ready = true, fallbackId?: string) {
  const opener = useRef((history.location.state as NavigationState | null)?.returnFocusId);
  useEffect(() => {
    if (!ready || !opener.current) return;
    const button = document.getElementById(opener.current) || (fallbackId && document.getElementById(fallbackId));
    if (button) button.focus();
    opener.current = undefined;
  }, [ready, fallbackId]);
}
