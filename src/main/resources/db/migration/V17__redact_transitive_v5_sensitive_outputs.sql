-- V16 removed the original sensitive tool rows. Older synthesis steps had already
-- copied those bodies, so scrub every derived copy as well.

UPDATE agent_step_run r
JOIN agent_plan_step s ON s.id = r.plan_step_id
SET r.output_summary = JSON_SET(
  JSON_REMOVE(
    r.output_summary,
    '$.raw', '$.content', '$.fullContent', '$.body', '$.snippet', '$.text',
    '$.reasoning', '$.privateReasoning', '$.chainOfThought', '$.modelReasoning'
  ),
  '$.summary', '历史综合结果中的敏感上游正文已清除；请重新运行以生成结构化脱敏结论。',
  '$.redacted', TRUE,
  '$.sensitivity', CASE
    WHEN CAST(r.output_summary AS CHAR) LIKE '%长期摘要：%'
      OR CAST(r.output_summary AS CHAR) LIKE '%长期记忆摘要%'
      THEN 'PERSONAL'
    ELSE 'EXTERNAL_CONTENT'
  END,
  '$.criterionEvidence', JSON_ARRAY()
)
WHERE s.tool_ref = 'analysis.synthesize'
  AND (
    CAST(r.output_summary AS CHAR) LIKE '%长期摘要：%'
    OR CAST(r.output_summary AS CHAR) LIKE '%长期记忆摘要%'
    OR CAST(r.output_summary AS CHAR) LIKE '%网页搜索结果：%'
  );

UPDATE agent_artifact a
JOIN agent_step_run r ON r.id = a.step_run_id
JOIN agent_plan_step s ON s.id = r.plan_step_id
SET a.metadata_json = JSON_SET(
      JSON_REMOVE(a.metadata_json, '$.raw', '$.content', '$.body', '$.snippet', '$.text'),
      '$.redacted', TRUE,
      '$.sensitivity', CASE
        WHEN a.summary LIKE '%长期摘要：%' OR a.summary LIKE '%长期记忆摘要%'
          THEN 'PERSONAL'
        ELSE 'EXTERNAL_CONTENT'
      END,
      '$.criterionEvidence', JSON_ARRAY()
    ),
    a.summary = '历史综合结果中的敏感上游正文已清除；请重新运行以生成结构化脱敏结论。'
WHERE s.tool_ref = 'analysis.synthesize'
  AND (
    a.summary LIKE '%长期摘要：%'
    OR a.summary LIKE '%长期记忆摘要%'
    OR a.summary LIKE '%网页搜索结果：%'
  );
