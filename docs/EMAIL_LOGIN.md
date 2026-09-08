# 邮箱验证码登录

保留原用户名/密码登录。邮箱验证码验证成功后，已绑定邮箱进入原 user_id；未绑定邮箱自动创建普通 USER 账号。
不根据用户名、SMTP 发信邮箱自动合并用户，不删除测试账号。新邮箱账号使用验证码登录，其随机生成的内部密码不提供给用户。

## zhoulele 绑定步骤

1. 使用原用户名和密码登录。
2. 打开设置 → 账号与安全 → 绑定邮箱。
3. 输入本人邮箱，获取验证码并验证。聊天、知识库和订单继续属于原 user_id。

不要先在登录页使用未绑定邮箱登录，否则会创建独立账号。已绑定其他用户的邮箱不可覆盖绑定。
当前不提供自助更换已绑定邮箱或账户合并。

## 配置

本地在 backend-java/.env 配置，Docker 在 deploy/.env 配置（现有 Compose 的 env_file 会注入环境变量）。

```dotenv
MAIL_ENABLED=true
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=your-address@gmail.com
SPRING_MAIL_PASSWORD=replace-with-private-app-password
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
MAIL_FROM=your-address@gmail.com
MAIL_FROM_NAME=Lyle AI
```

使用未分享的应用专用密码。STARTTLS 必须成功，连接/读写有超时。服务器需要可访问 SMTP 服务；本地可访问不代表生产服务器可访问。
MAIL_ENABLED 默认 false；关闭时前端隐藏入口，发送和校验接口返回 503。
上线需要后端重新构建、前端重新构建及 Flyway V31 迁移，不能只改 .env。

## 安全行为与接口

- POST /api/auth/email/code：请求 email；成功统一返回发送提示。
- POST /api/auth/email/login：请求 email、code；成功返回原 AuthResponse 和 HttpOnly Refresh Cookie。
- GET /api/auth/email/options：是否启用。
- GET /api/auth/email/binding：已登录用户的绑定邮箱。
- POST /api/auth/email/binding/code、POST /api/auth/email/binding：必须携带有效登录凭据，验证码绑定当前 user_id 和用途。
- 邮箱统一 trim 和小写；暂仅支持 ASCII 邮箱，不做 Gmail 点号/加号别名合并。
- 验证码 6 位、5 分钟有效；Redis 只存带服务端密钥的 HMAC 摘要。
- 每邮箱 60 秒发送间隔，每小时最多 10 次；每 IP 邮箱接口每分钟最多 10 次。
- 验证使用原子 Redis Lua，单次消费；10 分钟窗口最多 5 次校验，重新发送不会清除尝试次数。
- Redis 故障时拒绝发送和校验；邮件发送失败会删除待用验证码，不返回 SMTP 异常细节。
- 同一邮箱及同一用户最多一条绑定，数据库唯一约束阻止并发重复绑定，冲突返回 409。

自动测试使用模拟邮件发送，不会连接真实 SMTP。上线前仍应验证生产 SMTP、实际收信和数据库迁移。
