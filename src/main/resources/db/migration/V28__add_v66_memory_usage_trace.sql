-- V6.6 keeps the user-visible memory explanation on the assistant message.
-- metadata_json already has JSON capacity from V21; this covering index makes
-- refresh/retry replay deterministic without introducing a duplicate table.
ALTER TABLE chat_message
  ADD KEY idx_chat_message_v66_replay (conversation_id, turn_id, role, created_at);
