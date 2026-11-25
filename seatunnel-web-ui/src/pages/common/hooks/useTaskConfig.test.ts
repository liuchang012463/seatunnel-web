import { act, renderHook, waitFor } from '@testing-library/react';
import useTaskConfig, { cachedOrSavedScene } from './useTaskConfig';

describe('task configuration navigation', () => {
  const draftKey = 'task-config-test';

  afterEach(() => sessionStorage.removeItem(draftKey));

  it('preserves legacy draft links and makes an explicit edit read the saved task', async () => {
    sessionStorage.setItem(draftKey, JSON.stringify({ name: '草稿' }));
    const initialize = jest.fn();
    const readSaved = jest.fn().mockResolvedValue({ code: 0, data: { name: '已保存' } });
    const { result, rerender } = renderHook(({ scene }) => useTaskConfig({
      id: '7', draftKey, scene: cachedOrSavedScene(scene), readSaved, initialize,
    }), { initialProps: { scene: null as string | null } });
    expect(result.current.status).toBe('ready');
    expect(initialize).toHaveBeenLastCalledWith({ name: '草稿' }, 'create', '7');
    expect(readSaved).not.toHaveBeenCalled();
    rerender({ scene: 'edit' });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(initialize).toHaveBeenLastCalledWith({ name: '已保存' }, 'edit', '7');
  });

  it('keeps missing and damaged explicit drafts local', () => {
    const initialize = jest.fn();
    const readSaved = jest.fn();
    const { result, rerender } = renderHook(({ key }) => useTaskConfig({
      id: '7', draftKey: key, scene: 'create', readSaved, initialize,
    }), { initialProps: { key: draftKey } });
    expect(result.current.status).toBe('draft-missing');
    sessionStorage.setItem(draftKey, '{');
    rerender({ key: draftKey + '-absent' });
    rerender({ key: draftKey });
    expect(result.current.status).toBe('draft-unreadable');
    expect(readSaved).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(draftKey)).toBe('{');
  });

  it('retries a failed read and discards a late response from the previous route', async () => {
    let finishOld!: (response: unknown) => void;
    const readSaved = jest.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve; }))
      .mockRejectedValueOnce(new Error('NetworkError'))
      .mockResolvedValueOnce({ code: 0, data: { name: '新任务' } });
    const initialize = jest.fn();
    const { result, rerender } = renderHook(({ id }) => useTaskConfig({
      id, draftKey: draftKey + id, scene: 'edit', readSaved, initialize,
    }), { initialProps: { id: 'old' } });
    expect(result.current.status).toBe('loading');
    rerender({ id: 'new' });
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.status).toBe('ready'));
    await act(async () => { finishOld({ code: 0, data: { name: '旧任务' } }); });
    expect(initialize).toHaveBeenCalledTimes(1);
    expect(initialize).toHaveBeenCalledWith({ name: '新任务' }, 'edit', 'new');
  });

  it('rechecks edit access when returning to a previously open task', async () => {
    let finishReturn!: (response: unknown) => void;
    const readSaved = jest.fn()
      .mockResolvedValueOnce({ code: 0, data: { name: '当前任务' } })
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockImplementationOnce(() => new Promise((resolve) => { finishReturn = resolve; }));
    const initialize = jest.fn();
    const { result, rerender } = renderHook(({ id }) => useTaskConfig({
      id, draftKey: draftKey + id, scene: 'edit', readSaved, initialize,
    }), { initialProps: { id: 'a' } });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    rerender({ id: 'b' });
    rerender({ id: 'a' });
    expect(result.current.status).toBe('loading');
    await act(async () => { finishReturn({ code: 403 }); });
    expect(result.current.status).toBe('forbidden');
    expect(initialize).toHaveBeenCalledTimes(1);
  });
});
