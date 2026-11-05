# 管理员知识库接口

新增路径均受现有 Spring Security `/admin/**` 的 ADMIN 角色校验保护；浏览器统一添加 `/api` 服务上下文。

|方法|路径|功能|
|---|---|---|
|GET|/admin/knowledge-bases|分页查询，支持 userId、keyword、page、size；size 最大 100|
|GET|/admin/knowledge-bases/{id}|知识库基本信息和所属用户 ID|
|GET|/admin/knowledge-bases/{id}/files|文件元数据分页，支持 keyword、status、page、size|
|PATCH|/admin/knowledge-bases/{id}|改名，body 为 name|
|POST|/admin/knowledge-bases/{id}/files|multipart file 上传，复用既有存储、大小校验与摄取服务|
|POST|/admin/knowledge-bases/{id}/files/{fileId}/rebuild|仅允许失败文件重试|

ID 按字符串返回，避免前端丢失 Long 精度。文件视图不返回存储路径、桶名、对象键或正文。解析状态统一为大写，与 Python 内部小写状态兼容。重试持有文件行锁，串行化检查与状态变更；行锁会覆盖摄取请求调用，需关注后续真实环境超时与吞吐情况。

跨用户处理复用真实所属用户作用域，额外以当前管理员 ID 记录 ADMIN_KNOWLEDGE_* 审计；底层原有服务审计仍会保留。未增加删除接口：现有删除服务只删除数据库记录，未完成物理文件及索引清理，不能直接扩展成管理员删除能力。未增加创建知识库入口、切片统计或详细失败原因接口。

验证命令：`mvn test -Dtest=AdminKnowledgeControllerTest,FileServiceImplTest,KnowledgeBaseServiceImplTest`。控制器测试覆盖所属用户传递、实际管理员审计、跨库文件拒绝和非失败状态拒绝；数据库行锁和真实存储/AI 摄取仍需集成环境验证。
