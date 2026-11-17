// Exercise release Nginx rules against explicit fixtures, without a database or engine.
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const ui = path.resolve(__dirname, '../..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'seatunnel-desktop-smoke-'));
const fixtures = ['Alpha', 'Beta'].map((name, index) => ({
  id: 900001 + index, jobName: `Review ${name}`, mode: 'SCRIPT', taskType: 'BATCH',
  releaseState: 'OFFLINE', sourceType: 'FAKE', sinkType: 'CONSOLE', executionMode: 'MANUAL',
}));
const server = http.createServer(async (req, res) => {
  let body = ''; for await (const part of req) body += part;
  const params = body ? JSON.parse(body) : {};
  let data = [];
  if (req.url.startsWith('/v3/api-docs')) {
    res.setHeader('Content-Type', 'application/json');
    return res.end(JSON.stringify({ openapi: '3.0.1', info: { title: 'Desktop smoke', version: '1' },
      paths: { '/api/health': { get: { summary: '健康检查', responses: {} } } } }));
  }
  if (req.url.includes('/users/currentUser')) data = { id: 1, userName: '桌面验收' };
  if (req.url.includes('/metrics/summary')) data = { totalRecords: 0, totalBytes: 0, totalTasks: 0 };
  if (req.url.includes('/metrics/charts')) data = {
    recordsTrend: [], bytesTrend: [], recordsSpeedTrend: [], bytesSpeedTrend: [],
  };
  if (req.url.includes('/batch-definition/page')) {
    const records = fixtures.filter(row => !params.jobName || row.jobName.includes(params.jobName));
    data = { bizData: records, pagination: { total: records.length, pageNo: 1, pageSize: 10 } };
  }
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify({ code: 0, data }));
});
let nginx;
server.listen(19728, '127.0.0.1', () => {
  const source = fs.readFileSync(path.join(ui, '../seatunnel-web-dist/src/main/conf/nginx/default.conf'), 'utf8')
    .replace('listen 80;', 'listen 127.0.0.1:18081;')
    .replace('/usr/share/nginx/html', path.join(ui, 'dist'))
    .replaceAll('http://seatunnel-web-api:9527', 'http://127.0.0.1:19728');
  const config = `pid ${temp}/nginx.pid;\nerror_log stderr;\nevents {}\nhttp {\n`
    + `types { text/html html; application/javascript js; text/css css; image/png png; }\n`
    + `fastcgi_temp_path ${temp}/fastcgi; uwsgi_temp_path ${temp}/uwsgi; scgi_temp_path ${temp}/scgi;\n`
    + `access_log off; client_body_temp_path ${temp}/body; proxy_temp_path ${temp}/proxy;\n${source}\n}`;
  fs.writeFileSync(path.join(temp, 'nginx.conf'), config);
  nginx = spawn(process.env.TEST_NGINX_BIN || '/usr/sbin/nginx', ['-p', temp, '-c', path.join(temp, 'nginx.conf'), '-g', 'daemon off;'], { stdio: 'inherit' });
  nginx.on('error', error => { console.error(error); server.close(); process.exitCode = 1; });
  nginx.on('exit', code => { server.close(); process.exitCode = code || 0; });
  console.log('Desktop distribution fixture server ready');
});
function close() { nginx?.kill('SIGQUIT'); server.close(); }
process.on('SIGTERM', close);
process.on('SIGINT', close);
