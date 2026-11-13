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

/** 按失败文案给出可执行的排查建议，避免只提示「测试失败」。 */
export const getDataSourceConnectionTestAdvice = (detail: string): string[] => {
  const text = String(detail || '').toLowerCase();
  const advice: string[] = [];

  if (/timeout|timed?\s*out|超时|time\s*out/.test(text)) {
    advice.push('检查本机到目标主机的网络连通，以及防火墙、安全组是否放行对应端口。');
  }
  if (/connection refused|actively refused|拒绝连接|无法连接|unreachable|no route/.test(text)) {
    advice.push('确认主机地址与端口正确，且目标数据库/服务进程已启动并监听该端口。');
  }
  if (/password|access denied|authentication|auth|认证|鉴权|login|凭据|credential|unauthorized|401|403/.test(text)) {
    advice.push('核对用户名与密码（或 Token）是否正确，账号是否具备连接该库的权限。');
  }
  if (/unknown database|does not exist|database .* not found|库名|schema/.test(text)) {
    advice.push('核对数据库名 / Schema 是否填写正确，账号是否有访问该库的权限。');
  }
  if (/ssl|certificate|handshake|pkix/.test(text)) {
    advice.push('检查 SSL/TLS 开关与证书配置是否与目标端要求一致。');
  }
  if (/driver|classnotfound|nojdbc|找不到驱动/.test(text)) {
    advice.push('确认已安装并选择正确的 JDBC 驱动包，或重新上传驱动后再试。');
  }

  if (advice.length === 0) {
    advice.push('核对主机、端口、数据库名与账号密码是否与目标环境一致。');
    advice.push('确认网络可达，且目标服务允许当前环境发起连接。');
  }

  advice.push('修改配置后再次点击「测试连接」；若仍失败，保留失败原因联系运维或管理员。');
  return advice;
};
