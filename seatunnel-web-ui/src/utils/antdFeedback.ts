import type { ArgsProps as NotificationArgsProps } from 'antd/es/notification/interface';
import type { MessageInstance } from 'antd/es/message/interface';
import type { NotificationInstance } from 'antd/es/notification/interface';
import type { Key } from 'react';

type MessageMethod = 'info' | 'success' | 'error' | 'warning' | 'loading';

interface AntdFeedbackApi {
  message: MessageInstance;
  notification: NotificationInstance;
}

let feedbackApi: AntdFeedbackApi | undefined;

const pendingNotifications: NotificationArgsProps[] = [];
const pendingMessages: Array<{
  method: MessageMethod;
  args: Parameters<MessageInstance[MessageMethod]>;
}> = [];

export const registerAntdFeedback = (api: AntdFeedbackApi) => {
  feedbackApi = api;

  while (pendingMessages.length > 0) {
    const pending = pendingMessages.shift();
    if (pending) {
      (feedbackApi.message[pending.method] as (...args: any[]) => unknown)(
        ...pending.args,
      );
    }
  }

  while (pendingNotifications.length > 0) {
    const pending = pendingNotifications.shift();
    if (pending) feedbackApi.notification.open(pending);
  }
};

const dispatchMessage = <T extends MessageMethod>(
  method: T,
  args: Parameters<MessageInstance[T]>,
) => {
  if (feedbackApi) {
    (feedbackApi.message[method] as (...values: any[]) => unknown)(...args);
    return;
  }

  pendingMessages.push({
    method,
    args: args as Parameters<MessageInstance[MessageMethod]>,
  });
};

export const appMessage = {
  info: (...args: Parameters<MessageInstance['info']>) => dispatchMessage('info', args),
  success: (...args: Parameters<MessageInstance['success']>) => dispatchMessage('success', args),
  error: (...args: Parameters<MessageInstance['error']>) => dispatchMessage('error', args),
  warning: (...args: Parameters<MessageInstance['warning']>) => dispatchMessage('warning', args),
  loading: (...args: Parameters<MessageInstance['loading']>) => dispatchMessage('loading', args),
};

export const appNotification = {
  open: (options: NotificationArgsProps) => {
    if (feedbackApi) {
      feedbackApi.notification.open(options);
      return;
    }
    pendingNotifications.push(options);
  },
  destroy: (key?: Key) => feedbackApi?.notification.destroy(key),
};

export type { AntdFeedbackApi };
