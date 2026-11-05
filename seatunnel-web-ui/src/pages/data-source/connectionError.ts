/** Build a readable connection-test error without echoing connector credentials. */
export const getDataSourceConnectionErrorMessage = (error: any): string => {
  const payload = error?.response?.data ?? error?.data ?? error;
  const raw = payload?.message ?? payload?.msg ?? error?.message;
  if (typeof raw !== 'string' || !raw.trim() || raw === 'Network Error') {
    return '连接测试失败，请检查网络、主机、端口、数据库和账号配置。';
  }

  return raw
    .trim()
    .replace(/([a-z][a-z\d+.-]*:\/\/)[^/@\s]+@/gi, '$1******@')
    .replace(
      /((?:["']?)(?:password|passwd|pwd|secret(?:key)?|token|api[_-]?key[a-z0-9_]*|access[_-]?(?:token|keysecret)|clientsecret|privatekey|authorization)(?:["']?)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)/gi,
      '$1******',
    );
};
