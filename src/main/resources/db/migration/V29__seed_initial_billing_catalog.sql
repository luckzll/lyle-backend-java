INSERT INTO billing_product (
  id, product_code, product_type, display_name, description,
  status, created_at, updated_at
) VALUES
  (-29000, 'FREE', 'PLAN', '免费版', '用于体验 Lyle 的基础 AI 能力。', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (-29001, 'LYLE_STARTER', 'PLAN', '轻享版', '适合日常聊天、写作与轻量资料处理。', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (-29002, 'LYLE_PLUS', 'PLAN', '进阶版', '适合高频创作、知识库与多步骤任务。', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (-29003, 'LYLE_PRO', 'PLAN', '专业版', '适合重度使用、长文档与自动化工作流。', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO billing_price_version (
  id, product_id, version_code, currency, amount_minor,
  included_credit_micros, validity_days, entitlement_snapshot,
  pricing_rule_snapshot, status, sell_from, sell_until,
  created_at, published_at
) VALUES
  (
    -29100, -29000, 'LYLE_FREE_CNY_2026_01', 'CNY', 0,
    100000000, NULL,
    JSON_OBJECT(
      'benefits', JSON_ARRAY('100 AI 体验额度', '基础聊天、写作与资料整理', '用量明细可查看', '无需绑定支付方式'),
      'includedCreditMicros', 100000000
    ),
    JSON_OBJECT('settlement', 'USAGE_BASED', 'purchasable', FALSE),
    'PUBLISHED', CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  ),
  (
    -29101, -29001, 'LYLE_STARTER_CNY_2026_01', 'CNY', 990,
    1000000000, 30,
    JSON_OBJECT(
      'benefits', JSON_ARRAY('1,000 AI 额度', '额度 30 天有效', '聊天、写作、知识库与自动化按实际用量计费', '一次性购买，不自动续费'),
      'includedCreditMicros', 1000000000
    ),
    JSON_OBJECT('settlement', 'USAGE_BASED'),
    'PUBLISHED', CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  ),
  (
    -29102, -29002, 'LYLE_PLUS_CNY_2026_01', 'CNY', 1990,
    2500000000, 30,
    JSON_OBJECT(
      'benefits', JSON_ARRAY('2,500 AI 额度', '额度 30 天有效', '聊天、写作、知识库与自动化按实际用量计费', '一次性购买，不自动续费'),
      'includedCreditMicros', 2500000000
    ),
    JSON_OBJECT('settlement', 'USAGE_BASED'),
    'PUBLISHED', CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  ),
  (
    -29103, -29003, 'LYLE_PRO_CNY_2026_01', 'CNY', 3990,
    6000000000, 30,
    JSON_OBJECT(
      'benefits', JSON_ARRAY('6,000 AI 额度', '额度 30 天有效', '聊天、写作、知识库与自动化按实际用量计费', '一次性购买，不自动续费'),
      'includedCreditMicros', 6000000000
    ),
    JSON_OBJECT('settlement', 'USAGE_BASED'),
    'PUBLISHED', CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  );

-- The first rule set is intentionally published while settlement remains in SHADOW mode.
-- This lets the existing metering pipeline produce auditable estimates before real deductions are enabled.
INSERT INTO billing_usage_pricing_version (
  id, version_code, rules_json, status, effective_from,
  effective_until, created_at, published_at
) VALUES (
  -29201,
  'LYLE_USAGE_CNY_2026_01',
  JSON_OBJECT(
    'LLM_TEXT', JSON_OBJECT('unit', 1000, 'creditMicros', 1000000, 'minimum', 1000000),
    'LLM_VISION', JSON_OBJECT('unit', 1000, 'creditMicros', 2000000, 'minimum', 2000000),
    'EMBEDDING', JSON_OBJECT('unit', 1000, 'creditMicros', 200000, 'minimum', 200000),
    'RERANK', JSON_OBJECT('unit', 1000, 'creditMicros', 200000, 'minimum', 200000),
    'IMAGE_GENERATION', JSON_OBJECT('unit', 1, 'creditMicros', 20000000, 'minimum', 20000000)
  ),
  'PUBLISHED', CURRENT_TIMESTAMP(6), NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);
