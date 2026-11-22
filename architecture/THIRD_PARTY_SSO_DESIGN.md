# 第三方 SSO 接入设计

> **状态：第三方 SSO 与 ticket 生命周期流程已通过真实 Chrome 验收。** 文中分别标明 SeaTunnel 登录验收、IdP fixture 自检、迁移与测试结果的范围；Connect 测试和 IdP token smoke 不代替 SeaTunnel 回调/session 验收。

## 1. 两条认证链路

SeaTunnel Web 提供 OIDC 和外部 ticket 两种入口。Jync 的 `TICKET` 模式是外部 ticket 校验的参考实现；它不是 OIDC Provider。两边都由应用服务器向可信 IdP 校验 ticket，再建立各自的本地 Web 会话。

### Jync / ticket

1. Jync 的 `GET /sso/entry?ticket=...` 是 ticket 消费端：服务端向管理员配置的验证端点确认 ticket，再由 Jync 自己决定角色、建立 session 并回到站内保存路径。Jync 不信任浏览器直接提交的身份字段，也不采用 `next` 参数作为跳转目标。
2. 测试 fixture 的 `GET /ticket/login` 显示登录表单；通过后以 `303` 重定向到固定 SeaTunnel 地址 `http://localhost:8000/api/v1/unified-auth/sso/entry?ticket=...`。ticket 有效期 90 秒且只能消费一次。fixture 仅支持 `POST /ticket/validate`，使用 `application/x-www-form-urlencoded`；SeaTunnel 的验证请求可配置 GET 或 POST，接入该 fixture 时优先使用 POST。成功响应包含 `code: 0`、稳定 subject `seatunnel-sso-fixture-user`（`data.username`）、email 和 name，失效 ticket 返回 HTTP 401。
3. SeaTunnel Web 的 `GET /api/v1/unified-auth/sso/entry` 要求恰好一个 `ticket` 参数。验证成功后先建立 60 秒、进程内的待确认状态并重定向 `/login`，同时设置 HttpOnly、SameSite=Lax 的 `ST_TICKET_BROWSER_BINDING` cookie；此时还没有创建 SeaTunnel session。fixture ticket 已在验证时消费，取消后必须重新从登录门户获取新 ticket。
4. 登录页调用 `GET /api/v1/unified-auth/sso/pending`。只有身份已绑定到启用且有本地 `UserType` 的账号时，响应才带 `localUserName`、`confirmationToken` 和 `confirmable=true`，页面要求用户明确点击“确认并登录”。未绑定或本地用户不可用时不提供确认 token，只提示联系管理员并允许取消。确认成功后才创建 SeaTunnel `SESSION_ID` cookie。

参考实现：Jync 的 [`SsoEntryController`](../../jync/src/main/java/com/qqmu/jync/controller/SsoEntryController.java)、[`HttpTicketValidationClient`](../../jync/src/main/java/com/qqmu/jync/service/auth/oa/HttpTicketValidationClient.java)；fixture 协议见其 README 与 `server.js`；SeaTunnel Web 的 [`UnifiedAuthController`](../seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/controller/UnifiedAuthController.java)、[`UnifiedAuthServiceImpl`](../seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/service/impl/UnifiedAuthServiceImpl.java) 和登录页。

### OIDC

1. 浏览器访问 `GET /api/v1/unified-auth/oidc/start`，SeaTunnel Web 生成短期 `state`、`nonce`、PKCE verifier/challenge 和浏览器绑定值，再重定向到 Provider 的 authorization endpoint。
2. Provider 回跳到固定 callback：`/api/v1/unified-auth/oidc/callback/seatunnel`。服务端核对单次 state、浏览器绑定和配置版本，再以 authorization code 换取 token。
3. SeaTunnel Web 从 JWKS 取密钥并验证 RS256 ID Token、Issuer、Audience、Nonce 和有效时间；subject 与 issuer 用于身份匹配。连接测试只检查 discovery 与可用签名密钥，不能代替一次真实浏览器登录。
4. 验证成功后使用映射到的 SeaTunnel 本地用户建立 Web session。Provider 的 access/refresh token 由 Provider 管理；SeaTunnel Web 不把它们当成本地 session。SeaTunnel Web 自己发出的 `SESSION_ID` cookie 与 IdP token 是不同凭据。

测试 fixture 的 Issuer 固定为 `http://127.0.0.1:39770`，OIDC metadata/JWKS 由 discovery 提供。其测试 client 使用 authorization code、S256 PKCE、`client_secret_basic`、RS256 ID Token、userinfo、introspection 与 `offline_access`；fixture 仅在 client 允许 refresh grant 且授权含 `offline_access` 时签发 refresh token。Access Token 配置为 5 分钟，Refresh Token 为 60 分钟。fixture 的 smoke 使用独立 callback `http://127.0.0.1:39770/test/oidc/callback`；`GET /test/oidc/start` 会验证 ID Token、access/refresh introspection、userinfo 和刷新后的 access token，但不替代 SeaTunnel Web 的真实 callback/session 验收。

OIDC 管理配置和登录入口也位于 [`UnifiedAuthController`](../seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/controller/UnifiedAuthController.java)；回调 URL 必须由服务端环境配置，不能由请求参数覆盖。

## 2. 配置与身份映射

- 启用统一认证前，`SEATUNNEL_WEB_AUTH_MODE` 必须为 `PASSWORD`；`DEV_BYPASS` 模式会拒绝启用 OIDC/ticket。
- 保留 `DEV_BYPASS` 作为本地开发默认旁路：本地登录接口不校验密码、不创建 session，当前用户仍由 `SEATUNNEL_WEB_DEV_USER_ID` 指定；这不是已认证的用户身份，生产认证须使用 `PASSWORD`。
- `SEATUNNEL_WEB_AUTH_CORS_ORIGINS` 仅用于 PASSWORD 模式的跨源凭据请求，默认留空即不开放跨源凭据访问。分离 UI/API 域名时，使用逗号分隔的精确 HTTP(S) origin（含需要的端口，不带路径、通配符、userinfo、query 或 fragment）；CORS 不是 API 身份认证的替代品。`DEV_BYPASS` 保留开发兼容行为：允许任意 origin，但不允许携带跨源凭据。本机验收中 Umi `changeOrigin: true` 会改写转发 Host，Spring 仍按浏览器 `Origin` 严格匹配；因此仅对本机启动进程设置 `SEATUNNEL_WEB_AUTH_CORS_ORIGINS=http://localhost:8000`。该值不写入仓库 `.env`。
- `SEATUNNEL_WEB_AUTH_SECURE_COOKIES` 控制密码 session、OIDC/ticket browser-binding 和 OIDC/ticket session cookie 的 `Secure` 标记，默认 `true`，生产应保持开启。OIDC callback 与 ticket confirm 共用 `createSessionCookie`，因此该策略同样适用于 OIDC 建立的 session。代码按 `secureCookies || request.isSecure()` 设置；本机 HTTP loopback 验收只对启动进程设置 `SEATUNNEL_WEB_AUTH_SECURE_COOKIES=false`，不修改仓库 `.env`，HTTPS 请求仍会设置 `Secure`。
- OIDC 的服务端 `UNIFIED_AUTH_CALLBACK_URL` 必须使用固定 callback path，并与 Provider 注册值完全一致。开发验收可用 `http://localhost:8000/api/v1/unified-auth/oidc/callback/seatunnel`；生产应使用部署域名上的 HTTPS 回调。
- 管理员在统一认证配置中设置 Issuer、Client ID、Client Secret；启用 OIDC 时服务端要求 Client Secret。ticket 模式还需配置验证 endpoint/method、ticket 参数名、成功字段和值、用户名字段、可信 `ticketIssuer`，以及可选 email/display name pointer。
- 按 fixture README 的本地 SSH tunnel 方式，ticket 配置可使用登录入口 `http://127.0.0.1:39770/ticket/login`、验证端点 `http://127.0.0.1:39770/ticket/validate`、POST、参数 `ticket`、成功字段 `code=0`、用户名字段 `data.username`、email/name JSON Pointer `/email` 与 `/name`。fixture 响应不返回 issuer；`ticketIssuer` 应由管理员配置为稳定来源标识。当前 SeaTunnel 身份键实际使用 `ticketIssuer` 与 `ticketUsernameField`，配置中的 result issuer/subject pointer 不参与该身份键。
- 更新配置时，可空 ticket 字段会显式写入数据库 `NULL`，因此管理员清空登录入口等字段后，旧值不会因 ORM 忽略 null 更新而残留在公开 Provider 状态中。
- fixture OIDC Issuer 配置为 `http://127.0.0.1:39770`；SeaTunnel callback 仍是 `http://localhost:8000/api/v1/unified-auth/oidc/callback/seatunnel`。Issuer 的 `127.0.0.1` 由本机 SSH tunnel 提供，浏览器从 metadata 跟随同一 issuer。
- 外部身份按来源、issuer、subject 绑定 SeaTunnel 本地用户。未绑定时默认不自动创建账号；管理员可在统一用户映射页面绑定本地用户。开启自动 provision 时，新账号为 `GENERAL_USER`，不会因 IdP 响应中的管理员标记而获得 SeaTunnel 管理权限。
- SeaTunnel 的角色以本地 `UserType` 为准。Jync 的 admin/ops/viewer 规则只决定 Jync 自身权限；当前集成不会把 Jync 角色自动提升为 SeaTunnel 管理员。管理员应显式绑定本地 `ADMIN_USER`。

## 3. 服务端安全边界

- OIDC 与 ticket endpoint 由服务端访问；地址校验只接受 HTTPS，或本机回环地址上的 HTTP，并拒绝 URL user-info 和 fragment。endpoint 配置需要管理员权限；部署还应限制应用的出站网络目标，不能把 URL 校验当成网络隔离。
- OIDC state/nonce 与 ticket replay reservation 当前保存在进程内存中，ticket replay 记录有时效限制。多实例部署需提供粘性路由或经复核的共享状态实现；重启会清空这些进程内记录。IdP 仍须保证 ticket 自身一次性和短时有效。
- ticket 登录的 pending、browser binding、confirmation token 与 cancellation token 同样是进程内状态，有效期 60 秒。`pending`、`confirm`、`cancel` 要求请求头 `Sec-Fetch-Site: same-origin`；pending/confirm/cancel 响应禁止缓存并带 `Referrer-Policy: no-referrer`。有效 pending 始终返回独立的 `cancellationToken`；只有可确认时才返回专供确认登录的 `confirmationToken`。`confirm` 再次检查 ticket 开关、配置版本、身份绑定及本地用户状态，并一次性消费 confirmation token。`cancel` 使用 cancellation token，并校验同源请求、浏览器绑定以及 token 对应的当前 pending 项；不匹配返回冲突，不建立 session。当前 confirm/cancel 响应不发送 binding-cookie 删除头，浏览器 cookie 按 60 秒 Max-Age 到期。
- pending 无效或过期时 `GET /sso/pending` 返回 HTTP 410；跨站请求被拒绝。`POST /sso/confirm` 成功后创建正常 Web session。OIDC callback 失败时重定向到配置的前端路径并附加 `oidc=failed`，不把 Provider 的 error/code/state 放入 Location；当前 controller 未记录 callback Location 或异常消息。
- 修复前，真实浏览器曾复现未绑定 pending 的取消失败：该状态没有 `confirmationToken`，旧前端仍提交空值，取消断言失败，随后由管理 UI 恢复原映射。修复后，`cancellationToken` 与确认 token 分离，并由服务端绑定到当前浏览器和 pending 项；以下最终 Chrome 结果已覆盖该场景。
- **SeaTunnel 当前实现：** ticket 验证使用的 HTTP client 连接超时为 5 秒，每个请求及响应体读取的总时限为 8 秒，响应体上限为 1 MiB，且不跟随 HTTP redirect。ticket 验证应用代码不记录 ticket 值或响应正文；这项说明限于 SeaTunnel 应用代码，不覆盖代理、访问日志或其他基础设施日志。Jync 在此仅作为 ticket 接入参考，不据此宣称其实现具有上述相同限制。
- Nginx 针对 OIDC callback 的日志规则已复核。本次完整浏览器验收经过本机 Umi，不经过生产反向代理，因此不把该浏览器结果表述为生产 Nginx 的运行时验收。
- fixture 的 ticket 与 OIDC smoke 日志只记录阶段/结果，不记录 ticket、token 或响应正文；OIDC smoke 使用的 token 仅在服务端内存中处理，不显示在页面。
- `ST_OIDC_BROWSER_BINDING` 与 `ST_TICKET_BROWSER_BINDING` 均为 HttpOnly、SameSite=Lax；ticket binding cookie 的 path 限定在 SSO 路由并按 pending TTL 过期。SeaTunnel session cookie 也由服务端设置 HttpOnly；OIDC callback 与 ticket confirm 使用同一 session-cookie 创建方法。以上 cookie 的 `Secure` 由 `SEATUNNEL_WEB_AUTH_SECURE_COOKIES` 控制（默认开启），HTTPS 请求也会强制设置该标记；不要依赖 `request.isSecure()` 单独决定生产策略。

## 4. 测试与生产隔离

测试 fixture 部署在 `/mnt/lc/seatunnel-sso-test`，使用 `linux/arm64` 镜像 `seatunnel-sso-test:1.0.0`。服务使用 host network，不配置 Compose `ports`；监听固定为 `127.0.0.1:39770` 并校验 Issuer/callback。已确认的 Compose 命令为 `sh scripts/compose.sh build` 后执行 `sh scripts/compose.sh up`；监听可用 `ss -lntp 'sport = :39770'` 检查。

从 workstation 建立隧道：

```sh
ssh -N -L 39770:127.0.0.1:39770 haruka@192.168.100.95
```

隧道建立后，浏览器和本机 SeaTunnel API 都通过 `127.0.0.1:39770` 访问 fixture；`/ticket/login` 用于 ticket 登录，`/test/oidc/start` 用于 fixture 自身的 OIDC/refresh 检查。远端 fixture 已确认 healthy；health、OIDC discovery、JWKS 与 SSH tunnel 下的 loopback 监听均已检查通过。这些基础连通性结果不等同于 SeaTunnel 回调或 ticket 登录验收。测试 fixture 的账号、签名密钥、client secret 与 token store 必须和生产完全分离；生产使用独立 Issuer/client/密钥及受控网络策略。

**Token 归属：**测试或生产 IdP 负责签发、刷新、撤销并保护自己的 access/refresh token。SeaTunnel Web 仅消费其 OIDC ID Token 或服务端校验的一次性 ticket；不得把 IdP token 写入 SeaTunnel 用户表、日志、浏览器存储或本地验收材料。

## 5. 验收状态与回滚

### 已完成验证

- 数据库新增 MySQL 与 H2 两个前向 `V1.0.63` migration，使 `User.id` 的表定义与实体 `IdType.AUTO` 对齐；不修改既有 migration，也不重写已有用户 ID。
- H2 升级试验从 `V1.0.62` 到 `V1.0.63`，检查已有用户 ID `1`、`2`、`1701` 保留及新用户 ID 自动生成；最终定向 API tests 为 50 项、0 failed，H2 migration 5 项在 SQL 未变的情况下通过；修改的两个前端文件通过 Biome。真实 MySQL 8.0.39 已从 `1.0.62` 升级到 `1.0.63`：原管理员 ID `1` 保留，列类型、主键及注释未变，`id` 已为 `AUTO_INCREMENT`。
- 主线程完整真实浏览器脚本退出码为 0，以下阶段均通过：`local-admin-login`、`provider-configuration-and-connect-test`、`oidc-auto-provision-general-user`、`disable-auto-provision`、`ticket-unbound-without-session`、`ticket-ui-binding`（统一用户页面 UI 操作，无 API fallback）、`admin-bind-ticket-identity`、`fresh-ticket-explicit-confirmation`、`clear-public-ticket-entry`（保存清空后确认公开 UI 隐藏入口）及 `fixture-oidc-token-refresh-smoke`。
- 修复前的 Chrome 运行在未绑定 ticket 取消处失败，并由 finally 通过管理 UI 恢复原映射；修复后的 `SSO_ACCEPT_TICKET_LIFECYCLE=1` 真实 Chrome 复验中，已绑定与未绑定两态的取消均成功且不建立 session，管理员 UI 解绑及重绑、未绑定提示消失与映射恢复均通过。
- 随后 `SSO_ACCEPT_ONLY_TICKET=1` 真实 Chrome 流程通过：使用新 ticket 明确点击确认，并验证普通用户 session 建立成功。
- 只读数据库核对结果为 1 个活跃 `GENERAL_USER`，OIDC 与 ticket 共 2 条身份映射，且都指向该普通用户。远端 fixture `/test/oidc/start` 的 5 项检查也通过：授权码/PKCE、ID Token/JWKS、access token introspection 与 UserInfo、refresh token introspection/grant、刷新后 access token 验证。这组 fixture 自检独立于 SeaTunnel callback/session 验收。
- 上述 SeaTunnel 浏览器验收经过本机 Umi；Nginx callback 日志规则已复核，但生产反向代理未参与这次浏览器流程。

回滚时通过管理员配置关闭 OIDC 与 ticket，保留本地 `PASSWORD` 登录；不要手工回退已应用的 Flyway 历史或清空数据库。fixture 可用 `sh scripts/compose.sh down` 停止其专属 Compose 项目；生产回滚和数据清理由生产变更流程单独管理。
