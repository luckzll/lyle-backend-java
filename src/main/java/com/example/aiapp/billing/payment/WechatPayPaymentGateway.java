package com.example.aiapp.billing.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "app.billing", name = "payment-provider", havingValue = "WECHAT_PAY")
public class WechatPayPaymentGateway implements PaymentGateway {

    private static final DateTimeFormatter RFC_3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final WechatPayProperties properties;
    private final ObjectMapper objectMapper;
    private volatile Clients clients;

    public WechatPayPaymentGateway(WechatPayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "WECHAT_PAY";
    }

    @Override
    public CheckoutResult createPayment(CreatePaymentCommand command) {
        requireCny(command.currency());
        if (command.amountMinor() <= 0 || command.amountMinor() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信支付金额无效。");
        }

        PrepayRequest request = new PrepayRequest();
        request.setAppid(required(properties.appId(), "微信支付 AppID 未配置。"));
        request.setMchid(required(properties.merchantId(), "微信支付商户号未配置。"));
        request.setDescription(description(command.description()));
        request.setOutTradeNo(command.paymentNo());
        request.setTimeExpire(command.expiresAt()
                .atZone(ZoneId.systemDefault())
                .format(RFC_3339));
        request.setNotifyUrl(required(properties.notifyUrl(), "微信支付回调地址未配置。"));

        com.wechat.pay.java.service.payments.nativepay.model.Amount amount =
                new com.wechat.pay.java.service.payments.nativepay.model.Amount();
        amount.setTotal(Math.toIntExact(command.amountMinor()));
        amount.setCurrency("CNY");
        request.setAmount(amount);

        try {
            PrepayResponse response = clients().nativePayService().prepay(request);
            if (response == null || !StringUtils.hasText(response.getCodeUrl())) {
                throw unavailable();
            }
            return new CheckoutResult(
                    provider(),
                    "PENDING",
                    "QR_CODE",
                    response.getCodeUrl(),
                    command.expiresAt()
            );
        } catch (WechatPayException exception) {
            throw unavailable();
        }
    }

    @Override
    public PaymentResult queryPayment(String paymentNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setOutTradeNo(paymentNo);
        request.setMchid(required(properties.merchantId(), "微信支付商户号未配置。"));
        try {
            Transaction transaction = clients().nativePayService().queryOrderByOutTradeNo(request);
            return paymentResult(transaction);
        } catch (ServiceException exception) {
            return isNotFound(exception)
                    ? new PaymentResult("PENDING", null)
                    : new PaymentResult("UNKNOWN", null);
        } catch (WechatPayException exception) {
            return new PaymentResult("UNKNOWN", null);
        }
    }

    @Override
    public PaymentResult closePayment(String paymentNo) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setOutTradeNo(paymentNo);
        request.setMchid(required(properties.merchantId(), "微信支付商户号未配置。"));
        try {
            clients().nativePayService().closeOrder(request);
            return new PaymentResult("CLOSED", null);
        } catch (WechatPayException exception) {
            return queryPayment(paymentNo);
        }
    }

    @Override
    public RefundResult createRefund(CreateRefundCommand command) {
        requireCny(command.currency());
        if (command.amountMinor() <= 0
                || command.totalAmountMinor() <= 0
                || command.amountMinor() > command.totalAmountMinor()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信支付退款金额无效。");
        }
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(command.paymentNo());
        request.setOutRefundNo(command.refundNo());
        if (StringUtils.hasText(command.reason())) {
            request.setReason(limit(command.reason().trim(), 80));
        }
        AmountReq amount = new AmountReq();
        amount.setRefund(command.amountMinor());
        amount.setTotal(command.totalAmountMinor());
        amount.setCurrency("CNY");
        request.setAmount(amount);
        try {
            return refundResult(clients().refundService().create(request), command.refundNo());
        } catch (WechatPayException exception) {
            return new RefundResult("PENDING", command.refundNo());
        }
    }

    @Override
    public RefundResult queryRefund(String paymentNo, String refundNo) {
        QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(refundNo);
        try {
            return refundResult(clients().refundService().queryByOutRefundNo(request), refundNo);
        } catch (WechatPayException exception) {
            return new RefundResult("UNKNOWN", refundNo);
        }
    }

    public VerifiedPaymentNotification parsePaymentNotification(
            String serialNumber,
            String timestamp,
            String nonce,
            String signature,
            String body
    ) {
        if (!StringUtils.hasText(serialNumber)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(signature)
                || !StringUtils.hasText(body)) {
            throw new IllegalArgumentException("微信支付回调缺少验签字段。");
        }
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serialNumber)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .body(body)
                    .build();
            Transaction transaction = clients().notificationParser()
                    .parse(requestParam, Transaction.class);
            if (transaction == null
                    || !properties.merchantMatches(transaction.getMchid(), transaction.getAppid())
                    || !StringUtils.hasText(transaction.getOutTradeNo())
                    || transaction.getAmount() == null
                    || transaction.getAmount().getTotal() == null) {
                throw new IllegalArgumentException("微信支付回调的商户、应用或订单信息不匹配。");
            }
            JsonNode root = objectMapper.readTree(body);
            String eventId = root.path("id").asText();
            if (!StringUtils.hasText(eventId) || eventId.length() > 128) {
                throw new IllegalArgumentException("微信支付回调缺少事件编号。");
            }
            String currency = StringUtils.hasText(transaction.getAmount().getCurrency())
                    ? transaction.getAmount().getCurrency()
                    : "CNY";
            return new VerifiedPaymentNotification(
                    eventId,
                    transaction.getOutTradeNo(),
                    paymentStatus(transaction.getTradeState()),
                    transaction.getTransactionId(),
                    transaction.getAmount().getTotal().longValue(),
                    currency,
                    sha256(body)
            );
        } catch (WechatPayException exception) {
            throw new IllegalArgumentException("微信支付回调验签或解密失败。", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("微信支付回调格式无效。", exception);
        }
    }

    private Clients clients() {
        Clients current = clients;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (clients == null) {
                if (!properties.credentialsConfigured()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付 API v3 密钥配置尚未完成。");
                }
                if (properties.publicKeyPartiallyConfigured()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付公钥配置不完整。");
                }
                Config config = config();
                clients = new Clients(
                        new NativePayService.Builder().config(config).build(),
                        new RefundService.Builder().config(config).build(),
                        new NotificationParser((NotificationConfig) config)
                );
            }
            return clients;
        }
    }

    private Config config() {
        if (properties.publicKeyConfigured()) {
            return new RSAPublicKeyConfig.Builder()
                    .merchantId(properties.merchantId().trim())
                    .privateKeyFromPath(properties.merchantPrivateKeyPath().trim())
                    .merchantSerialNumber(properties.merchantSerialNumber().trim())
                    .apiV3Key(properties.apiV3Key().trim())
                    .publicKeyFromPath(properties.wechatPayPublicKeyPath().trim())
                    .publicKeyId(properties.wechatPayPublicKeyId().trim())
                    .build();
        }
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(properties.merchantId().trim())
                .privateKeyFromPath(properties.merchantPrivateKeyPath().trim())
                .merchantSerialNumber(properties.merchantSerialNumber().trim())
                .apiV3Key(properties.apiV3Key().trim())
                .build();
    }

    private static PaymentResult paymentResult(Transaction transaction) {
        if (transaction == null) {
            return new PaymentResult("UNKNOWN", null);
        }
        return new PaymentResult(
                paymentStatus(transaction.getTradeState()),
                transaction.getTransactionId()
        );
    }

    private static String paymentStatus(Transaction.TradeStateEnum state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state) {
            case SUCCESS -> "SUCCEEDED";
            case NOTPAY, USERPAYING, ACCEPT -> "PENDING";
            case CLOSED, REVOKED, PAYERROR -> "CLOSED";
            case REFUND -> "SUCCEEDED";
        };
    }

    private static RefundResult refundResult(Refund refund, String fallbackRefundNo) {
        if (refund == null || refund.getStatus() == null) {
            return new RefundResult("UNKNOWN", fallbackRefundNo);
        }
        String status = switch (refund.getStatus()) {
            case SUCCESS -> "SUCCEEDED";
            case PROCESSING -> "PENDING";
            case CLOSED, ABNORMAL -> "FAILED";
        };
        String providerRefundId = StringUtils.hasText(refund.getRefundId())
                ? refund.getRefundId()
                : fallbackRefundNo;
        return new RefundResult(status, providerRefundId);
    }

    private static boolean isNotFound(ServiceException exception) {
        return exception.getHttpStatusCode() == 404
                || "ORDERNOTEXIST".equalsIgnoreCase(exception.getErrorCode())
                || "RESOURCE_NOT_EXISTS".equalsIgnoreCase(exception.getErrorCode());
    }

    private static void requireCny(String currency) {
        if (!"CNY".equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信 Native 支付仅支持当前人民币套餐。");
        }
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
        }
        return value.trim();
    }

    private static String description(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "Lyle AI 套餐";
        normalized = normalized.replaceAll("[/=&]", " ").replaceAll("\\s+", " ");
        return limit(normalized, 127);
    }

    private static String limit(String value, int maxLength) {
        return value.substring(0, Math.min(value.length(), maxLength));
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付服务暂时不可用，请稍后重试。");
    }

    private record Clients(
            NativePayService nativePayService,
            RefundService refundService,
            NotificationParser notificationParser
    ) {
    }

    public record VerifiedPaymentNotification(
            String eventId,
            String paymentNo,
            String status,
            String providerTransactionId,
            long amountMinor,
            String currency,
            String payloadHash
    ) {
    }
}
