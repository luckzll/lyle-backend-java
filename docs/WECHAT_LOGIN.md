# 微信网站扫码登录

## 配置

在后端本地 `.env` 和生产运行环境中分别配置。不要提交 AppSecret，不要复用微信支付或小程序的密钥。

```dotenv
# 审核通过并获得网站应用微信登录权限后才设为 true；默认关闭。
WECHAT_LOGIN_ENABLED=false
# 微信开放平台 open.weixin.qq.com → 管理中心 → 网站应用 → 应用详情。
WECHAT_LOGIN_APP_ID=
# 同一个网站应用的 AppSecret，仅后端保存。
WECHAT_LOGIN_APP_SECRET=
# 必须与本项目回调一致；开放平台授权回调域填写 luckyzll.site（无协议、路径）。
WECHAT_LOGIN_CALLBACK_URL=https://luckyzll.site/api/auth/wechat/callback
```

生产必须使用 HTTPS，`REFRESH_COOKIE_SECURE=true`，`AUTH_ALLOWED_ORIGINS` 包含 `https://luckyzll.site`。
二维码和回调必须在同一个域名下运行，保留 Host 转发；不要在 localhost 扫指向生产域的二维码。
Redis 是认证状态存储，不可用时失败关闭。V32 迁移新增身份表，不修改或删除旧用户。
仅设置开关但未填写有效格式凭据时，前端显示“微信登录即将开放”。配置检查不能代替微信审核/权限验证。

## 登录流程

1. 邮箱/微信切换；邮箱功能保持原样。微信打开后 POST `/auth/wechat/start`。
2. 后端保存 5 分钟随机 state，给浏览器独立的 HttpOnly/Secure/Lax 流程 cookie。
3. 展示官方 `qrconnect` iframe，`scope=snsapi_login`、`login_type=jssdk`、`self_redirect=false`。其参数依据微信官方 wxLogin.js；不生成自制二维码、不加载远端 JS 到本页。
4. 微信确认后顶层窗口跳转 `/api/auth/wechat/callback`。必须带启动流程的浏览器 cookie 和正确的一次性 state；原子校验后服务器兑换 code。
5. OAuth 凭据只在服务器内使用，不返回前端，不放日志。回调跳回固定 `/login?wechat=complete`，失败只用固定 `failed` 标识。设置 no-store/no-referrer。
6. 前端 POST `/auth/wechat/complete`，已绑定微信复用原有 AuthService 会话/刷新 cookie/状态校验。
7. 首次微信授权要求邮箱验证码和天爱人机验证；明确点击“关联微信并登录”。已验证的旧邮箱保留原 user_id；新邮箱走原有创建流程，再将 `(appid, openid)` 绑定到此账号。整个数据库关联与会话创建在事务内，唯一约束阻止重复绑定。

不按微信昵称或 UnionID 自动合并，不获取头像/昵称等无关资料，不实现密码登录。一个账号在同一 AppID 下只绑定一个微信。本期不提供微信解绑/换绑；避免误绑定请使用正确邮箱。已存在绑定不能覆盖其他账号。

## 安全及边界

### 旧用户名账号迁移

登录页“旧用户名账号？绑定登录方式”仅供没有邮箱和微信身份的旧账号使用。
先通过人机验证及原用户名/密码验证，获得 10 分钟 HttpOnly 绑定凭证，不签发正常登录会话。
随后验证邮箱，或使用微信扫码，将身份绑定到原 user_id 后才签发会话。
已有邮箱或微信的账号不能继续使用原密码迁移入口。忘记原密码不在本期恢复范围内。
微信迁移意图与 OAuth state 一起保存，回调还需同一浏览器的旧账号凭证；不能拿迁移授权改走新邮箱注册。
绑定冲突不会覆盖其他用户，也不自动合并历史记录、余额、订单。迁移事务锁定原账号并依赖身份唯一约束。
相关 POST 接口为 `/auth/migration/start`、`cancel`、`email/code`、`email/login`，均校验请求来源并限流。

- 所有基于流程 cookie 的 POST 校验允许来源及 Host，拒绝无来源和跨站请求。回调 GET 独立用 state+cookie 防登录 CSRF。
- state 和授权身份均一次性，Redis TTL 5 分钟，刷新使前一码失效。同一浏览器多标签共享一个活动流程，请在最新页面扫码。
- 切换回邮箱会取消当前流程；前端只允许固定微信官方 HTTPS iframe 地址。
- 邮箱验证码绑定具体授权流程，复用现有冷却、尝试次数与人机验证保护；微信路径按 IP 限流。
- code 兑换超时/错误不给用户展示上游内容，不带底层异常到日志。
- 服务器/网关访问日志不得记录回调完整 query string（其中含短期 code/state）。
- 这是 PC 网站应用扫码授权，不是公众号网页静默授权、小程序登录或微信支付；手机同机扫码不便时使用邮箱入口。

## 验收

本地单元和 MVC 测试用假的授权客户端、Redis 和数据库，不连接微信、SMTP 或生产，不代表真实审核通过。
审核后需在 HTTPS 正式域验证：旧账号扫码登录；新微信+旧邮箱关联保持历史记录；新邮箱创建；拒绝授权；二维码刷新/过期；重复 callback；跨浏览器回调；关闭开关；Redis/微信故障；移动端回退邮箱。

官方参考： https://res.wx.qq.com/connect/zh_CN/htmledition/js/wxLogin.js
官方指南： https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html
