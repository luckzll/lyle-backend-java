ALTER TABLE agent_mission
  ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0;

UPDATE agent_mission mission
LEFT JOIN (
  SELECT mission_id, MAX(sequence_no) AS max_sequence
  FROM mission_event
  GROUP BY mission_id
) existing ON existing.mission_id = mission.id
SET mission.event_sequence = COALESCE(existing.max_sequence, 0);
