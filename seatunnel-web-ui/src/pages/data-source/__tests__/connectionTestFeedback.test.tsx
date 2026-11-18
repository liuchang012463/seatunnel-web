import { Modal } from 'antd';
import { appMessage } from '@/utils/antdFeedback';
import { presentConnectionTestResult } from '../connectionTestFeedback';

jest.mock('antd', () => ({ Modal: { success: jest.fn(), confirm: jest.fn() } }));
jest.mock('@/utils/antdFeedback', () => ({ appMessage: { success: jest.fn() } }));

describe('连接测试反馈', () => {
  beforeEach(() => jest.clearAllMocks());
  it('成功仅显示三秒消息，不打开弹窗', () => {
    presentConnectionTestResult({ success: true, detail: '', dataSourceName: '测试库' });
    expect(appMessage.success).toHaveBeenCalledWith('「测试库」连接测试通过', 3);
    expect(Modal.success).not.toHaveBeenCalled();
    expect(Modal.confirm).not.toHaveBeenCalled();
  });
  it('失败保留原因和修改配置入口', () => {
    const onEdit = jest.fn();
    presentConnectionTestResult({ success: false, detail: 'Connection refused', onEdit });
    const options = (Modal.confirm as jest.Mock).mock.calls[0][0];
    expect(options.title).toBe('连接测试未通过');
    expect(options.okText).toBe('去修改连接配置');
    options.onOk();
    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(appMessage.success).not.toHaveBeenCalled();
  });
});
