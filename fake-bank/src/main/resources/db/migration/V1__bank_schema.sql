-- Schema RIÊNG của Fake Bank (database riêng): mô phỏng hệ thống phân tán, transaction boundary độc lập với hệ thống đặt phòng.
CREATE TABLE bank_accounts (
    id         BIGSERIAL PRIMARY KEY,
    account_no VARCHAR(40)    NOT NULL UNIQUE,
    owner_name VARCHAR(100)   NOT NULL,
    balance    NUMERIC(16, 2) NOT NULL CHECK (balance >= 0),
    currency   VARCHAR(3)     NOT NULL DEFAULT 'VND',
    scenario   VARCHAR(30)    NOT NULL DEFAULT 'SUCCESS',
    merchant   BOOLEAN        NOT NULL DEFAULT FALSE,
    version    BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE bank_transactions (
    id                 BIGSERIAL PRIMARY KEY,
    merchant_id        VARCHAR(50)    NOT NULL,
    merchant_reference VARCHAR(60),
    payment_intent_id  VARCHAR(60),
    idempotency_key    VARCHAR(100)   NOT NULL,
    request_hash       VARCHAR(64)    NOT NULL,
    payer_account_no   VARCHAR(40)    NOT NULL,
    amount             NUMERIC(16, 2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    failure_code       VARCHAR(50),
    provider_txn_id    VARCHAR(60)    NOT NULL UNIQUE,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_bank_idem UNIQUE (merchant_id, idempotency_key)
);

-- Sổ cái kép: mỗi giao dịch có bút toán nợ/có cân bằng.
CREATE TABLE bank_ledger_entries (
    id         BIGSERIAL PRIMARY KEY,
    txn_id     BIGINT         NOT NULL REFERENCES bank_transactions (id),
    ref_kind   VARCHAR(10)    NOT NULL,   -- PAYMENT | REFUND
    ref_id     VARCHAR(60)    NOT NULL,
    account_id BIGINT         NOT NULL REFERENCES bank_accounts (id),
    debit      NUMERIC(16, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit     NUMERIC(16, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    created_at TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_ledger_txn ON bank_ledger_entries (txn_id);

CREATE TABLE bank_refunds (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     VARCHAR(50)    NOT NULL,
    refund_key      VARCHAR(100)   NOT NULL,
    request_hash    VARCHAR(64)    NOT NULL,
    txn_id          BIGINT         NOT NULL REFERENCES bank_transactions (id),
    amount          NUMERIC(16, 2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)    NOT NULL,
    failure_code    VARCHAR(50),
    provider_ref    VARCHAR(60)    NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_bank_refund_key UNIQUE (merchant_id, refund_key)
);

CREATE TABLE bank_webhook_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(80)  NOT NULL,
    event_type      VARCHAR(30)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(15)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_bank_outbox ON bank_webhook_outbox (status, next_attempt_at);

-- Tài khoản TEST (tiền giả lập). Mỗi tài khoản gắn một kịch bản lỗi để kiểm thử fault injection.
INSERT INTO bank_accounts (account_no, owner_name, balance, scenario, merchant) VALUES
 ('MERCHANT-DATPHONG', 'Merchant Dat Phong', 0, 'SUCCESS', TRUE),
 ('TEST-OK',        'Khách test - thành công',            50000000, 'SUCCESS',            FALSE),
 ('TEST-POOR',      'Khách test - không đủ tiền',                1000, 'SUCCESS',            FALSE),
 ('TEST-DECLINED',  'Khách test - bị từ chối',            50000000, 'DECLINED',           FALSE),
 ('TEST-TIMEOUT',   'Khách test - trừ tiền rồi timeout',  50000000, 'TIMEOUT_AFTER_DEBIT', FALSE),
 ('TEST-LOST',      'Khách test - mất callback',          50000000, 'CALLBACK_LOST',      FALSE),
 ('TEST-DUP',       'Khách test - callback lặp',          50000000, 'DUPLICATE_CALLBACK', FALSE),
 ('TEST-DELAY',     'Khách test - callback chậm',         50000000, 'DELAYED_CALLBACK',   FALSE),
 ('TEST-DBFAIL',    'Khách test - lỗi DB phía bank',      50000000, 'DB_OUTAGE',          FALSE),
 ('TEST-REFUNDFAIL','Khách test - refund bị từ chối',     50000000, 'REFUND_FAIL',        FALSE);
