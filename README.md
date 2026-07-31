# backend-java

Java 后端服务，采用 Spring Boot + MyBatis-Plus + MySQL。

## 包结构

```text
src/main/java/com/example/aiapp/
  controller/              # HTTP 接口
  service/                 # 业务接口
  service/impl/            # 业务实现，impl 实现 service 外层接口
  domain/
    entity/                # 数据库实体对象，使用 MyBatis-Plus 注解
    dto/                   # 请求参数、服务间传输对象
    vo/                    # 返回前端的视图对象
  mapper/                  # MyBatis-Plus Mapper，继承 BaseMapper
  infrastructure/
    client/                # 调用 Python AI 服务
    storage/               # 文件存储，当前本地 storage，后续可换 MinIO / S3
    cache/                 # 缓存，Redis
    mq/                    # 消息队列
    vector/                # 向量数据库，Qdrant / Milvus
  config/                  # 配置
  utils/                   # 通用工具类

src/main/resources/mapper/
  ChatMessageMapper.xml
  ConversationMapper.xml
  KnowledgeBaseMapper.xml
  KnowledgeFileMapper.xml
```

## Mapper 约定

- 基础增删改查：使用 MyBatis-Plus `BaseMapper`
- 自定义查询、复杂 SQL：写在 `src/main/resources/mapper/*.xml`

## 当前接口

- GET /api/health
- POST /api/conversations
- GET /api/conversations
- POST /api/knowledge-bases
- GET /api/knowledge-bases
- POST /api/knowledge-bases/{knowledgeBaseId}/files
- GET /api/knowledge-bases/{knowledgeBaseId}/files
- GET /api/ingest/tasks/{taskId}
- POST /api/chat/send
- POST /api/auth/refresh
- POST /api/auth/logout
- POST /api/auth/logout-all
- POST /api/auth/change-password
- GET /api/auth/session
- GET /api/usage/summary
- GET /api/usage/by-task
- GET /api/usage/trend
- GET /api/usage/turns

## 执行体验 API

Java 层提供 `/api/experience/*`，始终从 JWT 登录态获取用户身份，再代理至 Python 服务。客户端提交的请求体不会决定用户 ID。

- 模板、引导与草稿：`/api/experience/templates`、`/onboarding`、`/drafts/*`
- 执行中心：`/api/experience/execution/*`、`/confirmations/*`
- 连接诊断：`/api/experience/connections/*`

工具、工作流与自动化接口统一位于 `/api/workbench/*`，前端以“高级设置”入口承载。

接口调试文件：`http/api.http`

## 数据库

- MySQL 8.4
- MyBatis-Plus
- 初始化 SQL: `../deploy/mysql/init.sql`

## 文件存储

当前 MVP 将上传文件保存到：

```text
../storage/
```

并把 `file://` 路径传给 Python AI 服务入库。后续接 MinIO / S3 时，只需要替换 `FileStorageService` 的实现。
