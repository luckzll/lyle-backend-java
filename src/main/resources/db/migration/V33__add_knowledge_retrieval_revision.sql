ALTER TABLE knowledge_file
    ADD COLUMN retrieval_revision VARCHAR(64) NOT NULL DEFAULT 'legacy';
