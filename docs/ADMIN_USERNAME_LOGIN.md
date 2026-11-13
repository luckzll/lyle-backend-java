# 管理员用户名登录

管理端支持已授权管理员使用用户名登录，不需要绑定或虚构邮箱。新接口为 `POST /api/auth/admin/password/login`；请求包含 `username`、`password`、`captchaToken` 及验证码提供方需要的可选 `captchaRandstr`。邮箱登录接口保持兼容。

此接口不创建账号、不提升权限、不重置密码。仅允许现有 `ACTIVE` 且角色为 `ADMIN` 的账号登录。生产没有默认管理员密码；账号授权及密码设置应通过受控运维操作完成，密码和密钥不能写入代码、日志或文档。

每次密码尝试先验证并消费滑块凭证，错误密码也需要重新验证。管理员认证在事务内锁定用户行，防止与并发改密、撤权操作回写旧账号信息。密码校验沿用 BCrypt，并限制 UTF-8 长度最多 72 字节；失败响应不区分账号、角色或密码原因。

新接口纳入账号/IP 限流与认证请求体上限。旧 `/auth/login`、`/auth/register`、`/auth/change-password` 继续关闭。成功会话沿用现有访问令牌、刷新轮换和 HttpOnly Cookie，路径 `/api/auth`，生产 HTTPS 下启用 Secure 和 SameSite=Lax。

权限或密码变更应同事务增加 `auth_version`、撤销旧会话和刷新令牌，并记录运维审计，随后重新登录。不要为了测试关闭验证码或为未授权账号创建管理员会话。

验证：`AdminPasswordSecurityTest`、`AuthServiceAdministratorTest` 及相关认证回归共 55 项通过；包含真实 BCrypt/JWT 的 MockMvc 测试、Cookie、角色访问、验证码消耗、限流、请求体边界和锁定读取契约。生产验收另行记录，不将 MockMvc 通过等同于已完成真实人机验证登录。
