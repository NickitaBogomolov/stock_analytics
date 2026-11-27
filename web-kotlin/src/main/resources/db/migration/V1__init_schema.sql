-- Схема хранения Market Analytics (соответствует docs/uml/erd.puml).
-- Цены хранятся как DOUBLE PRECISION: расчёты ведутся в double, и так значения
-- из БД бит-в-бит совпадают с результатом разбора CSV (паритет с C++ ядром).

CREATE TABLE instrument (
    ticker      VARCHAR(32) PRIMARY KEY CHECK (ticker ~ '^[A-Za-z0-9_.-]{1,32}$'),
    source      VARCHAR(16) NOT NULL CHECK (source IN ('demo', 'upload')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE quote (
    ticker      VARCHAR(32)      NOT NULL REFERENCES instrument (ticker) ON DELETE CASCADE,
    trade_date  DATE             NOT NULL,
    open        DOUBLE PRECISION NOT NULL CHECK (open > 0),
    high        DOUBLE PRECISION NOT NULL,
    low         DOUBLE PRECISION NOT NULL CHECK (low > 0),
    close       DOUBLE PRECISION NOT NULL CHECK (close > 0),
    volume      BIGINT           NOT NULL CHECK (volume >= 0),
    PRIMARY KEY (ticker, trade_date),
    CONSTRAINT quote_ohlc_consistent CHECK (high >= GREATEST(open, close) AND low <= LEAST(open, close))
);

CREATE TABLE analysis_run (
    run_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker        VARCHAR(32)      NOT NULL REFERENCES instrument (ticker) ON DELETE CASCADE,
    engine        VARCHAR(8)       NOT NULL CHECK (engine IN ('kotlin', 'cpp')),
    fast_period   INT              NOT NULL CHECK (fast_period > 0),
    slow_period   INT              NOT NULL,
    rsi_period    INT              NOT NULL CHECK (rsi_period > 0),
    risk_free_pct DOUBLE PRECISION NOT NULL,
    sessions      INT              NOT NULL CHECK (sessions > 0),
    first_date    DATE             NOT NULL,
    last_date     DATE             NOT NULL,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CHECK (fast_period < slow_period),
    CHECK (first_date <= last_date)
);
CREATE INDEX analysis_run_ticker_created_idx ON analysis_run (ticker, created_at DESC);

CREATE TABLE metrics_report (
    run_id                BIGINT PRIMARY KEY REFERENCES analysis_run (run_id) ON DELETE CASCADE,
    last_close            DOUBLE PRECISION NOT NULL,
    total_return_pct      DOUBLE PRECISION NOT NULL,
    annual_volatility_pct DOUBLE PRECISION NOT NULL CHECK (annual_volatility_pct >= 0),
    max_drawdown_pct      DOUBLE PRECISION NOT NULL CHECK (max_drawdown_pct BETWEEN 0 AND 100),
    sharpe_ratio          DOUBLE PRECISION NOT NULL,
    rsi                   DOUBLE PRECISION NOT NULL CHECK (rsi BETWEEN 0 AND 100),
    trend                 VARCHAR(10)      NOT NULL CHECK (trend IN ('UPTREND', 'DOWNTREND', 'SIDEWAYS')),
    rsi_zone              VARCHAR(10)      NOT NULL CHECK (rsi_zone IN ('OVERBOUGHT', 'OVERSOLD', 'NEUTRAL'))
);

-- Снимок рядов на момент запуска: котировки инструмента могут быть перезагружены позже.
CREATE TABLE indicator_point (
    run_id     BIGINT           NOT NULL REFERENCES analysis_run (run_id) ON DELETE CASCADE,
    trade_date DATE             NOT NULL,
    close      DOUBLE PRECISION NOT NULL,
    sma_fast   DOUBLE PRECISION,
    sma_slow   DOUBLE PRECISION,
    rsi        DOUBLE PRECISION,
    PRIMARY KEY (run_id, trade_date)
);

CREATE TABLE trade_signal (
    run_id     BIGINT           NOT NULL REFERENCES analysis_run (run_id) ON DELETE CASCADE,
    trade_date DATE             NOT NULL,
    type       VARCHAR(4)       NOT NULL CHECK (type IN ('BUY', 'SELL')),
    price      DOUBLE PRECISION NOT NULL CHECK (price > 0),
    PRIMARY KEY (run_id, trade_date)
);

CREATE TABLE comparison (
    comparison_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fast_period   INT              NOT NULL,
    slow_period   INT              NOT NULL,
    rsi_period    INT              NOT NULL,
    risk_free_pct DOUBLE PRECISION NOT NULL,
    common_dates  INT              NOT NULL CHECK (common_dates > 0),
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE comparison_item (
    comparison_id         BIGINT           NOT NULL REFERENCES comparison (comparison_id) ON DELETE CASCADE,
    ticker                VARCHAR(32)      NOT NULL REFERENCES instrument (ticker) ON DELETE CASCADE,
    position              SMALLINT         NOT NULL CHECK (position >= 0),
    total_return_pct      DOUBLE PRECISION NOT NULL,
    annual_volatility_pct DOUBLE PRECISION NOT NULL,
    max_drawdown_pct      DOUBLE PRECISION NOT NULL,
    sharpe_ratio          DOUBLE PRECISION NOT NULL,
    rsi                   DOUBLE PRECISION NOT NULL,
    trend                 VARCHAR(10)      NOT NULL,
    PRIMARY KEY (comparison_id, ticker)
);

CREATE TABLE correlation_cell (
    comparison_id BIGINT           NOT NULL,
    ticker_a      VARCHAR(32)      NOT NULL,
    ticker_b      VARCHAR(32)      NOT NULL,
    value         DOUBLE PRECISION NOT NULL CHECK (value BETWEEN -1.000001 AND 1.000001),
    PRIMARY KEY (comparison_id, ticker_a, ticker_b),
    FOREIGN KEY (comparison_id, ticker_a) REFERENCES comparison_item (comparison_id, ticker) ON DELETE CASCADE,
    FOREIGN KEY (comparison_id, ticker_b) REFERENCES comparison_item (comparison_id, ticker) ON DELETE CASCADE
);
