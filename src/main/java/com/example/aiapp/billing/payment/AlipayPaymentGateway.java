package com.example.aiapp.billing.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.billing", name = "payment-provider", havingValue = "ALIPAY")
public class AlipayPaymentGateway implements PaymentGateway {

    private static final DateTimeFormatter ALIPAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CHARSET = "UTF-8";
    private final AlipayProperties properties;

    public AlipayPaymentGateway(AlipayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "ALIPAY";
    }

    @Override
    public CheckoutResult createPayment(CreatePaymentCommand command) {
        requireCny(command.currency());
        if (command.amountMinor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付金额必须大于 0。 ");
        }
        try {
            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            if (StringUtils.hasText(properties.notifyUrl())) {
                request.setNotifyUrl(properties.notifyUrl().trim());
            }
            request.setReturnUrl(UriComponentsBuilder
                    .fromUriString(required(properties.returnUrlBase(), "支付宝同步回跳地址未配置。"))
                    .pathSegment(command.orderNo())
                    .build()
                    .toUriString());

            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            model.setOutTradeNo(command.paymentNo());
            model.setTotalAmount(amount(command.amountMinor()));
            model.setSubject(subject(command.description()));
            model.setProductCode("FAST_INSTANT_TRADE_PAY");
            model.setTimeExpire(formatAlipayTime(command.expiresAt()));
            request.setBizModel(model);

            AlipayTradePagePayResponse response = client().pageExecute(request, "POST");
            if (!response.isSuccess() || !StringUtils.hasText(response.getBody())) {
                throw unavailable();
            }
            return new CheckoutResult(
                    provider(),
                    "PENDING",
                    "HTML_FORM",
                    response.getBody(),
                    command.expiresAt()
            );
        } catch (AlipayApiException exception) {
            throw unavailable();
        }
    }

    static String formatAlipayTime(LocalDateTime utcTime) {
        return utcTime.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ALIPAY_ZONE)
                .format(ALIPAY_TIME);
    }

    @Override
    public PaymentResult queryPayment(String paymentNo) {
        try {
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            model.setOutTradeNo(paymentNo);
            request.setBizModel(model);
            AlipayTradeQueryResponse response = client().execute(request);
            if (!response.isSuccess()) {
                return new PaymentResult(
                        "ACQ.TRADE_NOT_EXIST".equalsIgnoreCase(response.getSubCode()) ? "PENDING" : "UNKNOWN",
                        null
                );
            }
            return new PaymentResult(paymentStatus(response.getTradeStatus()), response.getTradeNo());
        } catch (AlipayApiException exception) {
            return new PaymentResult("UNKNOWN", null);
        }
    }

    @Override
    public PaymentResult closePayment(String paymentNo) {
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(paymentNo);
            request.setBizModel(model);
            AlipayTradeCloseResponse response = client().execute(request);
            return response.isSuccess()
                    ? new PaymentResult("CLOSED", response.getTradeNo())
                    : new PaymentResult("UNKNOWN", response.getTradeNo());
        } catch (AlipayApiException exception) {
            return new PaymentResult("UNKNOWN", null);
        }
    }

    @Override
    public RefundResult createRefund(CreateRefundCommand command) {
        requireCny(command.currency());
        try {
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(command.paymentNo());
            model.setOutRequestNo(command.refundNo());
            model.setRefundAmount(amount(command.amountMinor()));
            if (StringUtils.hasText(command.reason())) {
                model.setRefundReason(command.reason().trim());
            }
            request.setBizModel(model);
            AlipayTradeRefundResponse response = client().execute(request);
            if (!response.isSuccess()) {
                return new RefundResult("FAILED", null);
            }
            return "Y".equalsIgnoreCase(response.getFundChange())
                    ? new RefundResult("SUCCEEDED", command.refundNo())
                    : new RefundResult("PENDING", command.refundNo());
        } catch (AlipayApiException exception) {
            return new RefundResult("PENDING", command.refundNo());
        }
    }

    @Override
    public RefundResult queryRefund(String paymentNo, String refundNo) {
        try {
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
            model.setOutTradeNo(paymentNo);
            model.setOutRequestNo(refundNo);
            request.setBizModel(model);
            AlipayTradeFastpayRefundQueryResponse response = client().execute(request);
            if (!response.isSuccess()) {
                return new RefundResult("UNKNOWN", refundNo);
            }
            return switch (String.valueOf(response.getRefundStatus())) {
                case "REFUND_SUCCESS" -> new RefundResult("SUCCEEDED", refundNo);
                case "REFUND_PROCESSING" -> new RefundResult("PENDING", refundNo);
                default -> new RefundResult("UNKNOWN", refundNo);
            };
        } catch (AlipayApiException exception) {
            return new RefundResult("UNKNOWN", refundNo);
        }
    }

    public boolean verifyNotification(Map<String, String> parameters) {
        if (!properties.sdkConfigured()) {
            return false;
        }
        try {
            return AlipaySignature.rsaCheckV1(
                    parameters,
                    properties.alipayPublicKey().trim(),
                    CHARSET,
                    parameters.getOrDefault("sign_type", "RSA2")
            );
        } catch (AlipayApiException exception) {
            return false;
        }
    }

    public boolean notificationOwnerMatches(Map<String, String> parameters) {
        return properties.sdkConfigured()
                && properties.appId().trim().equals(parameters.get("app_id"))
                && properties.sellerConfigured()
                && properties.sellerMatches(parameters.get("seller_id"), parameters.get("seller_email"));
    }

    private AlipayClient client() {
        if (!properties.sdkConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付宝支付配置尚未完成。 ");
        }
        AlipayConfig config = new AlipayConfig();
        config.setServerUrl(properties.gatewayUrl().trim());
        config.setAppId(properties.appId().trim());
        config.setPrivateKey(properties.appPrivateKey().trim());
        config.setFormat("json");
        config.setAlipayPublicKey(properties.alipayPublicKey().trim());
        config.setCharset(CHARSET);
        config.setSignType("RSA2");
        try {
            return new DefaultAlipayClient(config);
        } catch (AlipayApiException exception) {
            throw unavailable();
        }
    }

    private static String paymentStatus(String tradeStatus) {
        return switch (tradeStatus == null ? "" : tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
            case "WAIT_BUYER_PAY" -> "PENDING";
            case "TRADE_CLOSED" -> "CLOSED";
            default -> "UNKNOWN";
        };
    }

    private static String amount(long amountMinor) {
        return BigDecimal.valueOf(amountMinor)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private static String subject(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "Lyle AI 套餐";
        normalized = normalized.replaceAll("[/=&]", " ").replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(normalized.length(), 128));
    }

    private static void requireCny(String currency) {
        if (!"CNY".equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付宝网站支付仅支持当前人民币套餐。 ");
        }
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
        }
        return value.trim();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付宝服务暂时不可用，请稍后重试。 ");
    }
}
