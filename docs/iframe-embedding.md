# iframe 嵌入与菜单开关

SeaTunnel Web 通过 URL 路径前缀 `/iframe` 进入嵌入模式：隐藏左侧菜单与顶栏，且站内跳转会自动留在 `/iframe` 命名空间。

```html
<iframe
  src="https://seatunnel.example.com/iframe/data-source"
  title="数据源管理"
></iframe>
```

深链示例：

- `/iframe/sync/link-up`
- `/iframe/lake/resources`
- `/iframe/sync/batch-link-up/123/detail`

普通路径（如 `/data-source`）仍保留完整壳层，供独立访问使用。不要用 `?hideMenu=1`——该查询开关已废弃且无效。

## 行为说明

1. 宿主将 iframe 的 `src` 设为任意 `/iframe/...` 入口。
2. 应用内 `history.push` / `replace` 与菜单链接在嵌入模式下会自动补上 `/iframe` 前缀。
3. `/iframe` 本身会重定向到 `/iframe/data-source`。

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
Cookie 设置为 `SameSite=None; Secure`，并在登录后返回原页面。因此跨站嵌入必须使用 HTTPS；普通窗口和同源 iframe 登录仍使用 `SameSite=Lax`。

如果浏览器或企业策略完全禁用了第三方 Cookie，即使使用
`SameSite=None; Secure` 也无法维持 iframe 会话。此时应由父系统与
SeaTunnel Web 对接可信 SSO，而不是在 URL 中传递会话令牌。
