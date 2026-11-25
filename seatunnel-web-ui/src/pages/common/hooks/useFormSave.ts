import { useEffect, useRef, useState } from 'react';

type Feedback = { phase: 'pending' | 'error' | 'success'; text: string };

/** Keep the form and its live region mounted until the save result has been announced. */
export function useFormSave(subject: string) {
  const [feedback, setFeedback] = useState<Feedback>();
  const inFlight = useRef(false);
  const afterSave = useRef<() => void>();
  const trigger = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (feedback?.phase === 'error' && document.activeElement === document.body) {
      trigger.current?.focus();
    }
    if (feedback?.phase !== 'success') return;
    const timer = window.setTimeout(() => {
      inFlight.current = false;
      setFeedback(undefined);
      afterSave.current?.();
    }, 1200);
    return () => window.clearTimeout(timer);
  }, [feedback]);

  const save = async (operation: () => Promise<string>, onSaved: () => void) => {
    // Lock before validation as two button activations can precede a React render.
    if (inFlight.current) return;
    inFlight.current = true;
    trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setFeedback({ phase: 'pending', text: `正在保存${subject}…` });
    try {
      const text = await operation();
      afterSave.current = onSaved;
      setFeedback({ phase: 'success', text });
    } catch (error) {
      inFlight.current = false;
      if (error && typeof error === 'object' && 'errorFields' in error) {
        setFeedback({ phase: 'error', text: '请检查标记的字段后重试。' });
        return;
      }
      // Never log connection payloads, tokens or passwords.
      console.error(`[表单保存] ${subject}保存失败`);
      const detail = error instanceof Error && error.message ? error.message : `${subject}保存失败`;
      setFeedback({ phase: 'error', text: `${detail}。请检查填写内容后重试。` });
    }
  };

  return {
    feedback,
    saving: feedback?.phase === 'pending',
    locked: feedback?.phase === 'pending' || feedback?.phase === 'success',
    clearFeedback: () => setFeedback(undefined),
    save,
  };
}
