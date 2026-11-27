-- Источник «moex»: котировки загружаются с Московской биржи (ISS API).
ALTER TABLE instrument DROP CONSTRAINT instrument_source_check;
ALTER TABLE instrument ADD CONSTRAINT instrument_source_check CHECK (source IN ('demo', 'upload', 'moex'));

-- Краткое наименование бумаги (например, «Сбербанк»); для загруженных CSV — NULL.
ALTER TABLE instrument ADD COLUMN name VARCHAR(128);
-- Когда котировки последний раз синхронизировались с биржей.
ALTER TABLE instrument ADD COLUMN synced_at TIMESTAMPTZ;
