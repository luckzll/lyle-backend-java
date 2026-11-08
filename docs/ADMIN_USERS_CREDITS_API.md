# 管理员用户与额度接口

新增接口使用现有 `/admin/**` ADMIN 服务端角色保护。浏览器请求前缀为 `/api`。

|方法|路径|用途|
|---|---|---|
|GET|/admin/users|按 keyword（用户名或 ID）、status、page、size 查询用户；size 上限 100|
|GET|/admin/users/{id}|用户基本信息，ID 为字符串，不返回密码、Token 等字段|
|GET|/admin/users/{id}/credits|纯读取余额、累计授予、累计消耗和最近到期时间|
|GET|/admin/users/{id}/ledger|最近 limit 条流水，limit 最大 100，支持 type 筛选|
|POST|/admin/users/{id}/credits/grants|人工发放，body 含 requestId、creditMicros、validityDays、reason|

## 金额及单位

1 点 = 1,000,000 微额度。查询返回的 ID 和额度为十进制字符串，避免 JavaScript 精度丢失。前端允许最多 6 位小数，使用 BigInt 转换，写入请求限制为不超过 9,000,000,000,000,000 的安全整数微额度。有效期为 1～3650 天，null 为长期有效；操作原因必填。

## 数据与幂等

- 部署需先执行 Flyway V34，新表 `admin_credit_operation` 保存请求、操作者、目标用户、数量、有效期、原因和处理状态。
- 相同 requestId 和相同内容仅执行一次；同编号修改内容或更换操作者/用户返回 409。请求记录使用不改变已有内容的 UPSERT 直接获取独占锁，避免 INSERT IGNORE 后升级锁造成并发死锁。
- 发放、账本流水、管理员审计、幂等状态在同一数据库事务中执行。管理员发放事务使用 READ COMMITTED，账务层锁定账户，确保等待并发请求完成后读取当前余额，且不同发放操作的流水余额连续。禁用账号、未知用户或未开启商业额度功能时拒绝。
- 首次账户创建使用保留原字段的 UPSERT，避免不同请求编号同时为未初始化用户开户时的锁升级死锁；六路争用的真实 MySQL 回归覆盖此场景。
- 使用新的 `grantAdminCredits` 账务入口，复用既有额度批次和账本逻辑，不调用用户端初始化欢迎额度的路径。已有 `grantManualCredits` 行为保持兼容。
- GET 查询不创建商业账户、不触发欢迎额度。未初始化账户返回 initialized=false 和零余额；流水为空。
- 前端二次确认后提交；不确定网络结果时保留同一请求编号及内容，在当前浏览器 sessionStorage 中等待重试。不同管理员重用编号由服务端拒绝。操作原因属于管理记录，请勿输入密码或密钥。

## 范围及验收限制

本次支持用户查询、详情、额度查看、流水和人工增加额度。不含账号启停、删除用户、修改角色、额度扣减、撤销已发放额度、套餐订单和模型上架。流水仅展示最近 100 条，不宣称完整分页归档。

测试：`mvn test -Dtest=AdminUserServiceTest,AdminKnowledgeControllerTest,BillingTransactionContractTest,BillingControllerTest`。覆盖纯查询无账务副作用、重复发放拒绝、内容冲突、账号状态和人工发放不触发欢迎额度。

2026-11-08 已通过专用 MySQL 实例执行 `AdminLocalIntegrationTest`，验证六路重复请求、不同编号并发流水、审计异常回滚、V1～V34 迁移和真实浏览器发放。复现方式及边界见 [本地联调说明](ADMIN_LOCAL_VERIFICATION.md)。本地演示与隔离验证通过不代表生产库已升级或发布。
