ALTER TABLE chat_message
  ADD COLUMN turn_id VARCHAR(64) NULL AFTER conversation_id,
  ADD COLUMN metadata_json JSON NULL AFTER content,
  ADD KEY idx_chat_message_conversation_turn (conversation_id, turn_id);
