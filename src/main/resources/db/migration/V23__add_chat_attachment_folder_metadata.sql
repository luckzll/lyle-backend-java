ALTER TABLE chat_attachment
  ADD COLUMN source_type VARCHAR(32) NULL,
  ADD COLUMN folder_group_id VARCHAR(64) NULL,
  ADD COLUMN folder_root VARCHAR(255) NULL,
  ADD COLUMN relative_path VARCHAR(1024) NULL;

CREATE INDEX idx_chat_attachment_message_group_position
  ON chat_attachment(message_id, folder_group_id, position);
