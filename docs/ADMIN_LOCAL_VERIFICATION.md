# 管理后台本地真实联调

验证日期：2026-11-08。

## 结果

`AdminLocalIntegrationTest` 共 10 项通过，相关 Java 回归共 45 项通过，使用真实 Spring Boot HTTP、MyBatis/JDBC 和独立 MySQL 8.0.43。完整执行 V1～V34 迁移；后续重启验证迁移校验及重复启动通过。测试使用专用的 `127.0.0.1:13316/lyle_admin_it`，未访问现有 3306 数据库，也未连接生产环境。

| 验证项 | 结果 |
| --- | --- |
| 只读余额和流水 | 不创建账户、不发欢迎额度 |
| 同编号六路并发 | 全部成功响应，只生成一笔发放、一条流水及一条管理员审计 |
| 不同编号六路并发 | 已有账户上同时发放，每条流水的操作后余额连续，最终余额准确 |
| 首次开户六路并发 | 同时查到未建账户后争用开户，只创建一个账户，六次发放及流水余额全部准确 |
| 审计写入故障 | 通过故障注入验证账户、额度、流水及请求记录完整回滚 |
| 请求冲突和账号状态 | 修改数量或更换操作者返回 409，禁用账号拒绝发放 |
| 知识库与文件元数据 | 所属用户筛选、就绪状态兼容、跨库文件拒绝、改名与实际操作者审计 |
| 会话权限 | 未登录 401、普通用户 403，真实刷新 Cookie 和会话撤销生效 |
| 工作台与配置 | 使用数据库统计、读取与更新配置、查询审计 |
| 实际 Vue 浏览器 | 通过真实代理和 Cookie 完成用户筛选、1.25 点发放、流水、知识库和审计操作 |

数据库并发测试暴露了 `INSERT IGNORE` 后 `SELECT ... FOR UPDATE` 的锁升级死锁。请求编号及首次账户创建均修正为不改变已有内容的 UPSERT；管理员事务使用 READ COMMITTED，账务层锁定账户后写入授予和流水。首次开户回归通过测试闸门固定六路争用，先在旧 SQL 上复现死锁，再验证修复后通过。以上并发及回滚场景在修正后通过。

## 复现

默认测试会跳过此集成测试，只有明确设置下列变量才执行。先准备独立的本地 MySQL 服务，端口固定 13316，创建专用库 `lyle_admin_it` 及仅对此库有权限的账户。新库将执行全部 Flyway 迁移，已有验证库可复用；测试会持续增加带 `integration-` 前缀的测试账户。

```powershell
$env:LYLE_ADMIN_INTEGRATION = 'true'
$env:LYLE_ADMIN_IT_JDBC_URL = 'jdbc:mysql://127.0.0.1:13316/lyle_admin_it?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:LYLE_ADMIN_IT_USERNAME = '<专用验证库账户>'
$env:LYLE_ADMIN_IT_PASSWORD = '<专用验证库密码>'
mvn '-Dtest=AdminLocalIntegrationTest' test
```

若要同时运行真实 Vue 浏览器流程，先在同级 `lyle-admin-vue` 安装依赖、确保 5176 空闲且安装了 Chrome，再增加：

```powershell
$env:LYLE_ADMIN_IT_UI = 'true'
$env:PLAYWRIGHT_CHANNEL = 'chrome'
mvn '-Dtest=AdminLocalIntegrationTest' test
```

未启用 UI 时，浏览器项跳过。测试显式限制连接地址和数据库名，并在写入测试数据前核对实际端口与库名。默认应用 YAML 作为普通属性读取，移除 `.env` 导入，避免读取日常环境凭据；数据源、定时任务、存储和外部服务均使用测试配置。

## 证据和边界

- Java 结果：`target/surefire-reports/TEST-com.example.aiapp.controller.AdminLocalIntegrationTest.xml`。
- Vue 浏览器：同级管理前端的 `test-results/integration/result.json`、`browser.log` 和两张真实数据库页面截图。
- 浏览器使用后端实际创建的测试会话恢复登录；没有验证邮箱密码加滑块的登录入口，也未使用演示身份。
- Python 和对象存储未启动，AI 状态页面验证的是服务不可用时的失败隔离。没有调用付费模型，没有执行真实文档解析，也没有验收成功上传和解析重试。
- 本次临时 Java、Vue 联调服务和专用 MySQL 均已停止。验证数据保留在根目录 `.local/admin-console-20261108/`，没有删除用户业务文件或修改原有数据库。

生产或预发布数据库的现有数据升级、真实 Python/对象存储及滑块认证仍需对应环境验收。本记录不表示已经部署。
