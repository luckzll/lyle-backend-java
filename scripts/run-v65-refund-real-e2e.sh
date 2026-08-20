#!/usr/bin/env bash
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/lyle-v65-refund-real.XXXXXX")"
RUN_ID="$(basename "$RUN_DIR" | tr -cd '[:alnum:]')"
MYSQL_DATABASE="lyle_v65_refund_${RUN_ID}"
MYSQL_HOST="${V65_REAL_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${V65_REAL_MYSQL_PORT:-3306}"
MYSQL_USER="${V65_REAL_MYSQL_USER:-root}"
MYSQL_PASSWORD="${V65_REAL_MYSQL_PASSWORD:-}"
SERVER_PORT="${V65_REAL_SERVER_PORT:-18085}"
BASE_URL="http://127.0.0.1:${SERVER_PORT}/api"
JAVA_PID=""
MYSQL_CREATED=false

mysql_command() {
  MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    -h "$MYSQL_HOST" \
    -P "$MYSQL_PORT" \
    -u "$MYSQL_USER" \
    "$@"
}

cleanup() {
  if [[ -n "$JAVA_PID" ]]; then
    kill "$JAVA_PID" >/dev/null 2>&1 || true
    wait "$JAVA_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$MYSQL_CREATED" == "true" && "$MYSQL_DATABASE" =~ ^lyle_v65_refund_[[:alnum:]]+$ ]]; then
    mysql_command -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$RUN_DIR"
}
trap cleanup EXIT INT TERM

for command in curl jq lsof mysql mvn; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "V6.5 refund real E2E requires '$command'." >&2
    exit 2
  fi
done

if lsof -nP -iTCP:"$SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "V6.5 refund real E2E requires port $SERVER_PORT to be free." >&2
  exit 2
fi

mysql_command -e "CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
MYSQL_CREATED=true

(
  cd "$BACKEND_DIR"
  export MYSQL_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
  export MYSQL_USERNAME="$MYSQL_USER"
  export MYSQL_PASSWORD="$MYSQL_PASSWORD"
  export JWT_SECRET="v65-refund-real-jwt-secret-8c4eef5ea1174cb2a9ce"
  export JWT_KEY_ID=v65-refund-real
  export AUTH_ACCEPT_LEGACY_JWT=false
  export AUTH_RATE_LIMIT_PER_MINUTE=240
  export AUTH_LOGIN_USERNAME_RATE_LIMIT_PER_MINUTE=60
  export AUTH_REGISTER_RATE_LIMIT_PER_MINUTE=60
  export REDIS_ENABLED=false
  export MINIO_ENABLED=false
  export QDRANT_ENABLED=false
  export TOS_ENABLED=false
  export PAYMENT_CHECKOUT_ENABLED=true
  export PAYMENT_PROVIDER=MOCK
  export PAYMENT_FULFILLMENT_WORKER_ENABLED=true
  export PAYMENT_FULFILLMENT_SCAN_MILLIS=100
  exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$SERVER_PORT"
) >"$RUN_DIR/java.log" 2>&1 &
JAVA_PID="$!"

for _ in $(seq 1 120); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if ! curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
  echo "Java service did not become healthy." >&2
  tail -n 180 "$RUN_DIR/java.log" >&2 || true
  exit 1
fi

assert_status() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  local body_file="$4"
  if [[ "$actual" != "$expected" ]]; then
    echo "$label expected HTTP $expected, got $actual" >&2
    cat "$body_file" >&2
    exit 1
  fi
}

api() {
  local method="$1"
  local path="$2"
  local body="$3"
  local output_file="$4"
  local idempotency_key="${5:-}"
  local args=(-sS -o "$output_file" -w '%{http_code}' -X "$method")
  if [[ -n "${ACCESS_TOKEN:-}" ]]; then
    args+=(-H "Authorization: Bearer $ACCESS_TOKEN")
  fi
  if [[ -n "$idempotency_key" ]]; then
    args+=(-H "Idempotency-Key: $idempotency_key")
  fi
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}" "$BASE_URL$path"
}

USERNAME="v65_refund_${RUN_ID}"
PASSWORD='v65-refund-password'
REGISTER_BODY="$(jq -nc --arg username "$USERNAME" --arg password "$PASSWORD" '{username:$username,password:$password}')"
status="$(api POST /auth/register "$REGISTER_BODY" "$RUN_DIR/register.json")"
assert_status register 200 "$status" "$RUN_DIR/register.json"
USER_ID="$(jq -r '.user.id' "$RUN_DIR/register.json")"

mysql_command "$MYSQL_DATABASE" -e \
  "UPDATE app_user SET role = 'ADMIN' WHERE id = $USER_ID AND username = '$USERNAME'"

status="$(api POST /auth/login "$REGISTER_BODY" "$RUN_DIR/login.json")"
assert_status login 200 "$status" "$RUN_DIR/login.json"
ACCESS_TOKEN="$(jq -r '.accessToken' "$RUN_DIR/login.json")"

PRODUCT_BODY='{"productCode":"V65_REAL_PRO","productType":"PLAN","displayName":"V6.5 Real Pro","description":"Isolated refund contract"}'
status="$(api POST /admin/billing/products "$PRODUCT_BODY" "$RUN_DIR/product.json" product-v65-real)"
assert_status create-product 200 "$status" "$RUN_DIR/product.json"

PRICE_BODY='{"productCode":"V65_REAL_PRO","versionCode":"V65_REAL_PRO_30D","currency":"CNY","amountMinor":100,"includedCreditMicros":50000000,"validityDays":30,"benefits":["refund contract"],"pricingRules":{}}'
status="$(api POST /admin/billing/prices "$PRICE_BODY" "$RUN_DIR/price.json" price-v65-real)"
assert_status create-price 200 "$status" "$RUN_DIR/price.json"
status="$(api POST /admin/billing/prices/V65_REAL_PRO_30D/publish '' "$RUN_DIR/publish.json" publish-v65-real)"
assert_status publish-price 200 "$status" "$RUN_DIR/publish.json"

status="$(api GET /billing/account '' "$RUN_DIR/account.json")"
assert_status account 200 "$status" "$RUN_DIR/account.json"
ORDER_BODY="$(jq -nc --arg requestId "order-$RUN_ID" '{priceVersionCode:"V65_REAL_PRO_30D",purchaseIntent:"NEW_PURCHASE",clientRequestId:$requestId}')"
status="$(api POST /billing/orders "$ORDER_BODY" "$RUN_DIR/order.json")"
assert_status create-order 200 "$status" "$RUN_DIR/order.json"
ORDER_NO="$(jq -r '.orderNo' "$RUN_DIR/order.json")"

status="$(api POST "/billing/orders/$ORDER_NO/checkout" '' "$RUN_DIR/checkout.json")"
assert_status checkout 200 "$status" "$RUN_DIR/checkout.json"
PAYMENT_NO="$(jq -r '.paymentNo' "$RUN_DIR/checkout.json")"
status="$(api POST "/admin/billing/payments/$PAYMENT_NO/mock:complete" '' "$RUN_DIR/payment.json" payment-v65-real)"
assert_status complete-payment 200 "$status" "$RUN_DIR/payment.json"

for _ in $(seq 1 100); do
  status="$(api GET "/billing/orders/$ORDER_NO/status" '' "$RUN_DIR/order-status.json")"
  assert_status order-status 200 "$status" "$RUN_DIR/order-status.json"
  [[ "$(jq -r '.status' "$RUN_DIR/order-status.json")" == "FULFILLED" ]] && break
  sleep 0.1
done
if [[ "$(jq -r '.status' "$RUN_DIR/order-status.json")" != "FULFILLED" ]]; then
  echo "Order did not reach FULFILLED." >&2
  cat "$RUN_DIR/order-status.json" >&2
  exit 1
fi

REFUND_REQUEST_ID="refund-$RUN_ID"
REFUND_BODY="$(jq -nc --arg requestId "$REFUND_REQUEST_ID" '{requestId:$requestId,reasonCode:"USER_REQUEST",reasonNote:"V6.5 real replay contract"}')"
status="$(api POST "/admin/billing/orders/$ORDER_NO/refunds" "$REFUND_BODY" "$RUN_DIR/refund-first.json" "$REFUND_REQUEST_ID")"
assert_status first-refund 200 "$status" "$RUN_DIR/refund-first.json"
status="$(api POST "/admin/billing/orders/$ORDER_NO/refunds" "$REFUND_BODY" "$RUN_DIR/refund-replay.json" "$REFUND_REQUEST_ID")"
assert_status refund-replay 200 "$status" "$RUN_DIR/refund-replay.json"

FIRST_REFUND_NO="$(jq -r '.refund_no' "$RUN_DIR/refund-first.json")"
REPLAY_REFUND_NO="$(jq -r '.refund_no' "$RUN_DIR/refund-replay.json")"
if [[ -z "$FIRST_REFUND_NO" || "$FIRST_REFUND_NO" == "null" || "$FIRST_REFUND_NO" != "$REPLAY_REFUND_NO" ]]; then
  echo "Refund replay did not return the original refund." >&2
  diff -u "$RUN_DIR/refund-first.json" "$RUN_DIR/refund-replay.json" >&2 || true
  exit 1
fi
if [[ "$(jq -r '.status' "$RUN_DIR/refund-replay.json")" != "SUCCEEDED" ]]; then
  echo "Refund replay did not return SUCCEEDED." >&2
  cat "$RUN_DIR/refund-replay.json" >&2
  exit 1
fi

status="$(api GET "/billing/orders/$ORDER_NO" '' "$RUN_DIR/refunded-order.json")"
assert_status refunded-order 200 "$status" "$RUN_DIR/refunded-order.json"
jq -e '.status == "REFUNDED" and .refundStatus == "SUCCEEDED" and (.refundSucceededAt | type == "string" and length > 0)' \
  "$RUN_DIR/refunded-order.json" >/dev/null

REFUND_COUNT="$(mysql_command "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM refund_order WHERE order_id = (SELECT id FROM billing_order WHERE order_no = '$ORDER_NO') AND request_id = '$REFUND_REQUEST_ID'")"
REVERSAL_COUNT="$(mysql_command "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM commercial_ledger_entry WHERE entry_type = 'REFUND_REVERSAL' AND source_id = '$FIRST_REFUND_NO'")"
if [[ "$REFUND_COUNT" != "1" || "$REVERSAL_COUNT" != "1" ]]; then
  echo "Expected one refund and one reversal, got refunds=$REFUND_COUNT reversals=$REVERSAL_COUNT." >&2
  exit 1
fi

echo "V6.5 real refund idempotency: PASS"
echo "refundNo=$FIRST_REFUND_NO responses=200/200 refunds=$REFUND_COUNT reversals=$REVERSAL_COUNT"
