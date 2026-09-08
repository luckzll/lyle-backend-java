# 天爱验证码：自建邮箱防刷

当前实现固定使用 Tianai CAPTCHA 1.5.5 核心库，由 Java 后端生成并校验滑块拼图。前端直接加载后端返回的内嵌图片，不加载外部 CAPTCHA 脚本，不调用腾讯云、Cloudflare 或天爱远程服务。

## 部署准备

- 不需要注册验证码平台，也不需要 CAPTCHA_SITE_KEY、CAPTCHA_SECRET 等云服务凭据。旧 CAPTCHA_* 配置已不被代码读取，可在维护配置时移除。
- 保留现有 SMTP、数据库和 Redis 配置。Redis 必须可用，建议 Redis 6.2+（原子 GETDEL）。正式环境不回退到内存存储。
- 前后端一起重新构建；不增加数据库迁移，不改用户 ID、不删除账号。
- 上线前确认 zhoulele 及其他需要保留的账号已经绑定邮箱，用户名登录入口已关闭。
- JVM 必须支持 java.desktop / ImageIO。现有完整 Temurin JRE 可运行，无需图形界面；测试在 headless 模式生成图片。
- 拼图模板和猫背景来自核心库内置资源，另加入生成的山湖背景；刷新时根据上一张背景标记切换图片，同时重新生成挑战。素材说明见 [CAPTCHA_ASSETS.md](CAPTCHA_ASSETS.md)，核心库来源和许可证见 [上游仓库](https://github.com/dromara/tianai-captcha)。应持续更新图库、依赖及防刷策略，不能把基础滑块视为完整风控系统。

## 请求流程

1. POST /api/auth/captcha/challenge：返回公开拼图图片及 ID；答案仅在 Redis，120 秒过期。
2. POST /api/auth/captcha/verify：提交 ID 和滑动轨迹。后端使用生成时保存的图片尺寸，不信任客户端传来的尺寸。
3. 每道题只允许一次提交（无论正确与否），使用 Redis 原子读取并删除；错误或过期必须重新生成。
4. 正确答案签发 256 位随机票据，120 秒有效，绑定可信代理解析后的 IP。
5. 发邮箱验证码时携带 captchaToken；后端原子消费票据后才允许发信。重复、伪造、跨 IP 和过期票据均拒绝。
6. 绑定邮箱的发信入口使用同样验证，并且要求有效登录会话。原邮箱/IP 发信限流仍保留。
7. CAPTCHA 路径每 IP 每分钟最多 20 次请求；认证请求体最多 16 KiB，轨迹最多 256 个点。图像生成或 Redis 失败时不签发有效票据。

## 本地 UI 预览（无真实发信）

前端 scripts/preview-email-ui.mjs 仅监听 127.0.0.1:4193，将 CAPTCHA 路由转发到 4194；其余邮件请求不连接真实 SMTP。
后端测试类 CaptchaPreviewServer 在 4194 运行真实 Tianai 引擎，使用有过期时间的内存测试缓存，不读取 .env，不打包到生产 JAR。

先在 backend-java 运行：
```powershell
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/captcha-preview-classpath.txt -Dmdep.includeScope=test
$captchaPreviewClasspath = (Get-Content -Raw target/captcha-preview-classpath.txt).Trim()
java -Djava.awt.headless=true -cp "target/classes;target/test-classes;$captchaPreviewClasspath" com.example.aiapp.security.CaptchaPreviewServer
```

再启动前端 mock API 和代理到 4193 的 Vite 预览。仅用于展示和手动验证拼图，不能当作生产后端。

## 验收

后端测试使用真实 Tianai 生成/校验引擎、模拟 Redis，覆盖错误答案、单次消费、IP 绑定、畸形轨迹和 Redis 故障。前端 E2E 使用模拟 API，覆盖成功发信流程、失败重试、邮箱变化取消挑战、键盘操作、移动布局及可访问性。

部署后仍需实际检查生产 Redis、图片生成、人工滑块验证、SMTP 收信以及旧账号数据保留。基础滑块可提高自动化成本，但不保证拦截专业识图或代答服务。
