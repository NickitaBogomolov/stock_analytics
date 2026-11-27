-- Результаты квант-лаборатории (C++): бэктест, Монте-Карло, оптимизация портфеля.
-- Параметры и результат хранятся как JSONB: структура зависит от вида расчёта.
CREATE TABLE lab_run (
    lab_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind       VARCHAR(16)      NOT NULL CHECK (kind IN ('backtest', 'montecarlo', 'portfolio')),
    params     JSONB            NOT NULL,
    result     JSONB            NOT NULL,
    headline   VARCHAR(256)     NOT NULL,  -- краткий итог для списка (не нужно читать весь result)
    compute_ms DOUBLE PRECISION NOT NULL CHECK (compute_ms >= 0),
    created_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX lab_run_created_idx ON lab_run (created_at DESC);

CREATE TABLE lab_run_instrument (
    lab_run_id BIGINT      NOT NULL REFERENCES lab_run (lab_run_id) ON DELETE CASCADE,
    ticker     VARCHAR(32) NOT NULL REFERENCES instrument (ticker) ON DELETE CASCADE,
    position   SMALLINT    NOT NULL CHECK (position >= 0),
    PRIMARY KEY (lab_run_id, ticker)
);
