import { useEffect, useMemo, useState } from 'react';
import type { ConfigPageScene } from '../utils/configPageScene';
import { readTaskConfig, type ConfigRequest, type TaskConfigProblem } from '../utils/taskConfigLoad';

type Source = ConfigPageScene | 'view' | 'cached-or-saved';
export type TaskConfigStatus = TaskConfigProblem | 'loading' | 'ready' | 'draft-missing' | 'draft-unreadable';

interface Options {
  id?: string;
  scene: Source;
  draftKey: string;
  readSaved: ConfigRequest;
  readReleaseState?: ConfigRequest<{ releaseState?: string | number }>;
  initialize: (data: any, scene: ConfigPageScene, id: string) => void;
}

/** Older multi-table, script and file-sync links without a scene reopen an existing local draft. */
export function cachedOrSavedScene(scene: string | null): Source {
  return scene === 'create' || scene === 'edit' ? scene : 'cached-or-saved';
}

export default function useTaskConfig({ id, scene, draftKey, readSaved, readReleaseState, initialize }: Options) {
  const [attempt, setAttempt] = useState(0);
  const request = useMemo(() => ({ id, scene, draftKey, readSaved, readReleaseState, initialize, attempt }),
    [id, scene, draftKey, readSaved, readReleaseState, initialize, attempt]);
  const [result, setResult] = useState<{ request: typeof request; status: TaskConfigStatus }>();

  useEffect(() => {
    const { id, scene, draftKey, readSaved, readReleaseState, initialize } = request;
    let active = true;
    const finish = (status: TaskConfigStatus) => { if (active) setResult({ request, status }); };
    const load = async () => {
      if (!id) { finish('missing'); return; }
      let readingDraft = scene === 'create' || scene === 'cached-or-saved';
      try {
        const draft = readingDraft ? sessionStorage.getItem(draftKey) : null;
        if (scene === 'create' || draft) {
          if (!draft) { finish('draft-missing'); return; }
          initialize(JSON.parse(draft), 'create', id);
        } else {
          readingDraft = false;
          const saved = await readTaskConfig(id, readSaved, readReleaseState);
          if (!active) return;
          if ('problem' in saved) { finish(saved.problem); return; }
          initialize(saved.data, 'edit', id);
        }
        finish('ready');
      } catch {
        // Never log a draft or response body. Either can include connector settings.
        console.warn('[task-config] 无法打开任务配置', { id, scene });
        finish(readingDraft ? 'draft-unreadable' : 'unavailable');
      }
    };
    void load();
    return () => { active = false; };
  }, [request]);

  // A new URL or retry must hide the previous editor even before the effect starts.
  const status = result?.request === request ? result.status : 'loading';
  return { status, retry: () => setAttempt((previous) => previous + 1) };
}
