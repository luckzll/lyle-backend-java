# V6.5 真实支付渠道接入契约

本文档用于微信支付或支付宝的平台申请、商户号、证书和回调域名就绪后完成真实接入。当前代码只提供 `MOCK` 网关，不得仅通过修改环境变量开启生产收银台。

## 平台材料未就绪时

保持以下安全姿态：

```dotenv
COMMERCIAL_LEDGER_ENABLED=true
COMMERCIAL_SETTLEMENT_SHADOW_ENABLED=true
COMMERCIAL_QUOTA_ENFORCEMENT_ENABLED=false
PAYMENT_CHECKOUT_ENABLED=false
PAYMENT_PROVIDER=MOCK
PAYMENT_RECONCILIATION_ENABLED=false
```

此状态允许查看套餐、账户和账单，但不允许新建真实支付。不要为了通过发布检查而填写虚假商户号、密钥、证书或回调地址。

## 代码接入契约

1. 为真实渠道实现 `PaymentGateway`，`provider()` 必须和 `PAYMENT_PROVIDER` 规范化后的值一致。不能让 `MockPaymentGateway` 代理真实请求。
2. `createPayment` 以 `paymentNo` 作为幂等业务号；重试必须返回同一笔渠道支付，不能重复扣款。
3. `queryPayment` 只查询、不改变渠道状态；`closePayment` 和 `createRefund` 必须允许同一业务号安全重放。
4. 将渠道状态显式映射为域状态，未知状态必须拒绝并告警，不得默认当作成功。
5. 当前回调入口使用 `X-Billing-Signature` HMAC 和平台无关 JSON，只用于本地沙箱。真实接入必须按渠道官方规则验签、解密和解析原始报文，再转换为内部标准事件。不得在网关或代理层伪造内部 HMAC 代替渠道验签。
6. 内部标准事件至少包含 `providerEventId`、`paymentNo`、`status`、`merchantId`、`providerTransactionId`、`amountMinor` 和 `currency`。事件 ID 和原始报文摘要用于回调去重与审计。
7. API 密钥、私钥和平台证书通过部署密钥系统注入，不写入仓库、镜像、日志、测试快照或验收报告。

## 必须完成的渠道验收

- HTTPS 真实回调：正常通知、重复通知、乱序通知、非法签名、错误商户号、错误金额和错误币种。
- 支付闭环：创建、用户取消、超时关闭、成功回调、主动查询补单、异步履约与权益到账。
- 退款闭环：全额退款、同 requestId 重放、渠道处理中、渠道失败、成功后账本单次冲正。
- 对账闭环：金额差异、状态差异、单边账、补单和人工处置留痕。
- 隔离与审计：双用户订单与账本隔离、管理员权限、幂等键冲突、敏感信息脱敏和完整审计记录。

## 开放与回滚顺序

1. 在渠道测试环境完成上述用例，且保持生产 `PAYMENT_CHECKOUT_ENABLED=false`。
2. 生成真实、不入库的 `deploy/.env.production`，执行 `deploy/check_production_readiness.sh deploy/.env.production`。
3. 在发布窗口内完成小额支付、回调去重、退款和对账演练后，才能开启收银台。
4. 异常时先设置 `PAYMENT_CHECKOUT_ENABLED=false` 阻止新单；必要时再设置 `COMMERCIAL_QUOTA_ENFORCEMENT_ENABLED=false` 和 `COMMERCIAL_SETTLEMENT_SHADOW_ENABLED=true`。不删除支付、退款、账本和回调历史数据。
