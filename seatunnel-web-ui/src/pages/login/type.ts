import HttpUtils from '@/utils/HttpUtils';

export const apiPrefix = '/api/v1';

export const loginApi = {
  login: (data: any, crossOriginIframe = false): Promise<{ code: number; data: any; message?: string }> => {
    const query = crossOriginIframe ? '?embedded=true' : '';
    return HttpUtils.post(`${apiPrefix}/login${query}`, data);
  },

  googleLogin: (params: { credential: string }) => {
    return HttpUtils.post('/api/v1/auth/google/login', params);
  },
};
