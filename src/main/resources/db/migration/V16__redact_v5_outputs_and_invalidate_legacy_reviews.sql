-- Sensitive tool output must never remain in Step, Artifact, event, or final review storage.
-- The application now redacts before persistence; this migration repairs pre-release V5 rows.

UPDATE agent_step_run r
JOIN agent_plan_step s ON s.id = r.plan_step_id
SET r.output_summary = JSON_SET(
  JSON_REMOVE(
    r.output_summary,
    '$.raw', '$.content', '$.fullContent', '$.body', '$.snippet', '$.text',
    '$.reasoning', '$.privateReasoning', '$.chainOfThought', '$.modelReasoning'
  ),
  '$.summary', CASE
    WHEN s.tool_ref = 'memory.snapshot'
      THEN '历史个人记忆正文已清除；请重新运行以生成结构化脱敏摘要。'
    ELSE '历史外部网页正文已清除；请重新运行以生成结构化脱敏摘要。'
  END,
  '$.redacted', TRUE,
  '$.sensitivity', CASE
    WHEN s.tool_ref = 'memory.snapshot' THEN 'PERSONAL'
    ELSE 'EXTERNAL_CONTENT'
  END,
  '$.criterionEvidence', JSON_ARRAY()
)
WHERE s.tool_ref IN ('memory.snapshot', 'research.search');

UPDATE agent_artifact a
JOIN agent_step_run r ON r.id = a.step_run_id
JOIN agent_plan_step s ON s.id = r.plan_step_id
SET a.summary = CASE
      WHEN s.tool_ref = 'memory.snapshot'
        THEN '历史个人记忆正文已清除；请重新运行以生成结构化脱敏摘要。'
      ELSE '历史外部网页正文已清除；请重新运行以生成结构化脱敏摘要。'
    END,
    a.metadata_json = JSON_SET(
      JSON_REMOVE(a.metadata_json, '$.raw', '$.content', '$.body', '$.snippet', '$.text'),
      '$.redacted', TRUE,
      '$.sensitivity', CASE
        WHEN s.tool_ref = 'memory.snapshot' THEN 'PERSONAL'
        ELSE 'EXTERNAL_CONTENT'
      END,
      '$.criterionEvidence', JSON_ARRAY()
    )
WHERE a.artifact_type = 'EVIDENCE'
  AND s.tool_ref IN ('memory.snapshot', 'research.search');

UPDATE agent_task_event e
SET e.payload_json = JSON_OBJECT(
  'redacted', TRUE,
  'reason', 'SENSITIVE_OUTPUT_POLICY_UPGRADE',
  'originalEventType', e.event_type
)
WHERE e.event_type IN ('step.completed', 'task.completed')
  AND EXISTS (
    SELECT 1
    FROM agent_task_run tr
    JOIN agent_step_run sr ON sr.task_run_id = tr.id
    JOIN agent_plan_step ps ON ps.id = sr.plan_step_id
    WHERE tr.agent_task_id = e.agent_task_id
      AND ps.tool_ref IN ('memory.snapshot', 'research.search')
  );

-- Reviewer v1 results predate strict redaction and the final evidence-mapping policy.
-- Keep the audit record, but make the conclusion explicitly non-authoritative.
UPDATE agent_artifact
SET summary = '历史 V5 验收结论已因证据与脱敏策略升级而失效，请重新运行任务。',
    content_ref = NULL,
    evidence_refs = JSON_ARRAY(),
    metadata_json = JSON_OBJECT(
      'outcome', 'PARTIAL',
      'criteria', JSON_ARRAY(),
      'facts', JSON_ARRAY(),
      'inferences', JSON_ARRAY(),
      'recommendations', JSON_ARRAY('按当前 V5 策略重新运行并生成逐项脱敏证据。'),
      'gaps', JSON_ARRAY('历史结论未经过 reviewer v2 重新评审。'),
      'promptVersion', 'legacy-invalidated-v16',
      'redacted', TRUE,
      'superseded', TRUE
    )
WHERE artifact_type = 'RESULT'
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.promptVersion')), '') <> 'lyle-agent-v5-reviewer-2';

UPDATE agent_task t
JOIN (
  SELECT DISTINCT agent_task_id
  FROM agent_artifact
  WHERE artifact_type = 'RESULT'
    AND JSON_EXTRACT(metadata_json, '$.superseded') = TRUE
) legacy ON legacy.agent_task_id = t.id
SET t.status = 'PARTIALLY_COMPLETED',
    t.version = t.version + 1,
    t.updated_at = CURRENT_TIMESTAMP(6)
WHERE t.status = 'COMPLETED';

UPDATE agent_task_run tr
JOIN (
  SELECT DISTINCT task_run_id
  FROM agent_artifact
  WHERE artifact_type = 'RESULT'
    AND JSON_EXTRACT(metadata_json, '$.superseded') = TRUE
) legacy ON legacy.task_run_id = tr.id
SET tr.status = 'PARTIALLY_COMPLETED',
    tr.version = tr.version + 1,
    tr.finished_at = COALESCE(tr.finished_at, CURRENT_TIMESTAMP(6)),
    tr.updated_at = CURRENT_TIMESTAMP(6)
WHERE tr.status = 'COMPLETED';
