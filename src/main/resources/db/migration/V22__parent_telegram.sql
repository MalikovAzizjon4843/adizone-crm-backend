ALTER TABLE parents
    ADD COLUMN IF NOT EXISTS telegram_chat_id VARCHAR(50);
