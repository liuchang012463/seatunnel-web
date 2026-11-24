import { useEffect, useState } from 'react';

type Loader<T> = (signal: AbortSignal) => Promise<T>;

export default function useLineageRead<T>(load?: Loader<T>, revision = 0) {
  const [result, setResult] = useState<{
    load: Loader<T>;
    revision: number;
    data?: T;
    failed: boolean;
  }>();

  useEffect(() => {
    if (!load) return;
    const controller = new AbortController();
    load(controller.signal).then((data) => {
      if (!controller.signal.aborted) setResult({ load, revision, data, failed: false });
    }).catch(() => {
      if (controller.signal.aborted) return;
      setResult((previous) => ({
        load, revision, data: previous?.load === load ? previous.data : undefined, failed: true,
      }));
    });
    return () => controller.abort();
  }, [load, revision]);

  const current = result?.load === load ? result : undefined;
  return {
    data: current?.data,
    loading: Boolean(load) && current?.revision !== revision,
    failed: current?.revision === revision && current.failed,
  };
}
