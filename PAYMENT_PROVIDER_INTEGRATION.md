# V6.5 真实支付渠道接入契约

本文档用于支付平台申请、商户资料、密钥和回调域名就绪后完成真实接入。当前代码提供 `MOCK`、支付宝网站支付与微信 Native 支付网关；生产收银台仍必须在签约、配置和验收全部完成后开启。

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

## 支付宝网站支付配置

Java 服务使用 `alipay.trade.page.pay`，以 `paymentNo` 作为支付宝 `out_trade_no`。页面支付返回 HTML form，由现有 Vue 收银台渲染并提交；支付成功只以验签通知或服务端主动查询为准。

沙箱联调时通过本机环境配置注入以下字段，Java 必须使用支付宝返回的 PKCS#8 `appPrivateKey` 原值，不添加 PEM 头尾，也不要把任何密钥提交到仓库：

```dotenv
PAYMENT_PROVIDER=ALIPAY
PAYMENT_CHECKOUT_ENABLED=true
ALIPAY_GATEWAY_URL=https://openapi-sandbox.dl.alipaydev.com/gateway.do
ALIPAY_APP_ID=
ALIPAY_APP_PRIVATE_KEY=
ALIPAY_PUBLIC_KEY=
ALIPAY_SELLER_ID=
ALIPAY_SELLER_EMAIL=
ALIPAY_NOTIFY_URL=
ALIPAY_RETURN_URL_BASE=http://localhost:5173/billing/result
```

本地没有公网 HTTPS 地址时，`ALIPAY_NOTIFY_URL` 留空，系统通过 `alipay.trade.query` 主动补单；通知处理代码仍保留在 `/api/billing/webhooks/alipay/notify`。生产环境必须使用支付宝生产网关，并将通知与回跳地址都配置为真实 HTTPS 地址。

## 微信 Native 支付配置

网站收银台使用微信支付 API v3 Native 下单，Java 服务将微信返回的 `code_url` 交给 Vue 生成二维码。支付结果只以服务端验签并解密的通知，或服务端主动查单为准。

需先在微信支付商户平台完成 Native 支付权限、API v3 密钥和商户 API 证书配置。以下值必须通过部署密钥系统注入，私钥只传文件路径，不把私钥内容写入环境变量或仓库：

```dotenv
PAYMENT_PROVIDER=WECHAT_PAY
PAYMENT_CHECKOUT_ENABLED=true
PAYMENT_MERCHANT_ID=1234567890
WECHAT_PAY_APP_ID=wx0000000000000000
WECHAT_PAY_MERCHANT_ID=1234567890
WECHAT_PAY_MERCHANT_SERIAL_NUMBER=
WECHAT_PAY_MERCHANT_PRIVATE_KEY_PATH=/run/secrets/wechat-pay/apiclient_key.pem
WECHAT_PAY_API_V3_KEY=
WECHAT_PAY_NOTIFY_URL=https://luckyzll.site/api/billing/webhooks/wechat-pay/notify
```

微信支付官方当前推荐使用微信支付公钥验签。如已下载公钥，再同时配置：

```dotenv
WECHAT_PAY_PUBLIC_KEY_PATH=/run/secrets/wechat-pay/wechatpay_public_key.pem
WECHAT_PAY_PUBLIC_KEY_ID=PUB_KEY_ID_...
```

两个公钥字段必须同时填写或同时留空。留空时 SDK 使用 API v3 密钥自动下载平台证书。生产 Compose 会把宿主机 `deploy/secrets/wechat-pay/` 只读挂载为容器内 `/run/secrets/wechat-pay/`；该宿主机目录已被 Git 忽略。微信支付没有可以代替真实资金流的通用商户沙箱；在商户号未开通前保持 `PAYMENT_PROVIDER=MOCK` 和 `PAYMENT_CHECKOUT_ENABLED=false`。

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
