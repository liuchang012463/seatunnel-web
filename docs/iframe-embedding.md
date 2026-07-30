# iframe 嵌入与菜单开关

SeaTunnel Web 支持在同一套部署中按页面 URL 控制左侧菜单栏：

```html
<iframe
  src="https://seatunnel.example.com/sync/file-link-up?hideMenu=1"
  title="文件引接任务管理"
></iframe>
```

- `hideMenu=1`、`true`、`yes` 或 `on`：隐藏左侧菜单栏。
- `hideMenu=0`、`false`、`no` 或 `off`：显示左侧菜单栏。
- URL 参数优先于部署默认值，因此父系统和独立访问可以共用一套前端。

如需整个部署默认隐藏菜单，可在构建前设置
`UMI_APP_HIDE_SIDEBAR=1`（兼容 `REACT_APP_HIDE_SIDEBAR=1`）。需要显示菜单的
URL 可显式追加 `hideMenu=0`。

## 跨域嵌入

生产 Nginx 示例默认发送 `X-Frame-Options: SAMEORIGIN`，只允许同源 iframe。
跨域嵌入时，部署人员必须移除该响应头，并在网关或 Nginx 中配置精确的
Content Security Policy 来源白名单，例如：

```nginx
add_header Content-Security-Policy "frame-ancestors 'self' https://portal.example.com" always;
```

不要使用不受限制的 `frame-ancestors *`。同时应确认登录 Cookie 的
`SameSite`/`Secure` 策略满足目标浏览器的第三方 Cookie 规则。

跨站 iframe 中提交账号密码时，前端会自动请求嵌入模式登录，后端将会话
Cookie 设置为 `SameSite=None; Secure`，并在登录后返回原页面（包括
`hideMenu` 等查询参数）。因此跨站嵌入必须使用 HTTPS；普通窗口和同源
iframe 登录仍使用 `SameSite=Lax`。

如果浏览器或企业策略完全禁用了第三方 Cookie，即使使用
`SameSite=None; Secure` 也无法维持 iframe 会话。此时应由父系统与
SeaTunnel Web 对接可信 SSO，而不是在 URL 中传递会话令牌。
