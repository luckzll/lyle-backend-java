ALTER TABLE knowledge_file
  ADD COLUMN storage_provider VARCHAR(32) NULL AFTER storage_path,
  ADD COLUMN bucket VARCHAR(255) NULL AFTER storage_provider,
  ADD COLUMN object_key VARCHAR(1024) NULL AFTER bucket,
  ADD COLUMN original_name VARCHAR(255) NULL AFTER object_key,
  ADD COLUMN content_type VARCHAR(255) NULL AFTER original_name,
  ADD COLUMN file_size BIGINT NULL AFTER content_type,
  ADD COLUMN visibility VARCHAR(32) NOT NULL DEFAULT 'private' AFTER file_size;

UPDATE knowledge_file
SET
  storage_provider = CASE
    WHEN storage_path LIKE 's3://%' THEN 'MINIO'
    WHEN storage_path LIKE 'file://%' THEN 'LOCAL'
    ELSE 'LOCAL'
  END,
  original_name = file_name,
  visibility = 'private'
WHERE storage_provider IS NULL;
